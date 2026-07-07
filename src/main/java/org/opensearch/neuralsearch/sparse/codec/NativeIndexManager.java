/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.sparse.codec;

import lombok.extern.log4j.Log4j2;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.opensearch.neuralsearch.sparse.jni.NsparseJni;
import org.opensearch.neuralsearch.sparse.jni.SearchResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.opensearch.neuralsearch.settings.NeuralSearchSettings;

import static org.opensearch.neuralsearch.sparse.common.SparseConstants.ENGINE_CPP_NATIVE;
import static org.opensearch.neuralsearch.sparse.common.SparseConstants.ENGINE_FIELD;
import static org.opensearch.neuralsearch.sparse.common.SparseConstants.QUANTIZATION_CEILING_INGEST_FIELD;
import static org.opensearch.neuralsearch.sparse.common.SparseConstants.SUMMARY_PRUNE_RATIO_FIELD;
import static org.opensearch.neuralsearch.sparse.common.SparseConstants.Seismic.DEFAULT_QUANTIZATION_CEILING_INGEST;
import static org.opensearch.neuralsearch.sparse.common.SparseConstants.Seismic.DEFAULT_SUMMARY_PRUNE_RATIO;

@Log4j2
public class NativeIndexManager {

    private static final NativeIndexManager INSTANCE = new NativeIndexManager();
    private static final String NSPARSE_EXTENSION = ".nsparse";
    private static final long MIN_AVAILABLE_MB = 2_000L;
    private static final Semaphore BUILD_SEMAPHORE = new Semaphore(1);
    private static volatile int numBuildThreads = NeuralSearchSettings.DEFAULT_INDEX_THREAD_QTY;

    public static void setNumBuildThreads(int threads) {
        numBuildThreads = threads;
    }

    public static int getNumBuildThreads() {
        return numBuildThreads;
    }

    public static void acquireBuildPermit() {
        BUILD_SEMAPHORE.acquireUninterruptibly();
    }

    public static void releaseBuildPermit() {
        BUILD_SEMAPHORE.release();
    }

    public static long getAvailableMemoryMbStatic() {
        return getAvailableMemoryMb();
    }

    public static long getMinAvailableMb() {
        return MIN_AVAILABLE_MB;
    }

    private final ConcurrentHashMap<String, Long> loadedIndexes = new ConcurrentHashMap<>();

    public static NativeIndexManager getInstance() {
        return INSTANCE;
    }

    public static boolean isCppNativeEngine(FieldInfo fieldInfo) {
        if (fieldInfo == null) return false;
        String engine = fieldInfo.attributes().get(ENGINE_FIELD);
        return ENGINE_CPP_NATIVE.equals(engine);
    }

    public static String nativeIndexFileName(String segmentName, String fieldName) {
        return segmentName + "_" + fieldName + NSPARSE_EXTENSION;
    }

    public String buildIndexDescription(FieldInfo fieldInfo) {
        float summaryPruneRatio = DEFAULT_SUMMARY_PRUNE_RATIO;
        float quantCeilIngest = DEFAULT_QUANTIZATION_CEILING_INGEST;

        String val = fieldInfo.attributes().get(SUMMARY_PRUNE_RATIO_FIELD);
        if (val != null) summaryPruneRatio = Float.parseFloat(val);
        val = fieldInfo.attributes().get(QUANTIZATION_CEILING_INGEST_FIELD);
        if (val != null) quantCeilIngest = Float.parseFloat(val);

        String quantization = fieldInfo.attributes().get("quantization");
        if ("uint8".equals(quantization)) {
            return String.format(
                Locale.ROOT,
                "idmap,seismic_sq,quantizer=8bit|vmin=0.0|vmax=%.2f|lambda=-1|beta=-1|alpha=%.2f",
                quantCeilIngest,
                summaryPruneRatio
            );
        }
        return String.format(Locale.ROOT, "idmap,seismic,lambda=-1|beta=-1|alpha=%.2f", summaryPruneRatio);
    }

    public void buildAndSaveNativeIndex(
        SegmentInfo segmentInfo,
        FieldInfo fieldInfo,
        Directory directory,
        int docCount,
        int[] indptr,
        short[] indices,
        float[] values,
        int[] docIds
    ) throws IOException {
        long availableMb = getAvailableMemoryMb();
        if (availableMb < MIN_AVAILABLE_MB) {
            log.info(
                "Skipping native index build for segment [{}] field [{}]: only {}MB available (need {}MB)",
                segmentInfo.name,
                fieldInfo.name,
                availableMb,
                MIN_AVAILABLE_MB
            );
            return;
        }

        BUILD_SEMAPHORE.acquireUninterruptibly();
        try {
            buildAndSaveNativeIndexInternal(segmentInfo, fieldInfo, directory, docCount, indptr, indices, values, docIds);
        } finally {
            BUILD_SEMAPHORE.release();
        }
    }

