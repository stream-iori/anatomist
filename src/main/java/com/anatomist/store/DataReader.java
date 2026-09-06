package com.anatomist.store;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.ResolutionDiagnostics;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.anatomist.model.ProducerIds;

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
                   confidence, resolution, context, is_external, source_file, source_location, metadata, producer_id
            FROM edges
            WHERE relation IN (?, ?, ?, ?)
              AND producer_id <> ?
            """;

    public DataReader(ConnectionSupplier connSupplier) {
        this.connSupplier = connSupplier;
    }

    public Map<String, FileCacheEntry> readFileCache() {
        Connection c = conn();
        Map<String, FileCacheEntry> out = new LinkedHashMap<>();
        String sql = "SELECT provider_id,source_file,hash,schema_version,last_indexed,node_count,edge_count,"
                + "file_size,file_mtime_ns,contract_hash FROM file_cache";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                FileCacheEntry e = new FileCacheEntry(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7),
                        rs.getLong(8), rs.getLong(9), rs.getString(10));
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

    public long countNodesByProducer(String producerId) {
        try (PreparedStatement statement = conn().prepareStatement(
                "SELECT count(*) FROM nodes WHERE producer_id=?")) {
            statement.setString(1, producerId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException failure) {
            throw new RuntimeException("Failed to count nodes by producer", failure);
        }
    }

    public Map<String, String> readProjectMeta() {
        Map<String, String> out = new LinkedHashMap<>();
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT key,value FROM project_meta")) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read project_meta", e);
        }
        return out;
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

    public Map<String, Node> readNodesBySourceFiles(List<String> sourceFiles) {
        Map<String, Node> out = new LinkedHashMap<>();
        if (sourceFiles == null || sourceFiles.isEmpty()) return out;
        String sql = "SELECT id,symbol_id,label,kind,qualified_name,package,source_file,"
                + "source_location,module,scope,javadoc,metadata,producer_id FROM nodes WHERE source_file=?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (String sourceFile : sourceFiles) {
                ps.setString(1, sourceFile);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Node node = new Node();
                        node.id = rs.getString(1);
                        node.symbolId = rs.getString(2);
                        node.label = rs.getString(3);
                        node.kind = rs.getString(4);
                        node.qualifiedName = rs.getString(5);
                        node.pkg = rs.getString(6);
                        node.sourceFile = rs.getString(7);
                        node.sourceLocation = rs.getString(8);
                        node.module = rs.getString(9);
                        node.scope = rs.getString(10);
                        node.javadoc = rs.getString(11);
                        node.metadata = rs.getString(12);
                        node.producerId = rs.getString(13);
                        out.put(node.id, node);
                    }
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read nodes by source file", e);
        }
    }

    public Set<String> sourceFilesReferencingNodeIds(Set<String> nodeIds) {
        Set<String> out = new LinkedHashSet<>();
        if (nodeIds == null || nodeIds.isEmpty()) return out;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT source_file FROM edges WHERE target_id=? AND source_file IS NOT NULL UNION "
                        + "SELECT cso.source_file FROM call_site_targets cst "
                        + "JOIN call_sites cs ON cs.site_pk=cst.call_site_pk "
                        + "JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk WHERE cst.target_id=?")) {
            for (String nodeId : nodeIds) {
                ps.setString(1, nodeId);
                ps.setString(2, nodeId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read symbol dependents", e);
        }
    }

    public Set<String> sourceFilesReferencingOwnerIds(Set<String> ownerIds) {
        Set<String> out = new LinkedHashSet<>();
        if (ownerIds == null || ownerIds.isEmpty()) return out;
        String indexedSql = "SELECT e.source_file FROM edges e "
                + "WHERE e.target_id=? AND e.source_file IS NOT NULL UNION "
                + "SELECT e.source_file FROM edges e WHERE e.target_id>=? AND e.target_id<? "
                + "AND e.source_file IS NOT NULL UNION "
                + "SELECT cso.source_file FROM call_site_targets cst JOIN call_sites cs "
                + "ON cs.site_pk=cst.call_site_pk JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "WHERE cst.target_id=? UNION "
                + "SELECT cso.source_file FROM call_site_targets cst JOIN call_sites cs "
                + "ON cs.site_pk=cst.call_site_pk JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "WHERE cst.target_id>=? AND cst.target_id<?";
        String fallbackSql = "SELECT DISTINCT e.source_file FROM edges e "
                + "WHERE (e.target_id=? OR substr(e.target_id,1,length(?))=?) "
                + "AND e.source_file IS NOT NULL UNION SELECT DISTINCT cso.source_file "
                + "FROM call_site_targets cst JOIN call_sites cs ON cs.site_pk=cst.call_site_pk "
                + "JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "WHERE cst.target_id=? OR substr(cst.target_id,1,length(?))=?";
        try (PreparedStatement indexed = conn().prepareStatement(indexedSql);
             PreparedStatement fallback = conn().prepareStatement(fallbackSql)) {
            for (String ownerId : ownerIds) {
                String memberPrefix = ownerId + "#";
                String upper = nextPrefix(memberPrefix);
                PreparedStatement ps = upper == null ? fallback : indexed;
                ps.setString(1, ownerId);
                ps.setString(2, memberPrefix);
                ps.setString(3, upper == null ? memberPrefix : upper);
                ps.setString(4, ownerId);
                ps.setString(5, memberPrefix);
                ps.setString(6, upper == null ? memberPrefix : upper);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read owner dependents", e);
        }
    }

    public Set<String> sourceFilesMatchingExternalTargets(Set<String> logicalPrefixes) {
        Set<String> out = new LinkedHashSet<>();
        if (logicalPrefixes == null || logicalPrefixes.isEmpty()) return out;
        String indexedSql = "SELECT source_file FROM edges WHERE is_external=1 "
                + "AND external_target_fqn>=? AND external_target_fqn<? AND source_file IS NOT NULL UNION "
                + "SELECT cso.source_file FROM call_site_targets cst JOIN call_sites cs "
                + "ON cs.site_pk=cst.call_site_pk JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "WHERE cst.external_target_fqn>=? "
                + "AND cst.external_target_fqn<?";
        String fallbackSql = "SELECT source_file FROM edges WHERE is_external=1 "
                + "AND substr(external_target_fqn,1,length(?))=? AND source_file IS NOT NULL UNION "
                + "SELECT cso.source_file FROM call_site_targets cst JOIN call_sites cs "
                + "ON cs.site_pk=cst.call_site_pk JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "WHERE substr(cst.external_target_fqn,1,length(?))=?";
        try (PreparedStatement indexed = conn().prepareStatement(indexedSql);
             PreparedStatement fallback = conn().prepareStatement(fallbackSql)) {
            for (String prefix : logicalPrefixes) {
                String upper = nextPrefix(prefix);
                PreparedStatement ps = upper == null ? fallback : indexed;
                ps.setString(1, prefix);
                ps.setString(2, upper == null ? prefix : upper);
                ps.setString(3, prefix);
                ps.setString(4, upper == null ? prefix : upper);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to match external symbol dependents", e);
        }
    }

    public Set<String> sourceFilesMatchingExactExternalTargets(Set<String> logicalTargets) {
        Set<String> out = new LinkedHashSet<>();
        if (logicalTargets == null || logicalTargets.isEmpty()) return out;
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT source_file FROM edges WHERE is_external=1 "
                        + "AND external_target_fqn=? AND source_file IS NOT NULL UNION "
                        + "SELECT cso.source_file FROM call_site_targets cst JOIN call_sites cs "
                        + "ON cs.site_pk=cst.call_site_pk JOIN call_site_owners cso "
                        + "ON cso.owner_pk=cs.owner_pk WHERE cst.external_target_fqn=?")) {
            for (String target : logicalTargets) {
                ps.setString(1, target);
                ps.setString(2, target);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to match exact external symbol dependents", e);
        }
    }

    public Set<String> sourceFilesImplementingTypeIds(Set<String> ownerIds) {
        Set<String> out = new LinkedHashSet<>();
        if (ownerIds == null || ownerIds.isEmpty()) return out;
        String sql = "WITH RECURSIVE implementations(id) AS ("
                + "SELECT source_id FROM edges WHERE target_id=? "
                + "AND relation IN ('IMPLEMENTS','INHERITS') UNION "
                + "SELECT e.source_id FROM edges e JOIN implementations i ON e.target_id=i.id "
                + "WHERE e.relation IN ('IMPLEMENTS','INHERITS')) "
                + "SELECT DISTINCT n.source_file FROM nodes n JOIN implementations i ON n.id=i.id "
                + "WHERE n.source_file IS NOT NULL";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            for (String ownerId : ownerIds) {
                ps.setString(1, ownerId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read interface implementors", e);
        }
    }

    static String nextPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return null;
        int end = prefix.length();
        int codePoint = prefix.codePointBefore(end);
        if (codePoint >= Character.MAX_CODE_POINT) return null;
        int start = prefix.offsetByCodePoints(end, -1);
        return prefix.substring(0, start) + Character.toString(codePoint + 1);
    }

    public Map<String, String> readBeanClassTargets() {
        Map<String, String> out = new HashMap<>();
        String sql = "SELECT symbol_id,metadata FROM nodes WHERE kind=?";
        Connection c = conn();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, GraphConstants.Kind.BEAN);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String target = beanClassFromMetadata(rs.getString(2));
                    if (target == null) target = fallbackBeanClass(c, rs.getString(1));
                    if (target != null) out.put(rs.getString(1), target);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read bean class targets", e);
        }
        return out;
    }

    private static String fallbackBeanClass(Connection connection, String bean) throws SQLException {
        String sql = "SELECT COALESCE(e.target_id,e.external_target_fqn) FROM edges e "
                + "JOIN nodes n ON n.id=e.source_id WHERE n.symbol_id=? AND e.relation='DEFINED_BY' "
                + "ORDER BY e.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, bean);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String target = rows.getString(1);
                    if (target != null) {
                        String symbol = com.anatomist.core.NodeKeyFactory.isKey(target)
                                ? com.anatomist.core.NodeKeyFactory.symbolId(target) : target;
                        if (!symbol.contains("#")) return symbol;
                    }
                }
            }
        }
        return null;
    }

    private static String beanClassFromMetadata(String metadata) {
        if (metadata == null) return null;
        try {
            Object tree = com.anatomist.json.Json.parseTree(metadata);
            if (!(tree instanceof Map<?, ?> map)) return null;
            for (String key : List.of("productClass", "returnType", "className")) {
                Object value = map.get(key);
                if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
            }
        } catch (RuntimeException ignored) { }
        return null;
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
        String beanPredicate = "producer_id='" + ProducerIds.SPRING_XML + "'";
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
        String sql = "SELECT COUNT(*) FROM edges WHERE producer_id=?";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, ProducerIds.DERIVED_WIRING);
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

    public List<Edge> readWiringSourceEdgesBySourceFiles(List<String> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(sourceFiles.size(), "?"));
        String sql = """
                SELECT source_id, target_id, external_target_fqn, relation, call_kind,
                       confidence, resolution, context, is_external, source_file, source_location, metadata
                FROM edges
                WHERE relation IN (?, ?, ?, ?)
                  AND (metadata IS NULL OR (metadata NOT LIKE ? AND metadata NOT LIKE ?))
                  AND source_file IN (%s)
                """.formatted(placeholders);
        List<Edge> out = new ArrayList<>();
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            int index = bindWiringParameters(ps);
            for (String sourceFile : sourceFiles) ps.setString(index++, sourceFile);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(edgeFromRow(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read wiring source edges by source file", e);
        }
    }

    public int countGeneratedWiringEdgesBySourceFiles(List<String> sourceFiles) {
        if (sourceFiles == null || sourceFiles.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(sourceFiles.size(), "?"));
        String sql = "SELECT COUNT(DISTINCT e.id) FROM edges e WHERE "
                + "(e.metadata LIKE ? OR e.metadata LIKE ?) AND ("
                + "e.source_id IN (SELECT id FROM nodes WHERE source_file IN (" + placeholders + ")) OR "
                + "e.target_id IN (SELECT id FROM nodes WHERE source_file IN (" + placeholders + ")))";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            ps.setString(1, "%\"via\":\"" + GraphConstants.MetadataVia.INJECTION + "\"%");
            ps.setString(2, "%\"via\":\"" + GraphConstants.MetadataVia.INJECTED_CALL + "\"%");
            int index = 3;
            for (int repeat = 0; repeat < 2; repeat++) {
                for (String sourceFile : sourceFiles) ps.setString(index++, sourceFile);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count generated wiring edges by source file", e);
        }
    }

    static List<Edge> readWiringSourceEdges(Connection c) throws SQLException {
        List<Edge> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(SQL_SELECT_WIRING_SOURCE_EDGES)) {
            bindWiringParameters(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(edgeFromRow(rs));
            }
        }
        return out;
    }

    private static int bindWiringParameters(PreparedStatement ps) throws SQLException {
        ps.setString(1, GraphConstants.Relation.INJECTS);
        ps.setString(2, GraphConstants.Relation.IMPLEMENTS);
        ps.setString(3, GraphConstants.Relation.OVERRIDES);
        ps.setString(4, GraphConstants.Relation.CALLS);
        ps.setString(5, ProducerIds.DERIVED_WIRING);
        return 6;
    }

    private static Edge edgeFromRow(ResultSet rs) throws SQLException {
        Edge e = new Edge();
        e.sourceId = rs.getString(1);
        e.targetId = rs.getString(2);
        e.externalTargetFqn = rs.getString(3);
        e.relation = rs.getString(4);
        e.callKind = rs.getString(5);
        e.confidence = rs.getString(6);
        e.resolution = rs.getString(7);
        e.context = rs.getString(8);
        e.isExternal = rs.getInt(9) != 0;
        e.sourceFile = rs.getString(10);
        e.sourceLocation = rs.getString(11);
        e.metadata = rs.getString(12);
        e.producerId = rs.getString(13);
        return e;
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

    public Set<String> queryPackagesByKinds(Set<String> kinds) {
        Set<String> out = new LinkedHashSet<>();
        if (kinds == null || kinds.isEmpty()) return out;
        String placeholders = String.join(",", java.util.Collections.nCopies(kinds.size(), "?"));
        String sql = "SELECT DISTINCT package FROM nodes WHERE kind IN (" + placeholders
                + ") AND package IS NOT NULL AND package <> ''";
        try (PreparedStatement statement = conn().prepareStatement(sql)) {
            int index = 1;
            for (String kind : kinds) statement.setString(index++, kind);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) out.add(rows.getString(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query project packages", e);
        }
        return out;
    }

    public Map<String, Long> queryRelationCounts() {
        Map<String, Long> counts = new HashMap<>();
        try (Statement st = conn().createStatement();
             ResultSet rs = st.executeQuery("SELECT relation, COUNT(*) FROM edges GROUP BY relation")) {
            while (rs.next()) counts.put(rs.getString(1), rs.getLong(2));
            try (ResultSet calls = st.executeQuery("SELECT COUNT(*) FROM call_site_targets")) {
                if (calls.next()) counts.put(GraphConstants.Relation.CALLS, calls.getLong(1));
            }
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

    public List<IndexDiagnostic> readIndexDiagnostics() {
        List<IndexDiagnostic> out = new ArrayList<>();
        String sql = "SELECT severity,code,phase,source_file,module,scope,symbol,occurrence_count,sample,language,provider_id,provider_reason "
                + "FROM index_diagnostics ORDER BY CASE severity WHEN 'error' THEN 0 WHEN 'warning' THEN 1 ELSE 2 END, code";
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(new IndexDiagnostic(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11),
                        rs.getString(12)));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read index diagnostics", e);
        }
    }

    /** Lossless resolution occurrence counts from analysis_coverage. */
    public Map<String, Long> readResolutionDiagnosticCounts() {
        Map<String, Long> out = new java.util.TreeMap<>();
        String sql = "SELECT code_counts FROM analysis_coverage WHERE capability='AGGREGATE'";
        try (Statement st = conn().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Object tree = Json.parseTree(rs.getString(1));
                if (!(tree instanceof Map<?, ?> counts)) continue;
                counts.forEach((code, value) -> {
                    String key = String.valueOf(code);
                    if (ResolutionDiagnostics.isReasonCode(key) && value instanceof Number number) {
                        out.merge(key, number.longValue(), Long::sum);
                    }
                });
            }
            if (!out.isEmpty()) {
                long aggregate = out.values().stream().mapToLong(Long::longValue).sum();
                out.put("UNRESOLVED_SYMBOLS", aggregate);
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read resolution diagnostic coverage", e);
        }
    }

    /** File-level lossless coverage, optionally filtered like doctor --diagnostic-file. */
    public List<Map<String, Object>> readDiagnosticCoverage(String sourceFileFilter) {
        List<Map<String, Object>> out = new ArrayList<>();
        String sql = "SELECT source_file,module,scope,language,provider_id,capability,status,occurrences,groups_count,"
                + "codes,code_counts,details_truncated FROM analysis_coverage"
                + (sourceFileFilter == null || sourceFileFilter.isBlank()
                ? "" : " WHERE source_file LIKE ?")
                + " ORDER BY provider_id,source_file,module,scope,capability";
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            if (sourceFileFilter != null && !sourceFileFilter.isBlank()) {
                ps.setString(1, "%" + sourceFileFilter.replace('\\', '/') + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("source_file", rs.getString(1).replace('\\', '/'));
                    row.put("module", rs.getString(2));
                    row.put("scope", rs.getString(3));
                    row.put("language", rs.getString(4));
                    row.put("provider_id", rs.getString(5));
                    row.put("capability", rs.getString(6));
                    row.put("status", rs.getString(7));
                    row.put("occurrences", rs.getLong(8));
                    row.put("groups", rs.getLong(9));
                    row.put("codes", Json.parseTree(rs.getString(10)));
                    row.put("code_counts", Json.parseTree(rs.getString(11)));
                    row.put("details_truncated", rs.getInt(12) != 0);
                    out.add(row);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read diagnostic coverage", e);
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
