/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.sparse.codec;

import lombok.extern.log4j.Log4j2;
import org.opensearch.neuralsearch.sparse.jni.NsparseJni;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-directory page cache evictor. One eviction thread runs per Lucene
 * directory regardless of how many concurrent merges target that directory.
 *
 * <p>Background: prior implementation spawned one evictor per writer
 * instance. With high merge concurrency (max_thread_count=16, max_merge_count=32)
 * Lucene creates many concurrent {@link SparseStoredFieldsWriter} and
 * {@link SparseDocValuesConsumer} instances per shard. Aggregate eviction
 * frequency exploded from the intended 600s to ~30-60s per shard, wiping
 * page cache that active merges were sequentially reading. The result was
 * a 14x stored-fields merge slowdown via mmap page-fault thrash.
 *
 * <p>This class refcounts attachments per directory so the eviction cadence
 * is fixed at one cycle per {@link #EVICT_INTERVAL_SECONDS}, no matter how
 * many concurrent writers attach to the same directory.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Writer calls {@link #attach(String)} when it starts a merge.</li>
 *   <li>If no evictor exists for that directory, one is created and its
 *       background thread starts. Otherwise the existing evictor's refcount
 *       is incremented and reused.</li>
 *   <li>Writer calls {@link #detach()} when its merge completes.</li>
 *   <li>When the last writer detaches, the thread is signalled to stop and
 *       the evictor is removed from the registry.</li>
 * </ol>
 *
 * <h2>File coverage</h2>
 * <p>Each cycle evicts page cache for all extensions in {@link #FILE_EXTENSIONS}
 * (the union of what either consumer needs). Evicting an extension that has no
 * matching files is a cheap no-op at the JNI layer.
 */
@Log4j2
public final class DirectoryEvictor {

    /**
     * Eviction cadence per directory. Sized so the kernel readahead window
     * for a sequential mmap read of a multi-GB segment is not torn before
     * Lucene finishes streaming through it.
     *
     * <p>At ~150 MB/s effective sequential read, 600s corresponds to ~90 GB
     * of streamed data. Larger segments still work because Lucene's stored
     * fields merge progresses past the just-evicted region within a few
     * minutes; the evictor only releases pages that have already been read.
     */
    static final int EVICT_INTERVAL_SECONDS = 600;

    /**
     * File extensions to evict each cycle. Union of what
     * {@link SparseStoredFieldsWriter} and {@link SparseDocValuesConsumer}
     * each need, since both can attach to the same directory concurrently.
     */
    private static final String[] FILE_EXTENSIONS = { ".fdt", ".fdx", ".cfs", ".dvd", ".dvdx" };

    /** Registry of directory paths to their active evictor. */
    private static final Map<String, DirectoryEvictor> REGISTRY = new HashMap<>();

    /** Single lock guarding the registry and refcount transitions. */
    private static final Object REGISTRY_LOCK = new Object();

    private final String dir;
    private int refCount;                 // guarded by REGISTRY_LOCK
    private volatile boolean running;
    private volatile Thread thread;
    private int evictCycleCount;          // diagnostic only, no synchronization needed

    private DirectoryEvictor(String dir) {
        this.dir = dir;
    }

    /**
     * Increment the refcount for {@code dir}, creating and starting the evictor
     * if this is the first attachment. Idempotent for the caller — pair every
     * attach with exactly one {@link #detach()}.
     *
     * @param dir absolute directory path string (must be stable for the lifetime of the merge)
     * @return the evictor instance to be used in the matching detach call
     */
    public static DirectoryEvictor attach(String dir) {
        synchronized (REGISTRY_LOCK) {
            DirectoryEvictor evictor = REGISTRY.get(dir);
            if (evictor == null) {
                evictor = new DirectoryEvictor(dir);
                REGISTRY.put(dir, evictor);
                evictor.startThread();
                log.info("DirectoryEvictor: started for {} (refcount=1)", dir);
            } else {
                log.info("DirectoryEvictor: reusing for {} (refcount={})", dir, evictor.refCount + 1);
            }
            evictor.refCount++;
            return evictor;
        }
    }

    /**
     * Decrement the refcount. When it reaches zero, the eviction thread is
     * signalled to stop and the registry entry is removed. Calling detach
     * more than once for a single attach is a no-op once refCount hits zero.
     */
    public void detach() {
        Thread toStop = null;
        synchronized (REGISTRY_LOCK) {
            if (refCount <= 0) {
                log.warn("DirectoryEvictor.detach() called with refCount={} for {}", refCount, dir);
                return;
            }
            refCount--;
            if (refCount == 0) {
                running = false;
                toStop = thread;
                REGISTRY.remove(dir, this);
                log.info("DirectoryEvictor: stopping for {} after {} eviction cycles (last reference released)", dir, evictCycleCount);
            } else {
                log.info("DirectoryEvictor: detached from {} (refcount now {})", dir, refCount);
            }
        }
        if (toStop != null) {
            toStop.interrupt();
            try {
                toStop.join(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void startThread() {
        running = true;
        thread = new Thread(this::run, "dir-page-evictor");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            while (running) {
                Thread.sleep(EVICT_INTERVAL_SECONDS * 1000L);
                if (!running) {
                    break;
                }
                for (String ext : FILE_EXTENSIONS) {
                    NsparseJni.evictPageCache(dir, ext);
                }
                evictCycleCount++;
                int currentRefs;
                synchronized (REGISTRY_LOCK) {
                    currentRefs = refCount;
                }
                log.info("DirectoryEvictor cycle #{}: evicted {} (active writers attached: {})", evictCycleCount, dir, currentRefs);
            }
        } catch (InterruptedException ignored) {
            // Normal shutdown path
        } catch (Exception e) {
            log.warn("DirectoryEvictor: thread error for {}", dir, e);
        }
    }

    /**
     * Test helper. Returns the number of registered evictors. Not part of the
     * public API; used only by unit tests to assert that detaches matched attaches.
     */
    static int registrySize() {
        synchronized (REGISTRY_LOCK) {
            return REGISTRY.size();
        }
    }
}
