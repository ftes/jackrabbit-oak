/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.plugins.document;

import java.io.Closeable;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import org.apache.jackrabbit.oak.commons.sort.StringSort;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp.Condition;
import org.apache.jackrabbit.oak.plugins.document.UpdateOp.Key;
import org.apache.jackrabbit.oak.plugins.document.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.StandardSystemProperty.LINE_SEPARATOR;
import static com.google.common.collect.Iterables.all;
import static com.google.common.collect.Iterators.partition;
import static com.google.common.util.concurrent.Atomics.newReference;
import static java.util.Collections.singletonMap;
import static org.apache.jackrabbit.oak.plugins.document.Collection.NODES;
import static org.apache.jackrabbit.oak.plugins.document.NodeDocument.MODIFIED_IN_SECS;
import static org.apache.jackrabbit.oak.plugins.document.NodeDocument.SplitDocType.COMMIT_ROOT_ONLY;
import static org.apache.jackrabbit.oak.plugins.document.NodeDocument.SplitDocType.DEFAULT_LEAF;
import static org.apache.jackrabbit.oak.plugins.document.UpdateOp.Condition.newEqualsCondition;

public class VersionGarbageCollector {
    //Kept less than MongoDocumentStore.IN_CLAUSE_BATCH_SIZE to avoid re-partitioning
    private static final int DELETE_BATCH_SIZE = 450;
    private static final int PROGRESS_BATCH_SIZE = 10000;
    private static final Key KEY_MODIFIED = new Key(MODIFIED_IN_SECS, null);
    private final DocumentNodeStore nodeStore;
    private final DocumentStore ds;
    private final VersionGCSupport versionStore;
    private int overflowToDiskThreshold = 100000;
    private final AtomicReference<GCJob> collector = newReference();

    private static final Logger log = LoggerFactory.getLogger(VersionGarbageCollector.class);

    /**
     * Split document types which can be safely garbage collected
     */
    private static final Set<NodeDocument.SplitDocType> GC_TYPES = EnumSet.of(
            DEFAULT_LEAF, COMMIT_ROOT_ONLY);

    VersionGarbageCollector(DocumentNodeStore nodeStore,
                            VersionGCSupport gcSupport) {
        this.nodeStore = nodeStore;
        this.versionStore = gcSupport;
        this.ds = nodeStore.getDocumentStore();
    }

    public VersionGCStats gc(long maxRevisionAge, TimeUnit unit) throws IOException {
        long maxRevisionAgeInMillis = unit.toMillis(maxRevisionAge);
        GCJob job = new GCJob(maxRevisionAgeInMillis);
        if (collector.compareAndSet(null, job)) {
            try {
                return job.run();
            } finally {
                collector.set(null);
            }
        } else {
            throw new IOException("Revision garbage collection is already running");
        }
    }

    public void cancel() {
        GCJob job = collector.get();
        if (job != null) {
            job.cancel();
        }
    }

    public void setOverflowToDiskThreshold(int overflowToDiskThreshold) {
        this.overflowToDiskThreshold = overflowToDiskThreshold;
    }

    public static class VersionGCStats {
        boolean ignoredGCDueToCheckPoint;
        boolean canceled;
        int deletedDocGCCount;
        int deletedLeafDocGCCount;
        int splitDocGCCount;
        int intermediateSplitDocGCCount;
        final Stopwatch collectDeletedDocs = Stopwatch.createUnstarted();
        final Stopwatch deleteDeletedDocs = Stopwatch.createUnstarted();
        final Stopwatch collectAndDeleteSplitDocs = Stopwatch.createUnstarted();
        final Stopwatch sortDocIds = Stopwatch.createUnstarted();

        @Override
        public String toString() {
            return "VersionGCStats{" +
                    "ignoredGCDueToCheckPoint=" + ignoredGCDueToCheckPoint +
                    ", canceled=" + canceled +
                    ", deletedDocGCCount=" + deletedDocGCCount + " (of which leaf: " + deletedLeafDocGCCount + ")" +
                    ", splitDocGCCount=" + splitDocGCCount +
                    ", intermediateSplitDocGCCount=" + intermediateSplitDocGCCount +
                    ", timeToCollectDeletedDocs=" + collectDeletedDocs +
                    ", timeToSortDocIds=" + sortDocIds +
                    ", timeTakenToDeleteDeletedDocs=" + deleteDeletedDocs +
                    ", timeTakenToCollectAndDeleteSplitDocs=" + collectAndDeleteSplitDocs +
                    '}';
        }
    }

