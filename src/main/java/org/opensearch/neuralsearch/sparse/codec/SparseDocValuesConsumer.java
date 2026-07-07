/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.sparse.codec;

import lombok.NonNull;
import lombok.extern.log4j.Log4j2;
import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.BytesRef;
import org.opensearch.neuralsearch.sparse.accessor.SparseVectorWriter;
import org.opensearch.neuralsearch.sparse.cache.CacheKey;
import org.opensearch.neuralsearch.sparse.cache.ForwardIndexCache;
import org.opensearch.neuralsearch.sparse.common.MergeStateFacade;
import org.opensearch.neuralsearch.sparse.common.PredicateUtils;
import org.opensearch.neuralsearch.sparse.data.SparseVector;
import org.opensearch.neuralsearch.sparse.jni.NsparseJni;
import org.opensearch.neuralsearch.sparse.mapper.SparseVectorField;
import org.opensearch.neuralsearch.sparse.quantization.ByteQuantizer;
import org.opensearch.neuralsearch.sparse.quantization.ByteQuantizationUtil;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Locale;

/**
 * A DocValuesConsumer that writes sparse doc values to a segment.
 */
@Log4j2
public class SparseDocValuesConsumer extends DocValuesConsumer {
    private final DocValuesConsumer delegate;
    private final SegmentWriteState state;
    private final MergeHelper mergeHelper;

    public SparseDocValuesConsumer(
        @NonNull SegmentWriteState state,
        @NonNull DocValuesConsumer delegate,
        @NonNull MergeHelper mergeHelper
    ) {
        super();
        this.delegate = delegate;
        this.state = state;
        this.mergeHelper = mergeHelper;
    }

