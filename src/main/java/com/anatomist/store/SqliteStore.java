package com.anatomist.store;

import com.anatomist.model.ExtractionResult;

import java.nio.file.Path;

public class SqliteStore implements AutoCloseable {

    private final Path dbPath;

    public SqliteStore(Path dbPath) {
        this.dbPath = dbPath;
    }

    public void initSchema() {
        throw new UnsupportedOperationException("not implemented");
    }

    public void write(ExtractionResult result) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public void close() {
    }
}