    private enum GCPhase {
        NONE,
        COLLECTING,
        DELETING,
        SORTING,
        SPLITS_CLEANUP
    }

    /**
     * Keeps track of timers when switching GC phases.
     *
     * Could be merged with VersionGCStats, however this way the public class is kept unchanged.
     */
    private static class GCPhases {

        final VersionGCStats stats;
        final Stopwatch elapsed;
        private final List<GCPhase> phases = Lists.newArrayList();
        private final Map<GCPhase, Stopwatch> watches = Maps.newHashMap();

        GCPhases() {
            this.stats = new VersionGCStats();
            this.elapsed = Stopwatch.createStarted();
            this.watches.put(GCPhase.NONE, Stopwatch.createStarted());
            this.watches.put(GCPhase.COLLECTING, stats.collectDeletedDocs);
            this.watches.put(GCPhase.DELETING, stats.deleteDeletedDocs);
            this.watches.put(GCPhase.SORTING, stats.sortDocIds);
            this.watches.put(GCPhase.SPLITS_CLEANUP, stats.collectAndDeleteSplitDocs);
        }

        public void start(GCPhase started) {
            suspend(currentWatch());
            this.phases.add(started);
            resume(currentWatch());
        }

        public void stop(GCPhase phase) {
            if (!phases.isEmpty() && phase == phases.get(phases.size() - 1)) {
                suspend(currentWatch());
                phases.remove(phases.size() - 1);
                resume(currentWatch());
            }
        }

        public void close() {
            while (!phases.isEmpty()) {
                suspend(currentWatch());
                phases.remove(phases.size() - 1);
            }
            this.elapsed.stop();
        }

        private GCPhase current() {
            return phases.isEmpty()? GCPhase.NONE : phases.get(phases.size() - 1);
        }

        private Stopwatch currentWatch() {
            return watches.get(current());
        }

        private void resume(Stopwatch w) {
            if (!w.isRunning()) {
                w.start();
            }
        }
        private void suspend(Stopwatch w) {
            if (w.isRunning()) {
                w.stop();
            }
        }
    }

    private class GCJob {

        private final long maxRevisionAgeMillis;
        private AtomicBoolean cancel = new AtomicBoolean();

        GCJob(long maxRevisionAgeMillis) {
            this.maxRevisionAgeMillis = maxRevisionAgeMillis;
        }

        VersionGCStats run() throws IOException {
            return gc(maxRevisionAgeMillis);
        }

        void cancel() {
            log.info("Canceling revision garbage collection.");
            cancel.set(true);
        }

        private VersionGCStats gc(long maxRevisionAgeInMillis) throws IOException {
            GCPhases phases = new GCPhases();
            final long oldestRevTimeStamp = nodeStore.getClock().getTime() - maxRevisionAgeInMillis;
            final RevisionVector headRevision = nodeStore.getHeadRevision();

            log.info("Starting revision garbage collection. Revisions older than [{}] will be " +
                    "removed", Utils.timestampToString(oldestRevTimeStamp));

            //Check for any registered checkpoint which prevent the GC from running
            Revision checkpoint = nodeStore.getCheckpoints().getOldestRevisionToKeep();
            if (checkpoint != null && checkpoint.getTimestamp() < oldestRevTimeStamp) {
                log.warn("Ignoring revision garbage collection because a valid " +
                                "checkpoint [{}] was found, which is older than [{}].",
                        checkpoint.toReadableString(),
                        Utils.timestampToString(oldestRevTimeStamp)
                );
                phases.stats.ignoredGCDueToCheckPoint = true;
                return phases.stats;
            }

            collectDeletedDocuments(phases, headRevision, oldestRevTimeStamp);
            collectSplitDocuments(phases, oldestRevTimeStamp);

            phases.close();
            phases.stats.canceled = cancel.get();
            log.info("Revision garbage collection finished in {}. {}", phases.elapsed, phases.stats);
            return phases.stats;
        }

