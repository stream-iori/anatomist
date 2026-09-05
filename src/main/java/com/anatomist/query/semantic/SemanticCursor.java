package com.anatomist.query.semantic;

/** Pull cursor with explicit ownership of database resources. */
public interface SemanticCursor<T> extends AutoCloseable {
    boolean hasNext();
    T next();
    @Override void close();
}
