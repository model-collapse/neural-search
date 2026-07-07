/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.neuralsearch.sparse.codec;

import lombok.extern.log4j.Log4j2;
import org.apache.lucene.codecs.StoredFieldsWriter;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.StoredFieldDataInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

@Log4j2
public class SparseStoredFieldsWriter extends StoredFieldsWriter {

    private final StoredFieldsWriter delegate;
    private final Directory directory;

    /**
     * Per-directory eviction is shared with all other writers attached to the
     * same directory (see {@link DirectoryEvictor}). This field is non-null
     * between {@link #merge(MergeState)} and {@link #close()}.
     */
    private DirectoryEvictor evictor;

    public SparseStoredFieldsWriter(StoredFieldsWriter delegate, Directory directory) {
        this.delegate = delegate;
        this.directory = directory;
    }

    @Override
    public void startDocument() throws IOException {
        delegate.startDocument();
    }

    @Override
    public void finishDocument() throws IOException {
        delegate.finishDocument();
    }

    @Override
    public void writeField(FieldInfo info, int value) throws IOException {
        delegate.writeField(info, value);
    }

    @Override
    public void writeField(FieldInfo info, long value) throws IOException {
        delegate.writeField(info, value);
    }

    @Override
    public void writeField(FieldInfo info, float value) throws IOException {
        delegate.writeField(info, value);
    }

    @Override
    public void writeField(FieldInfo info, double value) throws IOException {
        delegate.writeField(info, value);
    }

    @Override
    public void writeField(FieldInfo info, StoredFieldDataInput value) throws IOException {
        delegate.writeField(info, value);
    }

    @Override
    public void writeField(FieldInfo info, BytesRef value) throws IOException {
        delegate.writeField(info, value);
    }

    @Override
    public void writeField(FieldInfo info, String value) throws IOException {
        delegate.writeField(info, value);
    }

    @Override
    public void finish(int numDocs) throws IOException {
        delegate.finish(numDocs);
    }

    @Override
    public int merge(MergeState mergeState) throws IOException {
        attachEvictor();
        try {
            return delegate.merge(mergeState);
        } catch (Exception e) {
            // On exception we still need to detach so the directory's evictor
            // refcount stays consistent; close() may not be called on the
            // partially-constructed writer.
            detachEvictor();
            throw e;
        }
    }

    private void attachEvictor() {
        if (evictor != null) {
            return;
        }
        try {
            Path dirPath = NativeIndexManager.resolveDirectoryPath(this.directory);
            evictor = DirectoryEvictor.attach(dirPath.toString());
        } catch (Exception e) {
            log.warn("Could not attach DirectoryEvictor; eviction disabled for this writer", e);
        }
    }

    private void detachEvictor() {
        if (evictor != null) {
            evictor.detach();
            evictor = null;
        }
    }

    @Override
    public long ramBytesUsed() {
        return delegate.ramBytesUsed();
    }

    @Override
    public Collection<org.apache.lucene.util.Accountable> getChildResources() {
        return delegate.getChildResources();
    }

    @Override
    public void close() throws IOException {
        try {
            detachEvictor();
        } finally {
            delegate.close();
        }
    }
}