        private void collectSplitDocuments(GCPhases phases, long oldestRevTimeStamp) {
            phases.start(GCPhase.SPLITS_CLEANUP);
            versionStore.deleteSplitDocuments(GC_TYPES, oldestRevTimeStamp, phases.stats);
            phases.stop(GCPhase.SPLITS_CLEANUP);
        }

        private void collectDeletedDocuments(GCPhases phases,
                                             RevisionVector headRevision,
                                             long oldestRevTimeStamp)
                throws IOException {
            int docsTraversed = 0;
            DeletedDocsGC gc = new DeletedDocsGC(headRevision, cancel);
            try {
                phases.start(GCPhase.COLLECTING);
                Iterable<NodeDocument> itr = versionStore.getPossiblyDeletedDocs(oldestRevTimeStamp);
                try {
                    for (NodeDocument doc : itr) {
                        // continue with GC?
                        if (cancel.get()) {
                            break;
                        }
                        // Check if node is actually deleted at current revision
                        // As node is not modified since oldestRevTimeStamp then
                        // this node has not be revived again in past maxRevisionAge
                        // So deleting it is safe
                        docsTraversed++;
                        if (docsTraversed % PROGRESS_BATCH_SIZE == 0){
                            log.info("Iterated through {} documents so far. {} found to be deleted",
                                    docsTraversed, gc.getNumDocuments());
                        }
                        gc.possiblyDeleted(doc);
                        if (gc.hasLeafBatch()) {
                            phases.start(GCPhase.DELETING);
                            gc.removeLeafDocuments(phases.stats);
                            phases.stop(GCPhase.DELETING);
                        }
                    }
                } finally {
                    Utils.closeIfCloseable(itr);
                }
                phases.stop(GCPhase.COLLECTING);

                if (gc.getNumDocuments() == 0){
                    return;
                }

                phases.start(GCPhase.DELETING);
                gc.removeLeafDocuments(phases.stats);
                phases.stop(GCPhase.DELETING);

                phases.start(GCPhase.SORTING);
                gc.ensureSorted();
                phases.stop(GCPhase.SORTING);

                phases.start(GCPhase.DELETING);
                gc.removeDocuments(phases.stats);
                phases.stop(GCPhase.DELETING);
            } finally {
                gc.close();
            }
        }
    }

    /**
     * A helper class to remove document for deleted nodes.
     */
    private class DeletedDocsGC implements Closeable {

        private final RevisionVector headRevision;
        private final AtomicBoolean cancel;
        private final List<String> leafDocIdsToDelete = Lists.newArrayList();
        private final StringSort docIdsToDelete = newStringSort();
        private final StringSort prevDocIdsToDelete = newStringSort();
        private final Set<String> exclude = Sets.newHashSet();
        private boolean sorted = false;

        public DeletedDocsGC(@Nonnull RevisionVector headRevision,
                             @Nonnull AtomicBoolean cancel) {
            this.headRevision = checkNotNull(headRevision);
            this.cancel = checkNotNull(cancel);
        }

        /**
         * @return the number of documents gathers so far that have been
         * identified as garbage via {@link #possiblyDeleted(NodeDocument)}.
         * This number does not include the previous documents.
         */
        long getNumDocuments() {
            return docIdsToDelete.getSize() + leafDocIdsToDelete.size();
        }

        /**
         * Informs the GC that the given document is possibly deleted. The
         * implementation will check if the node still exists at the head
         * revision passed to the constructor to this GC. The implementation
         * will keep track of documents representing deleted nodes and remove
         * them together with associated previous document
         *
         * @param doc the candidate document.
         */
        void possiblyDeleted(NodeDocument doc)
                throws IOException {
            // construct an id that also contains
            // the _modified time of the document
            String id = doc.getId() + "/" + doc.getModified();
            // check if id is valid
            try {
                Utils.getDepthFromId(id);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid GC id {} for document {}", id, doc);
                return;
            }
            if (doc.getNodeAtRevision(nodeStore, headRevision, null) == null) {
                // Collect id of all previous docs also
                Iterator<String> previousDocs = previousDocIdsFor(doc);
                if (!doc.hasChildren() && !previousDocs.hasNext()) {
                    addLeafDocument(id);
                } else {
                    addDocument(id);
                    addPreviousDocuments(previousDocs);
                }
            }
        }