    private void buildAndSaveNativeIndexInternal(
        SegmentInfo segmentInfo,
        FieldInfo fieldInfo,
        Directory directory,
        int docCount,
        int[] indptr,
        short[] indices,
        float[] values,
        int[] docIds
    ) throws IOException {
        NsparseJni.ensureLoaded();

        String description = buildIndexDescription(fieldInfo);
        int dimension = 1;
        for (short idx : indices) {
            int unsignedIdx = Short.toUnsignedInt(idx);
            if (unsignedIdx >= dimension) {
                dimension = unsignedIdx + 1;
            }
        }

        log.info(
            "Building native index for segment [{}] field [{}] with {} docs (avail={}MB), description: {}",
            segmentInfo.name,
            fieldInfo.name,
            docCount,
            getAvailableMemoryMb(),
            description
        );

        long indexPtr = NsparseJni.createIndex(dimension, description);
        try {
            NsparseJni.addVectorsWithIds(indexPtr, docCount, indptr, indices, values, docIds);
            NsparseJni.setNumThreads(numBuildThreads);
            NsparseJni.buildIndex(indexPtr);

            Path dirPath = resolveNativeDirectoryPath(directory);
            String fileName = nativeIndexFileName(segmentInfo.name, fieldInfo.name);
            Path indexPath = dirPath.resolve(fileName);
            NsparseJni.saveIndex(indexPtr, indexPath.toString());

            log.info("Native index saved: {} ({} vectors)", indexPath, NsparseJni.numVectors(indexPtr));
        } finally {
            NsparseJni.deleteIndex(indexPtr);
        }
    }

    @SuppressWarnings("removal")
    private static long getAvailableMemoryMb() {
        return AccessController.doPrivileged((PrivilegedAction<Long>) () -> {
            try (BufferedReader br = Files.newBufferedReader(Path.of("/proc/meminfo"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("MemAvailable:")) {
                        String[] parts = line.split("\\s+");
                        return Long.parseLong(parts[1]) / 1024;
                    }
                }
                log.debug("MemAvailable not found in /proc/meminfo");
            } catch (Exception e) {
                log.debug("Cannot read /proc/meminfo: {}", e.toString());
            }
            return Long.MAX_VALUE;
        });
    }

    public long loadOrGetIndex(SegmentInfo segmentInfo, FieldInfo fieldInfo, Directory directory) throws IOException {
        String key = cacheKey(segmentInfo, fieldInfo);
        Long ptr = loadedIndexes.get(key);
        if (ptr != null) return ptr;

        NsparseJni.ensureLoaded();

        Path dirPath = resolveNativeDirectoryPath(directory);
        String fileName = nativeIndexFileName(segmentInfo.name, fieldInfo.name);
        Path indexPath = dirPath.resolve(fileName);

        if (!Files.exists(indexPath)) {
            return 0;
        }

        long indexPtr = NsparseJni.loadIndex(indexPath.toString());
        Long existing = loadedIndexes.putIfAbsent(key, indexPtr);
        if (existing != null) {
            NsparseJni.deleteIndex(indexPtr);
            return existing;
        }

        log.info("Loaded native index: {} ({} vectors)", indexPath, NsparseJni.numVectors(indexPtr));
        return indexPtr;
    }

    public boolean hasNativeIndex(SegmentInfo segmentInfo, FieldInfo fieldInfo, Directory directory) {
        String key = cacheKey(segmentInfo, fieldInfo);
        if (loadedIndexes.containsKey(key)) return true;

        try {
            Path dirPath = resolveNativeDirectoryPath(directory);
            String fileName = nativeIndexFileName(segmentInfo.name, fieldInfo.name);
            return Files.exists(dirPath.resolve(fileName));
        } catch (IOException e) {
            return false;
        }
    }

    public void releaseIndex(SegmentInfo segmentInfo, FieldInfo fieldInfo) {
        String key = cacheKey(segmentInfo, fieldInfo);
        Long ptr = loadedIndexes.remove(key);
        if (ptr != null) {
            NsparseJni.deleteIndex(ptr);
            log.info("Released native index from memory: {}", key);
        }
    }

