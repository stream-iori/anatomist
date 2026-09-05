package com.anatomist.cli;

import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.SemanticCapabilityRegistry;
import com.anatomist.query.semantic.SemanticIdentity;

import java.nio.file.Path;

/** One read lock, SQLite connection, and semantic identity for a query pipeline. */
final class SemanticExecutionContext implements AutoCloseable {
    private final QueryService query;
    private final SemanticIdentity identity;
    private final SemanticCapabilityRegistry capabilities;

    private SemanticExecutionContext(QueryService query, SemanticIdentity identity,
                                     SemanticCapabilityRegistry capabilities) {
        this.query = query;
        this.identity = identity;
        this.capabilities = capabilities;
    }

    static SemanticExecutionContext open(Path db, String module, String scope) {
        QueryService query = new QueryService(db);
        try {
            query.selectNodes(module, scope);
            SemanticIdentity identity = SemanticIdentity.read(query.connection());
            return new SemanticExecutionContext(query, identity,
                    new SemanticCapabilityRegistry(query.connection()));
        } catch (RuntimeException failure) {
            query.close();
            throw failure;
        }
    }

    QueryService query() { return query; }
    SemanticIdentity identity() { return identity; }
    SemanticCapabilityRegistry capabilities() { return capabilities; }

    @Override public void close() { query.close(); }
}