        /**
         * Removes the documents that have been identified as garbage. This
         * also includes previous documents. This method will only remove
         * documents that have not been modified since they were passed to
         * {@link #possiblyDeleted(NodeDocument)}.
         *
         * @param stats to track the number of removed documents.
         */
        void removeDocuments(VersionGCStats stats) throws IOException {
            removeLeafDocuments(stats);
            stats.deletedDocGCCount += removeDeletedDocuments(getDocIdsToDelete(), "(other)");
            // FIXME: this is incorrect because that method also removes intermediate docs
            stats.splitDocGCCount += removeDeletedPreviousDocuments();
        }

        boolean hasLeafBatch() {
            return leafDocIdsToDelete.size() >= DELETE_BATCH_SIZE;
        }

        void removeLeafDocuments(VersionGCStats stats) throws IOException {
            int removeCount = removeDeletedDocuments(getLeafDocIdsToDelete(), "(leaf)");
            stats.deletedLeafDocGCCount += removeCount;
            stats.deletedDocGCCount += removeCount;
        }

        public void close() {
            try {
                docIdsToDelete.close();
            } catch (IOException e) {
                log.warn("Failed to close docIdsToDelete", e);
            }
            try {
                prevDocIdsToDelete.close();
            } catch (IOException e) {
                log.warn("Failed to close prevDocIdsToDelete", e);
            }
        }

        //------------------------------< internal >----------------------------

        private Iterator<String> previousDocIdsFor(NodeDocument doc) {
            Map<Revision, Range> prevRanges = doc.getPreviousRanges(true);
            if (prevRanges.isEmpty()) {
                return Iterators.emptyIterator();
            } else if (all(prevRanges.values(), FIRST_LEVEL)) {
                // all previous document ids can be constructed from the
                // previous ranges map. this works for first level previous
                // documents only.
                final String path = doc.getPath();
                return Iterators.transform(prevRanges.entrySet().iterator(),
                        new Function<Map.Entry<Revision, Range>, String>() {
                    @Override
                    public String apply(Map.Entry<Revision, Range> input) {
                        int h = input.getValue().getHeight();
                        return Utils.getPreviousIdFor(path, input.getKey(), h);
                    }
                });
            } else {
                // need to fetch the previous documents to get their ids
                return Iterators.transform(doc.getAllPreviousDocs(),
                        new Function<NodeDocument, String>() {
                    @Override
                    public String apply(NodeDocument input) {
                        return input.getId();
                    }
                });
            }
        }

        private void addDocument(String id) throws IOException {
            docIdsToDelete.add(id);
        }

        private void addLeafDocument(String id) throws IOException {
            leafDocIdsToDelete.add(id);
        }

        private long getNumPreviousDocuments() {
            return prevDocIdsToDelete.getSize() - exclude.size();
        }

        private void addPreviousDocuments(Iterator<String> ids) throws IOException {
            while (ids.hasNext()) {
                prevDocIdsToDelete.add(ids.next());
            }
        }

        private Iterator<String> getDocIdsToDelete() throws IOException {
            ensureSorted();
            return docIdsToDelete.getIds();
        }

        private Iterator<String> getLeafDocIdsToDelete() throws IOException {
            return leafDocIdsToDelete.iterator();
        }

        private void concurrentModification(NodeDocument doc) {
            Iterator<NodeDocument> it = doc.getAllPreviousDocs();
            while (it.hasNext()) {
                exclude.add(it.next().getId());
            }
        }

        private Iterator<String> getPrevDocIdsToDelete() throws IOException {
            ensureSorted();
            return Iterators.filter(prevDocIdsToDelete.getIds(),
                    new Predicate<String>() {
                @Override
                public boolean apply(String input) {
                    return !exclude.contains(input);
                }
            });
        }