    @SuppressWarnings("removal")
    public void deleteNativeFile(SegmentInfo segmentInfo, FieldInfo fieldInfo, Directory directory) {
        try {
            Path dirPath = resolveNativeDirectoryPath(directory);
            String fileName = nativeIndexFileName(segmentInfo.name, fieldInfo.name);
            Path filePath = dirPath.resolve(fileName);
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                try {
                    if (Files.deleteIfExists(filePath)) {
                        log.info("Deleted native index file: {}", filePath);
                    }
                } catch (IOException e) {
                    log.warn("Failed to delete native index file: {}", filePath, e);
                }
                return null;
            });
        } catch (IOException e) {
            log.warn("Failed to resolve path for native file deletion: segment={}, field={}", segmentInfo.name, fieldInfo.name, e);
        }
    }

    public void releaseAndDeleteIndex(SegmentInfo segmentInfo, FieldInfo fieldInfo, Directory directory) {
        releaseIndex(segmentInfo, fieldInfo);
        deleteNativeFile(segmentInfo, fieldInfo, directory);
    }

    @SuppressWarnings("removal")
    public void deleteAllNativeFiles(Directory directory) {
        // Release all in-memory indexes that correspond to files in this directory
        releaseAllLoadedIndexes();

        try {
            Path nativeDir = resolveNativeDirectoryPath(directory);
            AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                try (var stream = Files.list(nativeDir)) {
                    stream.filter(p -> p.toString().endsWith(NSPARSE_EXTENSION)).forEach(p -> {
                        try {
                            Files.delete(p);
                            log.info("Deleted native index file: {}", p);
                        } catch (IOException e) {
                            log.warn("Failed to delete native index file: {}", p, e);
                        }
                    });
                } catch (IOException e) {
                    log.warn("Failed to list native directory: {}", nativeDir, e);
                }
                return null;
            });
        } catch (IOException e) {
            log.debug("Cannot resolve native directory for cleanup: {}", e.toString());
        }
    }

    public void releaseAllIndexes() {
        releaseAllLoadedIndexes();
    }

    private void releaseAllLoadedIndexes() {
        var entries = new java.util.ArrayList<>(loadedIndexes.entrySet());
        for (var entry : entries) {
            Long ptr = loadedIndexes.remove(entry.getKey());
            if (ptr != null) {
                NsparseJni.deleteIndex(ptr);
            }
        }
        if (!entries.isEmpty()) {
            log.info("Released {} native indexes from memory on index removal", entries.size());
        }
    }

    public int getLoadedCount() {
        return loadedIndexes.size();
    }

    public SearchResult search(long indexPtr, int[] indptr, short[] indices, float[] values, int k, float heapFactor, int cut) {
        return NsparseJni.search(indexPtr, 1, indptr, indices, values, k, heapFactor, cut);
    }

    public SearchResult searchSQ(
        long indexPtr,
        int[] indptr,
        short[] indices,
        float[] values,
        int k,
        float heapFactor,
        int cut,
        float vmin,
        float vmax
    ) {
        return NsparseJni.searchSQ(indexPtr, 1, indptr, indices, values, k, heapFactor, cut, vmin, vmax);
    }

    private String cacheKey(SegmentInfo segmentInfo, FieldInfo fieldInfo) {
        return segmentInfo.name + "/" + fieldInfo.name;
    }

    static Path resolveDirectoryPath(Directory directory) throws IOException {
        Directory unwrapped = directory;
        while (unwrapped != null) {
            if (unwrapped instanceof FSDirectory fsDir) {
                return fsDir.getDirectory();
            }
            if (unwrapped instanceof org.apache.lucene.store.FilterDirectory filterDir) {
                unwrapped = filterDir.getDelegate();
            } else {
                break;
            }
        }
        throw new IOException("Cannot resolve filesystem path from directory: " + directory.getClass().getName());
    }

    @SuppressWarnings("removal")
    static Path resolveNativeDirectoryPath(Directory directory) throws IOException {
        Path lucenePath = resolveDirectoryPath(directory);
        // lucenePath is .../nodes/0/indices/<uuid>/<shard>/index/
        // We want .../nodes/0/indices/<uuid>/<shard>/native/
        Path shardDir = lucenePath.getParent();
        if (shardDir == null) {
            throw new IOException("Cannot resolve shard directory from: " + lucenePath);
        }
        Path nativeDir = shardDir.resolve("native");
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            try {
                Files.createDirectories(nativeDir);
            } catch (IOException e) {
                log.warn("Failed to create native directory: {}", nativeDir, e);
            }
            return null;
        });
        return nativeDir;
    }
}
