package com.anatomist.store;

import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.GraphConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Derives DI-informed edges from SQLite without materializing the whole edge table. */
final class DatabaseWiringResolver {

    private static final int WRITE_BATCH_SIZE = 1_000;

    private DatabaseWiringResolver() {}

    static int rebuild(Connection connection) throws SQLException {
        deleteGenerated(connection);

        Map<String, List<String>> implementations = relationMap(connection,
                GraphConstants.Relation.IMPLEMENTS);
        Map<String, List<String>> overrides = relationMap(connection,
                GraphConstants.Relation.OVERRIDES);
        List<Edge> injections = relationEdges(connection, GraphConstants.Relation.INJECTS);
        Set<String> injectedPairs = new HashSet<>();
        for (Edge injection : injections) {
            injectedPairs.add(injection.sourceId + "\u0000" + injection.targetId);
        }

        int written = 0;
        List<Edge> pending = new ArrayList<>(WRITE_BATCH_SIZE);
        for (Edge injection : injections) {
            List<String> candidates = distinct(implementations.get(injection.targetId));
            if (candidates.isEmpty()) continue;
            String confidence = candidates.size() == 1
                    ? GraphConstants.Confidence.INFERRED : GraphConstants.Confidence.AMBIGUOUS;
            String metadata = metadata(GraphConstants.MetadataVia.INJECTION,
                    injection.targetId, candidates);
            for (String candidate : candidates) {
                Edge edge = new Edge();
                edge.sourceId = injection.sourceId;
                edge.targetId = candidate;
                edge.relation = GraphConstants.Relation.WIRES;
                edge.confidence = confidence;
                edge.sourceFile = injection.sourceFile;
                edge.sourceLocation = injection.sourceLocation;
                edge.metadata = metadata;
                pending.add(edge);
                if (pending.size() >= WRITE_BATCH_SIZE) {
                    written += insertNew(connection, pending);
                    pending.clear();
                }
            }
        }

        String callSql = "SELECT source_id,target_id,call_kind,context,source_file,source_location "
                + "FROM edges WHERE relation=? AND is_external=0 AND target_id IS NOT NULL ORDER BY id";
        try (PreparedStatement statement = connection.prepareStatement(callSql)) {
            statement.setString(1, GraphConstants.Relation.CALLS);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String sourceId = rows.getString(1);
                    String targetId = rows.getString(2);
                    String callerType = ownerTypeOfMethod(sourceId);
                    String calleeType = ownerTypeOfMethod(targetId);
                    if (callerType == null || calleeType == null
                            || !injectedPairs.contains(callerType + "\u0000" + calleeType)) {
                        continue;
                    }
                    List<String> candidates = distinct(overrides.get(targetId));
                    if (candidates.isEmpty()) continue;
                    String confidence = candidates.size() == 1
                            ? GraphConstants.Confidence.INFERRED : GraphConstants.Confidence.AMBIGUOUS;
                    String metadata = metadata(GraphConstants.MetadataVia.INJECTED_CALL,
                            calleeType, targetId, candidates);
                    for (String candidate : candidates) {
                        Edge edge = new Edge();
                        edge.sourceId = sourceId;
                        edge.targetId = candidate;
                        edge.relation = GraphConstants.Relation.CALLS;
                        edge.callKind = rows.getString(3);
                        edge.context = rows.getString(4);
                        edge.confidence = confidence;
                        edge.sourceFile = rows.getString(5);
                        edge.sourceLocation = rows.getString(6);
                        edge.metadata = metadata;
                        pending.add(edge);
                        if (pending.size() >= WRITE_BATCH_SIZE) {
                            written += insertNew(connection, pending);
                            pending.clear();
                        }
                    }
                }
            }
        }
        written += insertNew(connection, pending);
        return written;
    }

    private static Map<String, List<String>> relationMap(Connection connection, String relation)
            throws SQLException {
        Map<String, List<String>> out = new HashMap<>();
        String sql = "SELECT source_id,target_id FROM edges "
                + "WHERE relation=? AND is_external=0 AND source_id IS NOT NULL "
                + "AND target_id IS NOT NULL ORDER BY id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, relation);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.computeIfAbsent(rows.getString(2), ignored -> new ArrayList<>())
                            .add(rows.getString(1));
                }
            }
        }
        return out;
    }

    private static List<Edge> relationEdges(Connection connection, String relation) throws SQLException {
        List<Edge> out = new ArrayList<>();
        String sql = "SELECT source_id,target_id,source_file,source_location FROM edges "
                + "WHERE relation=? AND is_external=0 AND source_id IS NOT NULL "
                + "AND target_id IS NOT NULL ORDER BY id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, relation);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Edge edge = new Edge();
                    edge.sourceId = rows.getString(1);
                    edge.targetId = rows.getString(2);
                    edge.sourceFile = rows.getString(3);
                    edge.sourceLocation = rows.getString(4);
                    out.add(edge);
                }
            }
        }
        return out;
    }

    private static int insertNew(Connection connection, List<Edge> edges) throws SQLException {
        if (edges.isEmpty()) return 0;
        String existsSql = "SELECT 1 FROM edges WHERE relation=? AND source_id=? "
                + "AND target_id=? AND external_target_fqn IS ? AND call_kind IS ? "
                + "AND source_location IS ? LIMIT 1";
        String insertSql = "INSERT INTO edges(source_id,target_id,external_target_fqn,relation,"
                + "call_kind,confidence,resolution,context,is_external,source_file,source_location,metadata) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        int written = 0;
        try (PreparedStatement exists = connection.prepareStatement(existsSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (Edge edge : edges) {
                exists.setString(1, edge.relation);
                exists.setString(2, edge.sourceId);
                exists.setString(3, edge.targetId);
                exists.setString(4, edge.externalTargetFqn);
                exists.setString(5, edge.callKind);
                exists.setString(6, edge.sourceLocation);
                try (ResultSet rows = exists.executeQuery()) {
                    if (rows.next()) continue;
                }
                insert.setString(1, edge.sourceId);
                insert.setString(2, edge.targetId);
                insert.setString(3, edge.externalTargetFqn);
                insert.setString(4, edge.relation);
                insert.setString(5, edge.callKind);
                insert.setString(6, edge.confidence);
                insert.setString(7, edge.isExternal
                        ? (edge.resolution == null ? GraphConstants.Resolution.CLASSPATH : edge.resolution) : null);
                insert.setString(8, edge.context);
                insert.setInt(9, edge.isExternal ? 1 : 0);
                insert.setString(10, edge.sourceFile);
                insert.setString(11, edge.sourceLocation);
                insert.setString(12, edge.metadata);
                insert.addBatch();
                written++;
            }
            insert.executeBatch();
        }
        return written;
    }

    private static void deleteGenerated(Connection connection) throws SQLException {
        String sql = "DELETE FROM edges WHERE metadata LIKE ? OR metadata LIKE ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "%\"via\":\"" + GraphConstants.MetadataVia.INJECTION + "\"%");
            statement.setString(2, "%\"via\":\"" + GraphConstants.MetadataVia.INJECTED_CALL + "\"%");
            statement.executeUpdate();
        }
    }

    private static List<String> distinct(List<String> values) {
        return values == null || values.isEmpty()
                ? List.of() : new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static String metadata(String via, String source, List<String> candidates) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("via", via);
        value.put("source", source);
        value.put("candidates", candidates);
        return Json.writeCompact(value);
    }

    private static String metadata(String via, String interfaceType,
                                   String source, List<String> candidates) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("via", via);
        value.put("interfaceType", interfaceType);
        value.put("source", source);
        value.put("candidates", candidates);
        return Json.writeCompact(value);
    }

    private static String ownerTypeOfMethod(String methodId) {
        if (methodId == null) return null;
        int hash = methodId.indexOf('#');
        return hash <= 0 ? null : methodId.substring(0, hash);
    }
}
