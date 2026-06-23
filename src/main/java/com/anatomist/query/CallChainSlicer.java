package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CallChainSlicer {

    private static final int BATCH_SIZE = 500;

    private final Connection conn;

    public enum Level { CLASS, PACKAGE }

    public CallChainSlicer(Connection conn) {
        this.conn = conn;
    }

    public SliceResult slice(List<EdgeRow> chain, Level level) {
        if (chain.isEmpty()) {
            return new SliceResult(level.name().toLowerCase(), List.of());
        }

        Set<String> methodIds = collectMethodIds(chain);
        Map<String, String> owningType = batchOwningTypes(methodIds);

        Set<String> typeIds = new LinkedHashSet<>(owningType.values());
        Map<String, String> typePkg = batchPackages(typeIds);

        Set<String> allNodeIds = new LinkedHashSet<>(typeIds);
        allNodeIds.addAll(methodIds);
        Map<String, List<String>> annots = batchAnnotations(allNodeIds);

        Map<String, List<EdgeRow>> fieldAccesses = batchFieldAccesses(methodIds);
        Map<String, String> javadocs = batchJavadocs(methodIds);

        List<BlockResult> blocks = buildBlocks(chain, level, owningType, typePkg, annots, fieldAccesses);

        for (BlockResult b : blocks) {
            if (!b.methods.isEmpty()) {
                b.javadocSummary = javadocs.get(b.methods.get(0));
            }
        }

        return new SliceResult(level.name().toLowerCase(), blocks);
    }

    private List<BlockResult> buildBlocks(
            List<EdgeRow> chain, Level level,
            Map<String, String> owningType,
            Map<String, String> typePkg,
            Map<String, List<String>> annots,
            Map<String, List<EdgeRow>> fieldAccesses) {

        LinkedHashMap<String, BlockResult> blocksByKey = new LinkedHashMap<>();
        Map<String, String> methodToBlockKey = new HashMap<>();

        Set<String> seenMethods = new LinkedHashSet<>();
        for (EdgeRow e : chain) {
            if (e.source != null) seenMethods.add(e.source);
            if (e.target != null && !Boolean.TRUE.equals(e.isExternal)) seenMethods.add(e.target);
        }

        for (String mid : seenMethods) {
            String typeId = owningType.get(mid);
            String key = blockKey(typeId, typePkg, level);
            methodToBlockKey.put(mid, key);
            BlockResult block = blocksByKey.computeIfAbsent(key, k -> {
                BlockResult b = new BlockResult();
                b.name = blockName(typeId, typePkg, level);
                return b;
            });
            block.methods.add(mid);
            if (typeId != null) block.owningTypes.add(typeId);
            updateDepth(block, findDepthForMethod(mid, chain));
        }

        for (BlockResult block : blocksByKey.values()) {
            for (String mid : block.methods) {
                List<EdgeRow> fa = fieldAccesses.getOrDefault(mid, List.of());
                for (EdgeRow e : fa) {
                    if ("READS".equals(e.relation)) block.fieldsRead.add(e);
                    else if ("WRITES".equals(e.relation)) block.fieldsWritten.add(e);
                }
                List<String> ma = annots.getOrDefault(mid, List.of());
                for (String a : ma) {
                    if (!block.annotations.contains(a)) block.annotations.add(a);
                }
            }
            for (String typeId : block.owningTypes) {
                List<String> ta = annots.getOrDefault(typeId, List.of());
                for (String a : ta) {
                    if (!block.annotations.contains(a)) block.annotations.add(a);
                }
            }
        }

        for (EdgeRow e : chain) {
            String sourceKey = e.source != null ? methodToBlockKey.get(e.source) : null;
            String targetKey = (e.target != null && !Boolean.TRUE.equals(e.isExternal))
                    ? methodToBlockKey.get(e.target) : null;

            if (e.context != null && sourceKey != null) {
                BlockResult sb = blocksByKey.get(sourceKey);
                if (sb != null && !sb.controlFlowContext.contains(e.context)) {
                    sb.controlFlowContext.add(e.context);
                }
            }

            if (sourceKey != null && targetKey != null && sourceKey.equals(targetKey)) {
                blocksByKey.get(sourceKey).internalEdges.add(e);
            } else {
                if (sourceKey != null) {
                    blocksByKey.get(sourceKey).outboundEdges.add(e);
                }
                if (targetKey != null) {
                    blocksByKey.get(targetKey).inboundEdges.add(e);
                }
            }
        }

        List<BlockResult> result = new ArrayList<>(blocksByKey.values());
        for (BlockResult b : result) {
            if (b.depthRange[0] == Integer.MAX_VALUE) {
                b.depthRange = new int[]{0, 0};
            }
        }
        return result;
    }

    private String blockKey(String typeId, Map<String, String> typePkg, Level level) {
        if (typeId == null) return "__unknown__";
        if (level == Level.CLASS) return typeId;
        return typePkg.getOrDefault(typeId, "");
    }

    private String blockName(String typeId, Map<String, String> typePkg, Level level) {
        if (typeId == null) return "(unknown)";
        if (level == Level.CLASS) {
            String simple = typeId.contains(".") ? typeId.substring(typeId.lastIndexOf('.') + 1) : typeId;
            if (simple.contains("#")) simple = simple.substring(0, simple.indexOf('#'));
            return simple;
        }
        return typePkg.getOrDefault(typeId, "(default)");
    }

    private void updateDepth(BlockResult block, Integer depth) {
        if (depth == null) return;
        if (depth < block.depthRange[0]) block.depthRange[0] = depth;
        if (depth > block.depthRange[1]) block.depthRange[1] = depth;
    }

    private Integer findDepthForMethod(String mid, List<EdgeRow> chain) {
        for (EdgeRow e : chain) {
            if (mid.equals(e.source) || mid.equals(e.target)) return e.depth;
        }
        return null;
    }

    // ── batch queries ──

    private Set<String> collectMethodIds(List<EdgeRow> chain) {
        Set<String> ids = new LinkedHashSet<>();
        for (EdgeRow e : chain) {
            if (e.source != null) ids.add(e.source);
            if (e.target != null && !Boolean.TRUE.equals(e.isExternal)) ids.add(e.target);
        }
        return ids;
    }

    Map<String, String> batchOwningTypes(Set<String> methodIds) {
        Map<String, String> result = new HashMap<>();
        List<String> ids = new ArrayList<>(methodIds);
        for (int off = 0; off < ids.size(); off += BATCH_SIZE) {
            List<String> batch = ids.subList(off, Math.min(off + BATCH_SIZE, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT target_id, source_id FROM edges WHERE relation = 'CONTAINS' "
                    + "AND is_external = 0 AND target_id IN (" + placeholders + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("batchOwningTypes failed", e);
            }
        }
        return result;
    }

    Map<String, String> batchPackages(Set<String> typeIds) {
        Map<String, String> result = new HashMap<>();
        List<String> ids = new ArrayList<>(typeIds);
        for (int off = 0; off < ids.size(); off += BATCH_SIZE) {
            List<String> batch = ids.subList(off, Math.min(off + BATCH_SIZE, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT id, package FROM nodes WHERE id IN (" + placeholders + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("batchPackages failed", e);
            }
        }
        return result;
    }

    Map<String, List<String>> batchAnnotations(Set<String> nodeIds) {
        Map<String, List<String>> result = new HashMap<>();
        List<String> ids = new ArrayList<>(nodeIds);
        for (int off = 0; off < ids.size(); off += BATCH_SIZE) {
            List<String> batch = ids.subList(off, Math.min(off + BATCH_SIZE, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT node_id, annotation_fqn FROM annotations WHERE node_id IN ("
                    + placeholders + ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("batchAnnotations failed", e);
            }
        }
        return result;
    }

    Map<String, List<EdgeRow>> batchFieldAccesses(Set<String> methodIds) {
        Map<String, List<EdgeRow>> result = new HashMap<>();
        List<String> ids = new ArrayList<>(methodIds);
        for (int off = 0; off < ids.size(); off += BATCH_SIZE) {
            List<String> batch = ids.subList(off, Math.min(off + BATCH_SIZE, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT source_id, target_id, relation, is_external, "
                    + "external_target_fqn, context "
                    + "FROM edges WHERE source_id IN (" + placeholders + ") "
                    + "AND relation IN ('READS','WRITES')";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        EdgeRow e = new EdgeRow();
                        e.source = rs.getString(1);
                        e.target = rs.getString(2);
                        e.relation = rs.getString(3);
                        e.isExternal = rs.getInt(4) != 0;
                        e.externalTargetFqn = rs.getString(5);
                        e.context = rs.getString(6);
                        result.computeIfAbsent(e.source, k -> new ArrayList<>()).add(e);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("batchFieldAccesses failed", e);
            }
        }
        return result;
    }

    Map<String, String> batchJavadocs(Set<String> methodIds) {
        Map<String, String> result = new HashMap<>();
        List<String> ids = new ArrayList<>(methodIds);
        for (int off = 0; off < ids.size(); off += BATCH_SIZE) {
            List<String> batch = ids.subList(off, Math.min(off + BATCH_SIZE, ids.size()));
            String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
            String sql = "SELECT id, javadoc FROM nodes WHERE id IN (" + placeholders + ") AND javadoc IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < batch.size(); i++) ps.setString(i + 1, batch.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString(1), rs.getString(2));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("batchJavadocs failed", e);
            }
        }
        return result;
    }

}
