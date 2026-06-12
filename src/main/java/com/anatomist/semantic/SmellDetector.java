package com.anatomist.semantic;

import com.anatomist.store.SqliteStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SmellDetector {

    private final SqliteStore store;

    public SmellDetector(SqliteStore store) {
        this.store = store;
    }

    public List<Smell> detect() {
        try {
            Connection conn = store.connection();
            List<Smell> smells = new ArrayList<>();
            smells.addAll(detectAnemicModel(conn));
            smells.addAll(detectFatApplication(conn));
            smells.addAll(detectLayerBypass(conn));
            smells.addAll(detectCircularDeps(conn));
            smells.addAll(detectAdapterLeak(conn));
            smells.addAll(detectDomainSpillover(conn));
            return smells;
        } catch (SQLException e) {
            throw new RuntimeException("SmellDetector failed", e);
        }
    }

    // SMELL 1: Anemic model — DOMAIN_MODEL classes with only getters/setters
    List<Smell> detectAnemicModel(Connection conn) throws SQLException {
        List<Smell> result = new ArrayList<>();
        String sql = "SELECT ar.node_id, n.label FROM arch_roles ar "
                + "JOIN nodes n ON ar.node_id = n.id "
                + "WHERE ar.role = 'DOMAIN_MODEL'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String nodeId = rs.getString(1);
                String label = rs.getString(2);
                if (isAnemic(conn, nodeId)) {
                    result.add(new Smell("anemic-model", nodeId, label,
                            "DOMAIN_MODEL class has only getters/setters, no business methods",
                            "Add business logic methods or reclassify as DTO"));
                }
            }
        }
        return result;
    }

    private boolean isAnemic(Connection conn, String typeNodeId) throws SQLException {
        // Get all methods of this type
        String sql = "SELECT n.id, n.metadata FROM nodes n "
                + "JOIN edges e ON e.target_id = n.id "
                + "WHERE e.source_id = ? AND e.relation = 'CONTAINS' AND n.kind = 'METHOD'";
        int totalMethods = 0;
        int accessorCount = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, typeNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    totalMethods++;
                    String metadata = rs.getString(2);
                    if (metadata != null && metadata.contains("\"isAccessor\":true")) {
                        accessorCount++;
                    }
                }
            }
        }
        if (totalMethods == 0) return true;
        return accessorCount == totalMethods;
    }

    // SMELL 2: Fat Application — APPLICATION class with business logic (high call depth)
    List<Smell> detectFatApplication(Connection conn) throws SQLException {
        List<Smell> result = new ArrayList<>();
        String sql = "SELECT ar.node_id, n.label FROM arch_roles ar "
                + "JOIN nodes n ON ar.node_id = n.id "
                + "WHERE ar.role = 'APPLICATION'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String nodeId = rs.getString(1);
                String label = rs.getString(2);
                int selfCalls = countSelfCalls(conn, nodeId);
                if (selfCalls > 3) {
                    result.add(new Smell("fat-application", nodeId, label,
                            "APPLICATION class has " + selfCalls + " internal method calls (likely contains business logic)",
                            "Extract business rules to a DOMAIN_SERVICE class"));
                }
            }
        }
        return result;
    }

    private int countSelfCalls(Connection conn, String typeNodeId) throws SQLException {
        // Count CALLS edges where both source and target methods belong to this type
        String sql = "SELECT COUNT(*) FROM edges e "
                + "JOIN edges c1 ON c1.source_id = ? AND c1.relation = 'CONTAINS' AND c1.target_id = e.source_id "
                + "JOIN edges c2 ON c2.source_id = ? AND c2.relation = 'CONTAINS' AND c2.target_id = e.target_id "
                + "WHERE e.relation = 'CALLS' AND e.is_external = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, typeNodeId);
            ps.setString(2, typeNodeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // SMELL 3: Layer bypass — ENTRY directly calls REPOSITORY (skips APPLICATION)
    List<Smell> detectLayerBypass(Connection conn) throws SQLException {
        List<Smell> result = new ArrayList<>();
        String sql = "SELECT DISTINCT entry_n.label, repo_n.label, entry_ar.node_id, repo_ar.node_id "
                + "FROM arch_roles entry_ar "
                + "JOIN arch_roles repo_ar ON repo_ar.role = 'REPOSITORY' "
                + "JOIN nodes entry_n ON entry_ar.node_id = entry_n.id "
                + "JOIN nodes repo_n ON repo_ar.node_id = repo_n.id "
                + "WHERE entry_ar.role = 'ENTRY' "
                + "AND EXISTS ("
                + "  SELECT 1 FROM edges e_method "
                + "  JOIN edges c_entry ON c_entry.source_id = entry_ar.node_id AND c_entry.relation = 'CONTAINS' AND c_entry.target_id = e_method.source_id "
                + "  JOIN edges c_repo ON c_repo.source_id = repo_ar.node_id AND c_repo.relation = 'CONTAINS' AND c_repo.target_id = e_method.target_id "
                + "  WHERE e_method.relation = 'CALLS' AND e_method.is_external = 0"
                + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String entryLabel = rs.getString(1);
                String repoLabel = rs.getString(2);
                result.add(new Smell("layer-bypass", rs.getString(3), entryLabel,
                        entryLabel + " (ENTRY) directly calls " + repoLabel + " (REPOSITORY), skipping APPLICATION layer",
                        "Route through an APPLICATION class"));
            }
        }
        return result;
    }

    // SMELL 4: Circular dependency — APPLICATION ↔ DOMAIN_SERVICE bidirectional calls
    List<Smell> detectCircularDeps(Connection conn) throws SQLException {
        List<Smell> result = new ArrayList<>();
        String sql = "SELECT DISTINCT a1_n.label, a2_n.label, a1.node_id, a2.node_id "
                + "FROM arch_roles a1 "
                + "JOIN arch_roles a2 ON a1.node_id != a2.node_id "
                + "JOIN nodes a1_n ON a1.node_id = a1_n.id "
                + "JOIN nodes a2_n ON a2.node_id = a2_n.id "
                + "WHERE a1.role = 'APPLICATION' AND a2.role = 'DOMAIN_SERVICE' "
                + "AND EXISTS ("
                + "  SELECT 1 FROM edges e1 "
                + "  JOIN edges c1 ON c1.source_id = a1.node_id AND c1.relation = 'CONTAINS' AND c1.target_id = e1.source_id "
                + "  JOIN edges c2 ON c2.source_id = a2.node_id AND c2.relation = 'CONTAINS' AND c2.target_id = e1.target_id "
                + "  WHERE e1.relation = 'CALLS' AND e1.is_external = 0"
                + ") AND EXISTS ("
                + "  SELECT 1 FROM edges e2 "
                + "  JOIN edges c3 ON c3.source_id = a2.node_id AND c3.relation = 'CONTAINS' AND c3.target_id = e2.source_id "
                + "  JOIN edges c4 ON c4.source_id = a1.node_id AND c4.relation = 'CONTAINS' AND c4.target_id = e2.target_id "
                + "  WHERE e2.relation = 'CALLS' AND e2.is_external = 0"
                + ")";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new Smell("circular-dependency", rs.getString(3), rs.getString(1),
                        rs.getString(1) + " (APPLICATION) ↔ " + rs.getString(2) + " (DOMAIN_SERVICE) have bidirectional calls",
                        "Break the cycle: DOMAIN_SERVICE should not call back into APPLICATION"));
            }
        }
        return result;
    }

    // SMELL 5: Adapter leak — non-ADAPTER class directly calls HTTP/MQ external APIs
    List<Smell> detectAdapterLeak(Connection conn) throws SQLException {
        List<Smell> result = new ArrayList<>();
        Set<String> adapterNodes = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT node_id FROM arch_roles WHERE role = 'ADAPTER'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) adapterNodes.add(rs.getString(1));
        }

        String sql = "SELECT DISTINCT n_type.id, n_type.label FROM nodes n_type "
                + "JOIN edges contains ON contains.source_id = n_type.id AND contains.relation = 'CONTAINS' AND contains.is_external = 0 "
                + "JOIN edges calls ON calls.source_id = contains.target_id AND calls.relation = 'CALLS' AND calls.is_external = 1 "
                + "WHERE n_type.kind IN ('CLASS','INTERFACE') "
                + "AND (calls.external_target_fqn LIKE 'org.springframework.web.client.%' "
                + "  OR calls.external_target_fqn LIKE 'java.net.http.%' "
                + "  OR calls.external_target_fqn LIKE 'org.apache.http.%' "
                + "  OR calls.external_target_fqn LIKE 'okhttp3.%' "
                + "  OR calls.external_target_fqn LIKE 'feign.%' "
                + "  OR calls.external_target_fqn LIKE 'org.springframework.kafka.%' "
                + "  OR calls.external_target_fqn LIKE 'org.springframework.amqp.%' "
                + "  OR calls.external_target_fqn LIKE 'org.springframework.jms.%')";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String nodeId = rs.getString(1);
                if (!adapterNodes.contains(nodeId)) {
                    String label = rs.getString(2);
                    result.add(new Smell("adapter-leak", nodeId, label,
                            label + " directly calls external HTTP/MQ client but is not classified as ADAPTER",
                            "Move external API calls to a dedicated ADAPTER class"));
                }
            }
        }
        return result;
    }

    // SMELL 6: Domain spillover — DOMAIN_MODEL class depends on Spring/framework annotations
    List<Smell> detectDomainSpillover(Connection conn) throws SQLException {
        List<Smell> result = new ArrayList<>();
        String sql = "SELECT ar.node_id, n.label, a.annotation_fqn FROM arch_roles ar "
                + "JOIN nodes n ON ar.node_id = n.id "
                + "JOIN annotations a ON a.node_id = ar.node_id "
                + "WHERE ar.role = 'DOMAIN_MODEL' "
                + "AND (a.annotation_fqn LIKE 'org.springframework.%' "
                + "  OR a.annotation_fqn LIKE 'jakarta.inject.%' "
                + "  OR a.annotation_fqn LIKE 'javax.inject.%') "
                + "AND a.annotation_fqn NOT LIKE 'jakarta.persistence.%' "
                + "AND a.annotation_fqn NOT LIKE 'javax.persistence.%'";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String nodeId = rs.getString(1);
                String label = rs.getString(2);
                String annFqn = rs.getString(3);
                result.add(new Smell("domain-spillover", nodeId, label,
                        label + " (DOMAIN_MODEL) depends on framework annotation: " + annFqn,
                        "Remove framework dependency from domain model"));
            }
        }
        return result;
    }

    public static class Smell {
        public final String type;
        public final String nodeId;
        public final String nodeLabel;
        public final String description;
        public final String suggestion;

        public Smell(String type, String nodeId, String nodeLabel, String description, String suggestion) {
            this.type = type;
            this.nodeId = nodeId;
            this.nodeLabel = nodeLabel;
            this.description = description;
            this.suggestion = suggestion;
        }
    }
}
