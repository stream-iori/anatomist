package com.anatomist.store;

import com.anatomist.model.FileCacheEntry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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

    private Connection conn() {
        try {
            return connSupplier.get();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to acquire SQLite connection", e);
        }
    }
}