    @Override
    public void addNumericField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        this.delegate.addNumericField(field, valuesProducer);
    }

    @Override
    public void addBinaryField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        this.delegate.addBinaryField(field, valuesProducer);
        // check field is the sparse field, otherwise return
        if (!SparseVectorField.isSparseField(field)) {
            return;
        }
        if (NativeIndexManager.isCppNativeEngine(field)) {
            buildNativeIndexIfNeeded(field, valuesProducer);
        } else {
            addSparseVectorBinary(field, valuesProducer, false);
        }
    }

    private void addSparseVectorBinary(FieldInfo field, DocValuesProducer valuesProducer, boolean isMerge) throws IOException {
        if (!PredicateUtils.shouldRunSeisPredicate.test(this.state.segmentInfo, field)) {
            return;
        }
        BinaryDocValues binaryDocValues = valuesProducer.getBinary(field);
        CacheKey key = new CacheKey(this.state.segmentInfo, field);
        int docCount = this.state.segmentInfo.maxDoc();
        SparseVectorWriter writer = ForwardIndexCache.getInstance().getOrCreate(key, docCount).getWriter();
        int docId = binaryDocValues.nextDoc();
        while (docId != DocIdSetIterator.NO_MORE_DOCS) {
            boolean written = false;
            if (isMerge) {
                SparseBinaryDocValues sparseBinaryDocValues = (SparseBinaryDocValues) binaryDocValues;
                SparseVector vector = sparseBinaryDocValues.cachedSparseVector();
                if (vector != null) {
                    writer.insert(docId, vector);
                    written = true;
                }
            }
            if (!written) {
                BytesRef bytesRef = binaryDocValues.binaryValue();
                ByteQuantizer byteQuantizer = ByteQuantizationUtil.getByteQuantizerIngest(field);
                writer.insert(docId, new SparseVector(bytesRef, byteQuantizer));
            }
            docId = binaryDocValues.nextDoc();
        }
        if (isMerge) {
            if (valuesProducer instanceof SparseDocValuesReader reader) {
                mergeHelper.clearCacheData(reader.getMergeStateFacade(), field, ForwardIndexCache.getInstance()::onIndexRemoval);
            }
        }
    }

    private static final int BATCH_DOC_COUNT = 2_000_000;
    private static final int BATCH_NNZ_CAPACITY = 300_000_000;

    private boolean buildNativeIndexIfNeeded(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        log.info(
            "buildNativeIndexIfNeeded called for field [{}], segment [{}], maxDoc={}, attributes={}",
            field.name,
            this.state.segmentInfo.name,
            this.state.segmentInfo.maxDoc(),
            field.attributes()
        );
        if (!NativeIndexManager.isCppNativeEngine(field)) {
            log.info("Skipping native build: not cpp-native engine (engine={})", field.attributes().get("engine"));
            return false;
        }
        if (!NsparseJni.isAvailable()) {
            log.warn("cpp-native engine requested for field [{}] but native library is not available", field.name);
            return false;
        }
        if (!PredicateUtils.shouldRunSeisPredicate.test(this.state.segmentInfo, field)) {
            log.info(
                "Skipping native build: below approximate_threshold (maxDoc={}, threshold={})",
                this.state.segmentInfo.maxDoc(),
                field.attributes().get("approximate_threshold")
            );
            return false;
        }

        long availableMb = NativeIndexManager.getAvailableMemoryMbStatic();
        if (availableMb < NativeIndexManager.getMinAvailableMb()) {
            String msg = String.format(
                Locale.ROOT,
                "Native index build skipped for field [%s] due to insufficient memory (%dMB available, need %dMB free). "
                    + "Free memory and retry force merge.",
                field.name,
                availableMb,
                NativeIndexManager.getMinAvailableMb()
            );
            log.error(msg);
            throw new IOException(msg);
        }

        NativeIndexManager.acquireBuildPermit();
        try {
            buildNativeIndexBatched(field, valuesProducer);
            return true;
        } catch (Exception e) {
            log.error("Failed to build native index for field [{}]", field.name, e);
            throw new IOException(
                String.format(
                    Locale.ROOT,
                    "Native index build failed for field [%s]: %s. Retry force merge after resolving the issue.",
                    field.name,
                    e.getMessage()
                ),
                e
            );
        } finally {
            NativeIndexManager.releaseBuildPermit();
        }
    }

    private void buildNativeIndexBatched(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        BinaryDocValues binaryDocValues = valuesProducer.getBinary(field);

        NsparseJni.ensureLoaded();
        String description = NativeIndexManager.getInstance().buildIndexDescription(field);

        // First pass to find dimension: scan a sample of docs
        // Instead, we'll track max dimension as we go and set it on the index after creation.
        // Actually, createIndex needs dimension upfront. Use 65536 (max for unsigned short).
        int dimension = 65536;
        long indexPtr = NsparseJni.createIndex(dimension, description);
        log.info(
            "Created native index for field [{}] segment [{}] with dimension={}, description={}",
            field.name,
            this.state.segmentInfo.name,
            dimension,
            description
        );

        try {
            int[] indptr = new int[BATCH_DOC_COUNT + 1];
            short[] batchIndices = new short[BATCH_NNZ_CAPACITY];
            float[] batchValues = new float[BATCH_NNZ_CAPACITY];
            int[] batchDocIds = new int[BATCH_DOC_COUNT];

            int batchDocCount = 0;
            int batchOffset = 0;
            int totalDocs = 0;
            long totalNnz = 0;
            int batchNum = 0;
            boolean reserved = false;

            // Resolve path once for periodic page cache eviction during CSR loading
            Path lucenePath = NativeIndexManager.resolveDirectoryPath(this.state.directory);

            int docId = binaryDocValues.nextDoc();
            while (docId != DocIdSetIterator.NO_MORE_DOCS) {
                BytesRef bytesRef = binaryDocValues.binaryValue();
                int numPairs = bytesRef.length / 8;

                // Flush batch if adding this doc would exceed array capacity
                if (batchDocCount > 0 && (batchOffset + numPairs > BATCH_NNZ_CAPACITY || batchDocCount >= BATCH_DOC_COUNT)) {
                    indptr[batchDocCount] = batchOffset;
                    // After first batch, estimate total and reserve
                    if (!reserved) {
                        long maxDoc = this.state.segmentInfo.maxDoc();
                        long avgNnz = batchOffset / batchDocCount;
                        long estimatedTotalNnz = maxDoc * avgNnz;
                        NsparseJni.reserveIndex(indexPtr, maxDoc, estimatedTotalNnz);
                        log.info("Reserved native index capacity: {} vectors, {} estimated nnz", maxDoc, estimatedTotalNnz);
                        reserved = true;
                    }
                    flushBatch(indexPtr, batchDocCount, indptr, batchIndices, batchValues, batchDocIds, batchOffset, batchNum);
                    batchNum++;
                    // Evict page cache every 2 batches to prevent RSS buildup during CSR loading
                    if (batchNum % 2 == 0) {
                        NsparseJni.evictPageCache(lucenePath.toString(), ".dvd");
                        NsparseJni.evictPageCache(lucenePath.toString(), ".dvdx");
                        NsparseJni.evictPageCache(lucenePath.toString(), ".cfs");
                    }
                    batchDocCount = 0;
                    batchOffset = 0;
                }

                indptr[batchDocCount] = batchOffset;
                batchDocIds[batchDocCount] = docId;

                try (
                    DataInputStream dis = new DataInputStream(new ByteArrayInputStream(bytesRef.bytes, bytesRef.offset, bytesRef.length))
                ) {
                    for (int i = 0; i < numPairs; i++) {
                        batchIndices[batchOffset] = (short) (dis.readInt() % 65536);
                        batchValues[batchOffset] = dis.readFloat();
                        batchOffset++;
                    }
                }

                batchDocCount++;
                totalDocs++;
                totalNnz += numPairs;
                docId = binaryDocValues.nextDoc();
            }

            // Flush remaining batch
            if (batchDocCount > 0) {
                indptr[batchDocCount] = batchOffset;
                flushBatch(indexPtr, batchDocCount, indptr, batchIndices, batchValues, batchDocIds, batchOffset, batchNum);
                batchNum++;
            }

            if (totalDocs == 0) {
                return;
            }

            log.info(
                "All {} batches added: totalDocs={}, totalNnz={}, avgNnz={}. Building index...",
                batchNum,
                totalDocs,
                totalNnz,
                totalNnz / totalDocs
            );

            NsparseJni.setNumThreads(NativeIndexManager.getNumBuildThreads());

            // Final page cache eviction before k-means build
            NsparseJni.evictPageCache(lucenePath.toString(), ".dvd");
            NsparseJni.evictPageCache(lucenePath.toString(), ".dvdx");

            Path dirPath = NativeIndexManager.resolveNativeDirectoryPath(this.state.directory);
            String fileName = NativeIndexManager.nativeIndexFileName(this.state.segmentInfo.name, field.name);
            Path indexPath = dirPath.resolve(fileName);
            // Transfer ownership to JNI — buildAndSaveIndex deletes the native index internally
            // to free RSS before segment open. Setting indexPtr=0 first ensures no double-free
            // if buildAndSaveIndex throws after deleting.
            long ptrToConsume = indexPtr;
            indexPtr = 0;
            NsparseJni.buildAndSaveIndex(ptrToConsume, indexPath.toString());

            log.info("Native index saved: {} ({} vectors, {} total nnz)", indexPath, totalDocs, totalNnz);
        } finally {
            if (indexPtr != 0) {
                NsparseJni.deleteIndex(indexPtr);
            }
        }
    }

    private void flushBatch(
        long indexPtr,
        int docCount,
        int[] indptr,
        short[] indices,
        float[] values,
        int[] docIds,
        int nnzCount,
        int batchNum
    ) {
        int[] trimmedIndptr = java.util.Arrays.copyOf(indptr, docCount + 1);
        short[] trimmedIndices = java.util.Arrays.copyOf(indices, nnzCount);
        float[] trimmedValues = java.util.Arrays.copyOf(values, nnzCount);
        int[] trimmedDocIds = java.util.Arrays.copyOf(docIds, docCount);

        log.info("Flushing batch {}: {} docs, {} nnz", batchNum, docCount, nnzCount);
        NsparseJni.addVectorsWithIds(indexPtr, docCount, trimmedIndptr, trimmedIndices, trimmedValues, trimmedDocIds);
    }

    @Override
    public void addSortedField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        this.delegate.addSortedField(field, valuesProducer);
    }

    @Override
    public void addSortedNumericField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        this.delegate.addSortedNumericField(field, valuesProducer);
    }

    @Override
    public void addSortedSetField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
        this.delegate.addSortedSetField(field, valuesProducer);
    }

    @Override
    public void close() throws IOException {
        this.delegate.close();
    }

    @Override
    public void merge(MergeState mergeState) throws IOException {
        // Attach to the per-directory evictor (shared with all concurrent
        // SparseStoredFieldsWriter and SparseDocValuesConsumer merges in the
        // same shard). One eviction cycle per directory per
        // DirectoryEvictor.EVICT_INTERVAL_SECONDS, regardless of merge
        // concurrency. See DirectoryEvictor for the rationale.
        //
        // CRITICAL: the evictor must remain attached for the ENTIRE merge,
        // including buildNativeIndexBatched (CSR construction + SEISMIC native
        // build). That phase has the highest RSS pressure. Detaching after
        // delegate.merge() (the prior bug, see L23) lets page cache accumulate
        // unbounded during the build phase, pushing peak RSS from ~60 GB to
        // ~80 GB on 138M-doc / 3-shard workloads.
        DirectoryEvictor evictor = attachDirectoryEvictor();

        try {
            this.delegate.merge(mergeState);

            // Final eviction after the Lucene merge phase to clear page cache
            // before CSR construction begins
            evictSourceSegmentPageCache();

            assert mergeState != null;
            MergeStateFacade mergeStateFacade = mergeHelper.convertToMergeStateFacade(mergeState);
            FieldInfos mergeFieldInfos = mergeStateFacade.getMergeFieldInfos();
            if (mergeFieldInfos == null) {
                log.info("merge(): mergeFieldInfos is null, skipping");
                return;
            }
            log.info("merge(): processing {} fields for segment [{}]", mergeFieldInfos.size(), this.state.segmentInfo.name);
            for (FieldInfo fieldInfo : mergeFieldInfos) {
                DocValuesType type = fieldInfo.getDocValuesType();
                if (type == DocValuesType.BINARY && SparseVectorField.isSparseField(fieldInfo)) {
                    log.info(
                        "merge(): field [{}] is sparse binary, engine attr=[{}]",
                        fieldInfo.name,
                        fieldInfo.attributes().get("engine")
                    );
                    DocValuesProducer reader = mergeHelper.newSparseDocValuesReader(mergeStateFacade);
                    if (NativeIndexManager.isCppNativeEngine(fieldInfo)) {
                        boolean built = buildNativeIndexIfNeeded(fieldInfo, reader);
                        if (built) {
                            cleanupSourceSegmentNativeFiles(fieldInfo, mergeState);
                        } else if (PredicateUtils.shouldRunSeisPredicate.test(this.state.segmentInfo, fieldInfo)) {
                            // Native build failed for a segment that should have an index — fall back to java forward index
                            log.warn(
                                "Native build failed for field [{}] segment [{}], building java forward index as fallback",
                                fieldInfo.name,
                                this.state.segmentInfo.name
                            );
                            DocValuesProducer fallbackReader = mergeHelper.newSparseDocValuesReader(mergeStateFacade);
                            addSparseVectorBinary(fieldInfo, fallbackReader, true);
                        }
                    } else {
                        addSparseVectorBinary(fieldInfo, reader, true);
                    }
                }
            }
        } catch (IOException e) {
            log.error("Merge sparse doc values error", e);
            throw e;
        } catch (Exception e) {
            log.error("Merge sparse doc values error (non-IOException)", e);
            throw new IOException("Unexpected error during sparse doc values merge", e);
        } finally {
            // Detach the evictor LAST — only after CSR construction and
            // native build complete. See L23.
            if (evictor != null) {
                evictor.detach();
            }
        }
    }

    private void evictSourceSegmentPageCache() {
        try {
            Path lucenePath = NativeIndexManager.resolveDirectoryPath(this.state.directory);
            NsparseJni.evictPageCache(lucenePath.toString(), ".dvd");
            NsparseJni.evictPageCache(lucenePath.toString(), ".dvdx");
            NsparseJni.evictPageCache(lucenePath.toString(), ".fdt");
            NsparseJni.evictPageCache(lucenePath.toString(), ".fdx");
            NsparseJni.evictPageCache(lucenePath.toString(), ".cfs");
            log.info("Evicted source segment page cache after Lucene merge for segment [{}]", this.state.segmentInfo.name);
        } catch (Exception e) {
            log.warn("Failed to evict page cache after Lucene merge", e);
        }
    }

    /**
     * Attach to the shared per-directory evictor. Returns null if directory
     * resolution fails; eviction is best-effort and a missing evictor must
     * not abort the merge.
     */
    private DirectoryEvictor attachDirectoryEvictor() {
        try {
            Path lucenePath = NativeIndexManager.resolveDirectoryPath(this.state.directory);
            return DirectoryEvictor.attach(lucenePath.toString());
        } catch (Exception e) {
            log.warn(
                "Could not attach DirectoryEvictor for segment [{}]; eviction disabled for this merge",
                this.state.segmentInfo.name,
                e
            );
            return null;
        }
    }

    @SuppressWarnings("removal")
    private void cleanupSourceSegmentNativeFiles(FieldInfo fieldInfo, MergeState mergeState) {
        try {
            Path nativeDir = NativeIndexManager.resolveNativeDirectoryPath(this.state.directory);
            String newFileName = NativeIndexManager.nativeIndexFileName(this.state.segmentInfo.name, fieldInfo.name);
            Path newFilePath = nativeDir.resolve(newFileName);

            if (!Files.exists(newFilePath)) {
                log.warn("New native index file does not exist, skipping source cleanup: {}", newFilePath);
                return;
            }

            for (DocValuesProducer producer : mergeState.docValuesProducers) {
                if (producer instanceof SparseDocValuesProducer sparseProducer) {
                    String sourceSegmentName = sparseProducer.getState().segmentInfo.name;
                    String sourceFileName = NativeIndexManager.nativeIndexFileName(sourceSegmentName, fieldInfo.name);
                    Path sourceFilePath = nativeDir.resolve(sourceFileName);
                    NativeIndexManager.getInstance().releaseIndex(sparseProducer.getState().segmentInfo, fieldInfo);
                    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
                        try {
                            if (Files.deleteIfExists(sourceFilePath)) {
                                log.info("Cleaned up source segment native index after merge: {}", sourceFileName);
                            }
                        } catch (IOException e) {
                            log.warn("Failed to delete source native index file: {}", sourceFilePath, e);
                        }
                        return null;
                    });
                }
            }
        } catch (IOException e) {
            log.warn("Failed to resolve native directory for merge cleanup", e);
        }
    }
}
