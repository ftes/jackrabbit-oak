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
package org.apache.jackrabbit.oak.segment.standby;

import java.io.File;
import java.util.concurrent.ScheduledExecutorService;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.junit.Before;

public class ExternalSharedStoreIT extends DataStoreTestBase {

    private String commonDataStorePath;

    public ExternalSharedStoreIT() {
        this.storesCanBeEqual = true;
    }

    @Before
    public void createCommonDataStoreFolder() throws Exception {
        commonDataStorePath = folder.newFolder("data-store-common").getAbsolutePath();
    }

    @Override
    protected FileStore setupPrimary(File d, ScheduledExecutorService primaryExecutor) throws Exception {
        return setupFileDataStore(d, commonDataStorePath, primaryExecutor);
    }

    @Override
    protected FileStore setupSecondary(File d, ScheduledExecutorService secondaryExecutor) throws Exception {
        return setupFileDataStore(d, commonDataStorePath, secondaryExecutor);
    }

}