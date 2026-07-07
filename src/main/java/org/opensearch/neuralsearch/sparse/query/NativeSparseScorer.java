/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.sparse.query;

import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.opensearch.neuralsearch.sparse.codec.NativeIndexManager;
import org.opensearch.neuralsearch.sparse.jni.SearchResult;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

public class NativeSparseScorer extends Scorer {

    private final DocIdSetIterator iterator;
    private final float boost;

    public NativeSparseScorer(
        long nativeIndexPtr,
        SparseQueryContext queryContext,
        Map<String, Float> queryTokens,
        float heapFactor,
        boolean isSQ,
        float vmin,
        float vmax,
        Bits liveDocs,
        BitSet filterBitSet,
        float boost
    ) {
        this.boost = boost;
        // Build CSR query vector
        int nnz = queryTokens.size();
        int[] indptr = new int[] { 0, nnz };
        short[] indices = new short[nnz];
        float[] values = new float[nnz];

        int i = 0;
        for (Map.Entry<String, Float> entry : queryTokens.entrySet()) {
            int token = Integer.parseInt(entry.getKey());
            indices[i] = (short) (token % 65536);
            values[i] = entry.getValue();
            i++;
        }

        int k = queryContext.getK();
        int cut = queryContext.getTokens().size();

        SearchResult result;
        if (isSQ) {
            result = NativeIndexManager.getInstance().searchSQ(nativeIndexPtr, indptr, indices, values, k, heapFactor, cut, vmin, vmax);
        } else {
            result = NativeIndexManager.getInstance().search(nativeIndexPtr, indptr, indices, values, k, heapFactor, cut);
        }

        // Build sorted result pairs (docId -> score), filtering deleted and non-matching docs
        int[] docIds = new int[k];
        float[] scores = new float[k];
        int validCount = 0;
        for (int j = 0; j < k; j++) {
            int docId = result.getLabels()[j];
            if (docId < 0) {
                continue;
            }
            if (liveDocs != null && liveDocs.get(docId) == false) {
                continue;
            }
            if (filterBitSet != null && filterBitSet.get(docId) == false) {
                continue;
            }
            docIds[validCount] = docId;
            scores[validCount] = result.getDistances()[j];
            validCount++;
        }

        // Sort by docId for Lucene's expected ordering
        int[] sortedDocIds = Arrays.copyOf(docIds, validCount);
        float[] sortedScores = Arrays.copyOf(scores, validCount);
        sortByDocId(sortedDocIds, sortedScores);

        this.iterator = new NativeResultIterator(sortedDocIds, sortedScores, validCount);
    }

    private void sortByDocId(int[] docIds, float[] scores) {
        Integer[] idx = new Integer[docIds.length];
        for (int i = 0; i < idx.length; i++)
            idx[i] = i;
        Arrays.sort(idx, (a, b) -> Integer.compare(docIds[a], docIds[b]));
        int[] tmpDocIds = docIds.clone();
        float[] tmpScores = scores.clone();
        for (int i = 0; i < idx.length; i++) {
            docIds[i] = tmpDocIds[idx[i]];
            scores[i] = tmpScores[idx[i]];
        }
    }

    @Override
    public int docID() {
        return iterator.docID();
    }

    @Override
    public DocIdSetIterator iterator() {
        return iterator;
    }

    @Override
    public float getMaxScore(int upTo) throws IOException {
        return Float.MAX_VALUE;
    }

    @Override
    public float score() throws IOException {
        return ((NativeResultIterator) iterator).currentScore() * boost;
    }

    private static class NativeResultIterator extends DocIdSetIterator {
        private final int[] docIds;
        private final float[] scores;
        private final int count;
        private int pos = -1;

        NativeResultIterator(int[] docIds, float[] scores, int count) {
            this.docIds = docIds;
            this.scores = scores;
            this.count = count;
        }

        float currentScore() {
            if (pos < 0 || pos >= count) return 0f;
            return scores[pos];
        }

        @Override
        public int docID() {
            if (pos < 0) return -1;
            if (pos >= count) return NO_MORE_DOCS;
            return docIds[pos];
        }

        @Override
        public int nextDoc() {
            pos++;
            return docID();
        }

        @Override
        public int advance(int target) {
            while (pos < count - 1) {
                pos++;
                if (docIds[pos] >= target) return docIds[pos];
            }
            pos = count;
            return NO_MORE_DOCS;
        }

        @Override
        public long cost() {
            return count;
        }
    }
}
