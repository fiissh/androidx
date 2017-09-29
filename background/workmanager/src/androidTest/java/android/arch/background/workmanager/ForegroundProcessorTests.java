/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.arch.background.workmanager;

import static org.junit.Assert.assertEquals;

import android.arch.background.workmanager.foreground.ForegroundProcessor;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ForegroundProcessorTests {

    private WorkDatabase mWorkDatabase;
    private ForegroundProcessor mForegroundProcessor;

    @Before
    public void setUp() {
        LifecycleOwner alwaysActiveLifecycleOwner = new LifecycleOwner() {

            LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);

            @Override
            public Lifecycle getLifecycle() {
                mLifecycleRegistry.markState(Lifecycle.State.STARTED);
                return mLifecycleRegistry;
            }
        };

        Context appContext = InstrumentationRegistry.getTargetContext().getApplicationContext();
        mWorkDatabase = WorkDatabase.create(appContext, true);
        mForegroundProcessor =
                new ForegroundProcessor(appContext, mWorkDatabase, alwaysActiveLifecycleOwner);
    }

    @After
    public void tearDown() {
        mWorkDatabase.close();
    }

    @Test
    @SmallTest
    public void testSimpleWorker() throws InterruptedException {
        Work work = new Work.Builder(TestWorker.class).build();
        mWorkDatabase.workSpecDao().insertWorkSpec(work.getWorkSpec());
        mForegroundProcessor.process(work.getId());
        Thread.sleep(1000L);
        assertEquals(
                Work.STATUS_SUCCEEDED,
                mWorkDatabase.workSpecDao().getWorkSpecStatus(work.getId()));
    }
}
