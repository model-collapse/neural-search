/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.sparse.codec;

import org.opensearch.neuralsearch.sparse.AbstractSparseTestBase;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DirectoryEvictorTests extends AbstractSparseTestBase {

    /** Single attach + detach: registry grows then shrinks back to zero. */
    public void testAttachDetach_singleWriter() {
        int initial = DirectoryEvictor.registrySize();
        DirectoryEvictor evictor = DirectoryEvictor.attach("/tmp/test-dir-1");
        assertEquals(initial + 1, DirectoryEvictor.registrySize());
        evictor.detach();
        assertEquals(initial, DirectoryEvictor.registrySize());
    }

    /** Two writers on the same directory share one evictor; both must detach to release. */
    public void testAttachDetach_twoWritersSameDirectory() {
        int initial = DirectoryEvictor.registrySize();
        DirectoryEvictor a = DirectoryEvictor.attach("/tmp/test-dir-2");
        DirectoryEvictor b = DirectoryEvictor.attach("/tmp/test-dir-2");

        // Same instance returned (singleton per directory)
        assertSame("Concurrent attaches to the same directory must share an evictor", a, b);
        assertEquals("Registry has exactly one entry for the shared directory", initial + 1, DirectoryEvictor.registrySize());

        a.detach();
        assertEquals("First detach must NOT remove the entry while another writer holds it", initial + 1, DirectoryEvictor.registrySize());

        b.detach();
        assertEquals("Final detach removes the entry", initial, DirectoryEvictor.registrySize());
    }

    /** Different directories get independent evictors. */
    public void testAttachDetach_differentDirectories() {
        int initial = DirectoryEvictor.registrySize();
        DirectoryEvictor a = DirectoryEvictor.attach("/tmp/test-dir-3a");
        DirectoryEvictor b = DirectoryEvictor.attach("/tmp/test-dir-3b");

        assertNotSame("Different directories must have distinct evictors", a, b);
        assertEquals(initial + 2, DirectoryEvictor.registrySize());

        a.detach();
        b.detach();
        assertEquals(initial, DirectoryEvictor.registrySize());
    }

    /** Detaching past zero must be safe (no exception, no negative refcount). */
    public void testDetach_overdetachIsSafe() {
        DirectoryEvictor evictor = DirectoryEvictor.attach("/tmp/test-dir-4");
        evictor.detach();
        evictor.detach();  // must not throw, must not corrupt state
        // Re-attach should work cleanly.
        DirectoryEvictor again = DirectoryEvictor.attach("/tmp/test-dir-4");
        again.detach();
    }

    /**
     * Stress: simulate the eviction-merge-slowdown incident scenario.
     * 32 concurrent threads attach and detach to the same directory.
     * The registry size is 1 while any holder is alive, and 0 after all release.
     * If the implementation is per-instance (the bug), 32 evictors would coexist.
     */
    public void testStress_32ConcurrentWritersShareOneEvictor() throws InterruptedException {
        final String dir = "/tmp/test-dir-stress";
        final int writers = 32;
        final CountDownLatch attached = new CountDownLatch(writers);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch detached = new CountDownLatch(writers);
        final List<DirectoryEvictor> evictors = new ArrayList<>();
        final Object listLock = new Object();
        final AtomicInteger errors = new AtomicInteger();

        int initial = DirectoryEvictor.registrySize();

        Thread[] threads = new Thread[writers];
        for (int i = 0; i < writers; i++) {
            threads[i] = new Thread(() -> {
                try {
                    DirectoryEvictor e = DirectoryEvictor.attach(dir);
                    synchronized (listLock) {
                        evictors.add(e);
                    }
                    attached.countDown();
                    release.await();
                    e.detach();
                    detached.countDown();
                } catch (Exception ex) {
                    errors.incrementAndGet();
                }
            });
            threads[i].start();
        }

        assertTrue("All writers must attach within 5s", attached.await(5, TimeUnit.SECONDS));
        assertEquals(
            "Exactly one evictor exists for the shared directory while writers hold it",
            initial + 1,
            DirectoryEvictor.registrySize()
        );

        // All collected references must be the same singleton instance.
        synchronized (listLock) {
            DirectoryEvictor first = evictors.get(0);
            for (DirectoryEvictor e : evictors) {
                assertSame("All concurrent attaches must return the same instance", first, e);
            }
        }

        release.countDown();
        assertTrue("All writers must detach within 5s", detached.await(5, TimeUnit.SECONDS));

        for (Thread t : threads) {
            t.join(1000);
        }

        assertEquals("Registry must be empty after all writers detach", initial, DirectoryEvictor.registrySize());
        assertEquals("No errors during stress test", 0, errors.get());
    }
}