        private int removeDeletedDocuments(Iterator<String> docIdsToDelete, String label) throws IOException {
            log.info("Proceeding to delete [{}] documents [{}]", getNumDocuments(), label);

            Iterator<List<String>> idListItr = partition(docIdsToDelete, DELETE_BATCH_SIZE);
            int deletedCount = 0;
            int lastLoggedCount = 0;
            int recreatedCount = 0;
            while (idListItr.hasNext() && !cancel.get()) {
                Map<String, Map<Key, Condition>> deletionBatch = Maps.newLinkedHashMap();
                for (String s : idListItr.next()) {
                    int idx = s.lastIndexOf('/');
                    String id = s.substring(0, idx);
                    long modified = -1;
                    try {
                        modified = Long.parseLong(s.substring(idx + 1));
                    } catch (NumberFormatException e) {
                        log.warn("Invalid _modified {} for {}", s.substring(idx + 1), id);
                    }
                    deletionBatch.put(id, singletonMap(KEY_MODIFIED, newEqualsCondition(modified)));
                }

                if (log.isDebugEnabled()) {
                    StringBuilder sb = new StringBuilder("Performing batch deletion of documents with following ids. \n");
                    Joiner.on(LINE_SEPARATOR.value()).appendTo(sb, deletionBatch.keySet());
                    log.debug(sb.toString());
                }

                int nRemoved = ds.remove(NODES, deletionBatch);

                if (nRemoved < deletionBatch.size()) {
                    // some nodes were re-created while GC was running
                    // find the document that still exist
                    for (String id : deletionBatch.keySet()) {
                        NodeDocument d = ds.find(NODES, id);
                        if (d != null) {
                            concurrentModification(d);
                        }
                    }
                    recreatedCount += (deletionBatch.size() - nRemoved);
                }

                deletedCount += nRemoved;
                log.debug("Deleted [{}] documents so far", deletedCount);

                if (deletedCount + recreatedCount - lastLoggedCount >= PROGRESS_BATCH_SIZE){
                    lastLoggedCount = deletedCount + recreatedCount;
                    double progress = lastLoggedCount * 1.0 / getNumDocuments() * 100;
                    String msg = String.format("Deleted %d (%1.2f%%) documents so far", deletedCount, progress);
                    log.info(msg);
                }
            }
            return deletedCount;
        }

        private int removeDeletedPreviousDocuments() throws IOException {
            log.info("Proceeding to delete [{}] previous documents", getNumPreviousDocuments());

            int deletedCount = 0;
            int lastLoggedCount = 0;
            Iterator<List<String>> idListItr =
                    partition(getPrevDocIdsToDelete(), DELETE_BATCH_SIZE);
            while (idListItr.hasNext() && !cancel.get()) {
                List<String> deletionBatch = idListItr.next();
                deletedCount += deletionBatch.size();

                if (log.isDebugEnabled()) {
                    StringBuilder sb = new StringBuilder("Performing batch deletion of previous documents with following ids. \n");
                    Joiner.on(LINE_SEPARATOR.value()).appendTo(sb, deletionBatch);
                    log.debug(sb.toString());
                }

                ds.remove(NODES, deletionBatch);

                log.debug("Deleted [{}] previous documents so far", deletedCount);

                if (deletedCount - lastLoggedCount >= PROGRESS_BATCH_SIZE){
                    lastLoggedCount = deletedCount;
                    double progress = deletedCount * 1.0 / (prevDocIdsToDelete.getSize() - exclude.size()) * 100;
                    String msg = String.format("Deleted %d (%1.2f%%) previous documents so far", deletedCount, progress);
                    log.info(msg);
                }
            }
            return deletedCount;
        }

        private void ensureSorted() throws IOException {
            if (!sorted) {
                docIdsToDelete.sort();
                prevDocIdsToDelete.sort();
                sorted = true;
            }
        }
    }

    @Nonnull
    private StringSort newStringSort() {
        return new StringSort(overflowToDiskThreshold,
                NodeDocumentIdComparator.INSTANCE);
    }

    private static final Predicate<Range> FIRST_LEVEL = new Predicate<Range>() {
        @Override
        public boolean apply(@Nullable Range input) {
            return input != null && input.height == 0;
        }
    };
}
