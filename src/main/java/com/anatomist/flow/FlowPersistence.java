package com.anatomist.flow;

import com.anatomist.core.IndexTimings;
import com.anatomist.store.SqliteStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

/** Transactional persistence for the high-cardinality flow graph. */
public final class FlowPersistence {

    static final int BATCH_SIZE = 10_000;

    private FlowPersistence() {}

    public static Stats replaceAll(SqliteStore store, FlowResult result, IndexTimings timings) {
        store.inTransaction(connection -> {
            long started = start();
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM method_flow_coverage");
                statement.execute("DELETE FROM method_flow_summaries");
                statement.execute("DELETE FROM flow_edges");
                statement.execute("DELETE FROM flow_nodes");
            }
            stop(timings, "flow_delete", started);
            insert(connection, result, timings);
            relinkInterprocedural(connection, timings);
        });
        return stats(store);
    }

    public static Stats replaceFiles(SqliteStore store,
                                     List<String> sourceFiles,
                                     FlowResult result,
                                     IndexTimings timings) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return stats(store);
        store.inTransaction(connection -> {
            String placeholders = String.join(",", Collections.nCopies(sourceFiles.size(), "?"));
            long started = start();
            delete(connection, "DELETE FROM method_flow_coverage WHERE source_file IN ("
                    + placeholders + ")", sourceFiles);
            delete(connection, "DELETE FROM method_flow_summaries WHERE source_file IN ("
                    + placeholders + ")", sourceFiles);
            delete(connection, "DELETE FROM flow_edges WHERE source_file IN ("
                    + placeholders + ")", sourceFiles);
            delete(connection, "DELETE FROM flow_nodes WHERE source_file IN ("
                    + placeholders + ")", sourceFiles);
            stop(timings, "flow_delete", started);
            insert(connection, result, timings);
            relinkInterprocedural(connection, timings);
        });
        return stats(store);
    }

    public static void relinkInterprocedural(Connection connection, IndexTimings timings)
            throws SQLException {
        long started = start();
        try (Statement statement = connection.createStatement()) {
            rebindCallees(statement);
            statement.execute(
                    "DELETE FROM flow_edges WHERE relation IN ('CALL_ARGUMENT','CALL_RETURN')"
                            + " OR (relation='EXCEPTION_FLOW' AND context='callee')");
            statement.execute("""
                    INSERT INTO flow_edges
                      (source_node,target_node,relation,method_id,source_file,confidence,context,metadata)
                    SELECT DISTINCT argument.source_node, parameter.id, 'CALL_ARGUMENT',
                           call.method_id, call.source_file, 'INFERRED', argument.context, NULL
                    FROM flow_nodes call
                    JOIN flow_edges argument
                      ON argument.target_node=call.id AND argument.relation='ARGUMENT_FLOW'
                    JOIN flow_nodes parameter
                      ON parameter.method_id=call.callee_method
                     AND parameter.kind='PARAMETER'
                     AND parameter.slot=argument.context
                    WHERE call.kind IN ('CALL_RESULT','TAINT_SOURCE','TAINT_SINK','SANITIZER')
                      AND call.callee_method IS NOT NULL
                    ORDER BY argument.source_node,parameter.id,argument.context
                    """);
            statement.execute("""
                    INSERT INTO flow_edges
                      (source_node,target_node,relation,method_id,source_file,confidence,context,metadata)
                    SELECT DISTINCT returned.id, call.id, 'CALL_RETURN',
                           call.method_id, call.source_file, 'INFERRED', NULL, NULL
                    FROM flow_nodes call
                    JOIN flow_nodes returned
                      ON returned.method_id=call.callee_method
                     AND returned.kind='RETURN'
                    WHERE call.kind IN ('CALL_RESULT','TAINT_SOURCE','TAINT_SINK','SANITIZER')
                      AND call.callee_method IS NOT NULL
                    ORDER BY returned.id,call.id
                    """);
            statement.execute("""
                    INSERT INTO flow_edges
                      (source_node,target_node,relation,method_id,source_file,confidence,context,metadata)
                    SELECT DISTINCT thrown.id, call.id, 'EXCEPTION_FLOW',
                           call.method_id, call.source_file, 'POSSIBLE', 'callee', NULL
                    FROM flow_nodes call
                    JOIN flow_nodes thrown
                      ON thrown.method_id=call.callee_method
                     AND thrown.kind IN ('THROW','EXCEPTION')
                    WHERE call.kind IN ('CALL_RESULT','TAINT_SOURCE','TAINT_SINK','SANITIZER')
                      AND call.callee_method IS NOT NULL
                    ORDER BY thrown.id,call.id
                    """);
        }
        stop(timings, "flow_relink", started);
    }

    /**
     * Flow extraction and structural CALLS extraction use independent SymbolSolver
     * passes. When the flow pass falls back to a lexical callee, reuse one exact
     * structural call at the same source method/line/name/arity. Ambiguous matches
     * are deliberately left unresolved.
     */
    private static void rebindCallees(Statement statement) throws SQLException {
        statement.execute("DROP TABLE IF EXISTS temp.flow_callee_rebind");
        statement.execute("""
                CREATE TEMP TABLE flow_callee_rebind(
                    flow_node_id TEXT PRIMARY KEY,
                    target_method_id TEXT NOT NULL
                )
                """);
        statement.executeUpdate("""
                INSERT INTO flow_callee_rebind(flow_node_id,target_method_id)
                SELECT call.id,MIN(e.target_id)
                FROM flow_nodes call
                JOIN edges e
                  ON e.source_id=call.method_id
                 AND e.relation='CALLS'
                 AND e.is_external=0
                 AND e.source_file=call.source_file
                 AND e.source_location='L' || call.line
                JOIN nodes target
                  ON target.id=e.target_id
                 AND target.label=call.label
                 AND json_array_length(target.metadata,'$.parameters')
                     = CAST(json_extract(call.metadata,'$.argument_count') AS INTEGER)
                WHERE call.kind IN ('CALL_RESULT','TAINT_SOURCE','TAINT_SINK','SANITIZER')
                GROUP BY call.id
                HAVING COUNT(DISTINCT e.target_id)=1
                ORDER BY call.id
                """);
        statement.executeUpdate("""
                UPDATE flow_nodes
                SET callee_method=(
                        SELECT target_method_id FROM flow_callee_rebind
                        WHERE flow_node_id=flow_nodes.id),
                    metadata=json_set(COALESCE(metadata,'{}'),
                        '$.callee_method',(
                            SELECT target_method_id FROM flow_callee_rebind
                            WHERE flow_node_id=flow_nodes.id),
                        '$.resolution','STRUCTURAL_REBIND')
                WHERE id IN (SELECT flow_node_id FROM flow_callee_rebind)
                """);
        statement.execute("DROP TABLE temp.flow_callee_rebind");
    }

    private static void delete(Connection connection, String sql, List<String> values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
            statement.executeUpdate();
        }
    }

    private static void insert(Connection connection, FlowResult result, IndexTimings timings)
            throws SQLException {
        if (result == null) return;
        long started = start();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO flow_nodes"
                        + "(id,method_id,kind,label,source_file,module,scope,line,column_no,"
                        + "callee_method,slot,metadata) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            int count = 0;
            for (FlowNode node : result.nodes) {
                statement.setString(1, node.id());
                statement.setString(2, node.methodId());
                statement.setString(3, node.kind());
                statement.setString(4, node.label());
                statement.setString(5, node.sourceFile());
                statement.setString(6, node.module());
                statement.setString(7, node.scope());
                statement.setInt(8, node.line());
                statement.setInt(9, node.column());
                statement.setString(10, node.calleeMethod());
                statement.setString(11, node.slot());
                statement.setString(12, node.metadata());
                statement.addBatch();
                if (++count % BATCH_SIZE == 0) statement.executeBatch();
            }
            if (count % BATCH_SIZE != 0) statement.executeBatch();
        }
        stop(timings, "flow_nodes_insert", started);

        started = start();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO flow_edges"
                        + "(source_node,target_node,relation,method_id,source_file,confidence,context,metadata)"
                        + " VALUES (?,?,?,?,?,?,?,?)")) {
            int count = 0;
            for (FlowEdge edge : result.edges) {
                statement.setString(1, edge.sourceNode());
                statement.setString(2, edge.targetNode());
                statement.setString(3, edge.relation());
                statement.setString(4, edge.methodId());
                statement.setString(5, edge.sourceFile());
                statement.setString(6, edge.confidence());
                statement.setString(7, edge.context());
                statement.setString(8, edge.metadata());
                statement.addBatch();
                if (++count % BATCH_SIZE == 0) statement.executeBatch();
            }
            if (count % BATCH_SIZE != 0) statement.executeBatch();
        }
        stop(timings, "flow_edges_insert", started);

        started = start();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO method_flow_summaries"
                        + "(method_id,input_slot,output_slot,relation,source_file,confidence,metadata)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            int count = 0;
            for (MethodFlowSummary summary : result.summaries) {
                statement.setString(1, summary.methodId());
                statement.setString(2, summary.inputSlot());
                statement.setString(3, summary.outputSlot());
                statement.setString(4, summary.relation());
                statement.setString(5, summary.sourceFile());
                statement.setString(6, summary.confidence());
                statement.setString(7, summary.metadata());
                statement.addBatch();
                if (++count % BATCH_SIZE == 0) statement.executeBatch();
            }
            if (count % BATCH_SIZE != 0) statement.executeBatch();
        }
        stop(timings, "flow_summaries_insert", started);

        started = start();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO method_flow_coverage"
                        + "(method_id,source_file,detail_level) VALUES (?,?,?)")) {
            int count = 0;
            for (MethodFlowCoverage coverage : result.coverage) {
                statement.setString(1, coverage.methodId());
                statement.setString(2, coverage.sourceFile());
                statement.setString(3, coverage.detailLevel());
                statement.addBatch();
                if (++count % BATCH_SIZE == 0) statement.executeBatch();
            }
            if (count % BATCH_SIZE != 0) statement.executeBatch();
        }
        stop(timings, "flow_coverage_insert", started);
    }

    public static Stats stats(SqliteStore store) {
        try {
            Connection connection = store.connection();
            return new Stats(count(connection, "flow_nodes"),
                    count(connection, "flow_edges"),
                    count(connection, "method_flow_summaries"),
                    count(connection, "method_flow_coverage", "detail_level='DETAIL'"),
                    count(connection, "method_flow_coverage", "detail_level='SUMMARY'"));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count persisted flow facts", e);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        return count(connection, table, null);
    }

    private static int count(Connection connection, String table, String predicate)
            throws SQLException {
        String sql = "SELECT count(*) FROM " + table
                + (predicate == null ? "" : " WHERE " + predicate);
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static long start() {
        return System.nanoTime();
    }

    private static void stop(IndexTimings timings, String phase, long started) {
        if (timings != null) timings.addNanos(phase, System.nanoTime() - started);
    }

    public record Stats(int nodes, int edges, int summaries,
                        int detailedMethods, int summaryOnlyMethods) {}
}
