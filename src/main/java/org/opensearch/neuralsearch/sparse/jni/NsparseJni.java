/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.sparse.jni;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

@Log4j2
public class NsparseJni {

    private static volatile boolean loaded = false;
    private static volatile boolean available = false;

    public static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            loadNativeLibrary();
            available = true;
            log.info("nsparse JNI native library loaded successfully");
        } catch (Exception e) {
            log.warn("Failed to load nsparse JNI native library: {}", e.getMessage());
        }
    }

    private static void loadNativeLibrary() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String osName;
        if (os.contains("linux")) {
            osName = "linux";
        } else if (os.contains("mac")) {
            osName = "darwin";
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }

        String archName;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archName = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archName = "aarch64";
        } else {
            throw new UnsupportedOperationException("Unsupported architecture: " + arch);
        }

        String libName = "libnsparse_jni.so";
        if (osName.equals("darwin")) {
            libName = "libnsparse_jni.dylib";
        }

        String resourcePath = "/native/" + osName + "-" + archName + "/" + libName;
        InputStream in = NsparseJni.class.getResourceAsStream(resourcePath);

        if (in == null) {
            throw new UnsatisfiedLinkError("Native library not found in JAR: " + resourcePath);
        }

        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        Path tempFile = Files.createTempFile(tempDir, "nsparse_jni", libName.substring(libName.lastIndexOf('.')));
        try {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            in.close();
            System.load(tempFile.toAbsolutePath().toString());
        } finally {
            final Path toDelete = tempFile;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(toDelete);
                } catch (IOException ignored) {}
            }));
        }
    }

    public static boolean isAvailable() {
        ensureLoaded();
        return available;
    }

    public static native long createIndex(int dimension, String description);

    public static native void addVectors(long indexPtr, int n, int[] indptr, short[] indices, float[] values);

    public static native void addVectorsWithIds(long indexPtr, int n, int[] indptr, short[] indices, float[] values, int[] ids);

    public static native void reserveIndex(long indexPtr, long numVectors, long totalNnz);

    public static native void buildIndex(long indexPtr);

    public static native void buildAndSaveIndex(long indexPtr, String path);

    public static native SearchResult search(
        long indexPtr,
        int nQueries,
        int[] indptr,
        short[] indices,
        float[] values,
        int k,
        float heapFactor,
        int cut
    );

    public static native SearchResult searchSQ(
        long indexPtr,
        int nQueries,
        int[] indptr,
        short[] indices,
        float[] values,
        int k,
        float heapFactor,
        int cut,
        float vmin,
        float vmax
    );

    public static native void saveIndex(long indexPtr, String path);

    public static native long loadIndex(String path);

    public static native void deleteIndex(long indexPtr);

    public static native int numVectors(long indexPtr);

    public static native int getDimension(long indexPtr);

    public static native void setNumThreads(int numThreads);

    public static native void evictPageCache(String dirPath, String suffix);
}
