package com.anatomist.store;

import com.anatomist.model.Edge;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.GraphConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DataReader {

    private final ConnectionSupplier connSupplier;

    private static final String SQL_SELECT_WIRING_SOURCE_EDGES = """
            SELECT source_id, target_id, external_target_fqn, relation, call_kind,
                   confidence, context, is_external, source_file, source_location, metadata
            FROM edges
            WHERE relation IN (?, ?, ?, ?)
              AND (metadata IS NULL
                   OR (metadata NOT LIKE ? AND metadata NOT LIKE ?))
            """;

    public DataReader(ConnectionSupplier connSupplier) {
        this.connSupplier = connSupplier;
    }

    public Map<String, FileCacheEntry> readFileCache() {
        Connection c = conn();
        Map<String, FileCacheEntry> out = new LinkedHashMap<>();
        String sql = "SELECT source_file,hash,schema_version,last_indexed,node_count,edge_count FROM file_cache";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                FileCacheEntry e = new FileCacheEntry(
                        rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getInt(5), rs.getInt(6));
                out.put(e.sourceFile(), e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read file_cache", e);
        }
        return out;
    }

    public Optional<String> readProjectMeta(String key) {
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM project_meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.ofNullable(rs.getString(1));
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read project_meta", e);
        }
    }

    public Set<String> dependentsOf(List<String> seed) {
        Set<String> out = new LinkedHashSet<>();
        if (seed == null || seed.isEmpty()) return out;
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT source_file FROM file_dependencies WHERE depends_on_file = ?")) {
            for (String f : seed) {
                ps.setString(1, f);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query file_dependencies", e);
        }
        return out;
    }

    public Set<String> allNodeIds() {
        Set<String> out = new HashSet<>();
        Connection c = conn();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM nodes")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read node ids", e);
        }
        return out;
    }

    public Map<String, String> readBeanClassTargets() {
        Map<String, String> out = new HashMap<>();
        String sql = """
                SELECT e.source_id, COALESCE(e.target_id, e.external_target_fqn)
                FROM edges e
                JOIN nodes n ON n.id=e.source_id
                WHERE e.relation=? AND n.kind=?
                """;
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, GraphConstants.Relation.DEFINED_BY);
            ps.setString(2, GraphConstants.Kind.BEAN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String target = rs.getString(2);
                    if (target != null) out.put(rs.getString(1), target);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read bean class targets", e);
        }
        return out;
    }

    public Map<String, FileCacheService.SourceFileStats> sourceFileStats() {
        Map<String, FileCacheService.SourceFileStats> out = new LinkedHashMap<>();
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT source_file, COUNT(*) FROM nodes WHERE source_file <> '' GROUP BY source_file")) {
            while (rs.next()) {
                out.put(rs.getString(1), new FileCacheService.SourceFileStats(rs.getInt(2), 0));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count nodes by source_file", e);
        }
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT source_file, COUNT(*) FROM edges WHERE source_file IS NOT NULL GROUP BY source_file")) {
            while (rs.next()) {
                String sourceFile = rs.getString(1);
                FileCacheService.SourceFileStats prior = out.getOrDefault(
                        sourceFile, new FileCacheService.SourceFileStats(0, 0));
                out.put(sourceFile, new FileCacheService.SourceFileStats(prior.nodeCount(), rs.getInt(2)));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count edges by source_file", e);
        }
        return out;
    }

    public FileCacheService.SourceFileStats countRowsDeletedBySourceFiles(List<String> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return new FileCacheService.SourceFileStats(0, 0);
        }
        String placeholders = String.join(",", Collections.nCopies(sourceFiles.size(), "?"));
        String nodeSql = "SELECT COUNT(*) FROM nodes WHERE source_file IN (" + placeholders + ")";
        String edgeSql = "SELECT COUNT(DISTINCT e.id) FROM edges e "
                + "WHERE e.source_id IN (SELECT id FROM nodes WHERE source_file IN (" + placeholders + ")) "
                + "OR e.target_id IN (SELECT id FROM nodes WHERE source_file IN (" + placeholders + "))";
        try {
            int nodes = countWithBindings(nodeSql, sourceFiles);
            List<String> edgeBindings = new ArrayList<>(sourceFiles);
            edgeBindings.addAll(sourceFiles);
            int edges = countWithBindings(edgeSql, edgeBindings);
            return new FileCacheService.SourceFileStats(nodes, edges);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count graph rows by source_file", e);
        }
    }

    public FileCacheService.SourceFileStats countSpringBeanGraphRows() {
        String beanPredicate = "kind='" + GraphConstants.Kind.BEAN + "' AND source_file LIKE '%.xml'";
        String nodeSql = "SELECT COUNT(*) FROM nodes WHERE " + beanPredicate;
        String edgeSql = "SELECT COUNT(DISTINCT e.id) FROM edges e "
                + "WHERE e.relation='" + GraphConstants.Relation.WIRES + "' "
                + "OR e.source_id IN (SELECT id FROM nodes WHERE " + beanPredicate + ") "
                + "OR e.target_id IN (SELECT id FROM nodes WHERE " + beanPredicate + ")";
        try {
            return new FileCacheService.SourceFileStats(count(nodeSql), count(edgeSql));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count Spring bean graph rows", e);
        }
    }

    public int countGeneratedWiringEdges() {
        String sql = "SELECT COUNT(*) FROM edges WHERE metadata LIKE ? OR metadata LIKE ?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, "%\"via\":\"" + GraphConstants.MetadataVia.INJECTION + "\"%");
            ps.setString(2, "%\"via\":\"" + GraphConstants.MetadataVia.INJECTED_CALL + "\"%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count generated wiring edges", e);
        }
    }

    public List<Edge> readWiringSourceEdges() {
        Connection c = conn();
        try {
            return readWiringSourceEdges(c);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read wiring source edges", e);
        }
    }

    static List<Edge> readWiringSourceEdges(Connection c) throws SQLException {
        List<Edge> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(SQL_SELECT_WIRING_SOURCE_EDGES)) {
            ps.setString(1, GraphConstants.Relation.INJECTS);
            ps.setString(2, GraphConstants.Relation.IMPLEMENTS);
            ps.setString(3, GraphConstants.Relation.OVERRIDES);
            ps.setString(4, GraphConstants.Relation.CALLS);
            ps.setString(5, "%\"via\":\"" + GraphConstants.MetadataVia.INJECTION + "\"%");
            ps.setString(6, "%\"via\":\"" + GraphConstants.MetadataVia.INJECTED_CALL + "\"%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Edge e = new Edge();
                    e.sourceId = rs.getString(1);
                    e.targetId = rs.getString(2);
                    e.externalTargetFqn = rs.getString(3);
                    e.relation = rs.getString(4);
                    e.callKind = rs.getString(5);
                    e.confidence = rs.getString(6);
                    e.context = rs.getString(7);
                    e.isExternal = rs.getInt(8) != 0;
                    e.sourceFile = rs.getString(9);
                    e.sourceLocation = rs.getString(10);
                    e.metadata = rs.getString(11);
                    out.add(e);
                }
            }
        }
        return out;
    }

    public Map<String, Long> queryKindCounts() {
        Map<String, Long> counts = new HashMap<>();
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT kind, COUNT(*) FROM nodes GROUP BY kind")) {
            while (rs.next()) counts.put(rs.getString(1), rs.getLong(2));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query kind counts", e);
        }
        return counts;
    }

    public Map<String, Long> queryRelationCounts() {
        Map<String, Long> counts = new HashMap<>();
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT relation, COUNT(*) FROM edges GROUP BY relation")) {
            while (rs.next()) counts.put(rs.getString(1), rs.getLong(2));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query relation counts", e);
        }
        return counts;
    }

    public long queryAnnotationCount() {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM annotations")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query annotation count", e);
        }
    }

    public long querySemanticAnnotationCount() {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM semantic_annotations")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query semantic annotation count", e);
        }
    }

    private int count(String sql) throws SQLException {
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private int countWithBindings(String sql, List<String> bindings) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (int i = 0; i < bindings.size(); i++) {
                ps.setString(i + 1, bindings.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private Connection conn() {
        try {
            return connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
    }
}
