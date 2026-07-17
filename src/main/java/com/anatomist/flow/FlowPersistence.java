package com.anatomist.flow;

import com.anatomist.store.SqliteStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

/** Transactional persistence for the high-cardinality flow graph. */
public final class FlowPersistence {

    private FlowPersistence() {}

    public static void replaceAll(SqliteStore store, FlowResult result) {
        store.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DELETE FROM method_flow_summaries");
                statement.execute("DELETE FROM flow_edges");
                statement.execute("DELETE FROM flow_nodes");
            }
            insert(connection, result);
        });
    }

    public static void replaceFiles(SqliteStore store,
                                    List<String> sourceFiles,
                                    FlowResult result) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return;
        store.inTransaction(connection -> {
            String placeholders = String.join(",", Collections.nCopies(sourceFiles.size(), "?"));
            delete(connection, "DELETE FROM method_flow_summaries WHERE source_file IN ("
                    + placeholders + ")", sourceFiles);
            delete(connection, "DELETE FROM flow_edges WHERE source_file IN ("
                    + placeholders + ")", sourceFiles);
            delete(connection, "DELETE FROM flow_nodes WHERE source_file IN ("
                    + placeholders + ")", sourceFiles);
            insert(connection, result);
        });
    }

    public static void relinkInterprocedural(SqliteStore store) {
        store.inTransaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "DELETE FROM flow_edges WHERE relation IN ('CALL_ARGUMENT','CALL_RETURN')");
                statement.execute("""
                        INSERT INTO flow_edges
                          (source_node,target_node,relation,method_id,source_file,confidence,context,metadata)
                        SELECT DISTINCT argument.source_node, parameter.id, 'CALL_ARGUMENT',
                               call.method_id, call.source_file, 'INFERRED', argument.context, NULL
                        FROM flow_nodes call
                        JOIN flow_edges argument
                          ON argument.target_node=call.id AND argument.relation='ARGUMENT_FLOW'
                        JOIN flow_nodes parameter
                          ON parameter.method_id=json_extract(call.metadata,'$.callee_method')
                         AND parameter.kind='PARAMETER'
                         AND json_extract(parameter.metadata,'$.slot')=argument.context
                        WHERE call.kind IN ('CALL_RESULT','TAINT_SOURCE','TAINT_SINK','SANITIZER')
                        """);
                statement.execute("""
                        INSERT INTO flow_edges
                          (source_node,target_node,relation,method_id,source_file,confidence,context,metadata)
                        SELECT DISTINCT returned.id, call.id, 'CALL_RETURN',
                               call.method_id, call.source_file, 'INFERRED', NULL, NULL
                        FROM flow_nodes call
                        JOIN flow_nodes returned
                          ON returned.method_id=json_extract(call.metadata,'$.callee_method')
                         AND returned.kind='RETURN'
                        WHERE call.kind IN ('CALL_RESULT','TAINT_SOURCE','TAINT_SINK','SANITIZER')
                        """);
            }
        });
    }

    private static void delete(Connection connection, String sql, List<String> values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
            statement.executeUpdate();
        }
    }

    private static void insert(Connection connection, FlowResult result) throws SQLException {
        if (result == null) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO flow_nodes"
                        + "(id,method_id,kind,label,source_file,module,scope,line,column_no,metadata)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?)")) {
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
                statement.setString(10, node.metadata());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO flow_edges"
                        + "(source_node,target_node,relation,method_id,source_file,confidence,context,metadata)"
                        + " VALUES (?,?,?,?,?,?,?,?)")) {
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
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO method_flow_summaries"
                        + "(method_id,input_slot,output_slot,relation,source_file,confidence,metadata)"
                        + " VALUES (?,?,?,?,?,?,?)")) {
            for (MethodFlowSummary summary : result.summaries) {
                statement.setString(1, summary.methodId());
                statement.setString(2, summary.inputSlot());
                statement.setString(3, summary.outputSlot());
                statement.setString(4, summary.relation());
                statement.setString(5, summary.sourceFile());
                statement.setString(6, summary.confidence());
                statement.setString(7, summary.metadata());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }
}
