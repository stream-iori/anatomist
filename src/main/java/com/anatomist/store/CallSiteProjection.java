package com.anatomist.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds canonical call-site/target facts from source-backed CALLS edges. */
final class CallSiteProjection {
    private CallSiteProjection() {}

    static void rebuild(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_sites_caller_order");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_sites_source");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_site_targets_internal");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_site_targets_external");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_site_targets_identity");
            statement.executeUpdate("DELETE FROM call_site_targets");
            statement.executeUpdate("DELETE FROM call_sites");
        }
        insert(connection, readSites(connection, Filter.all()));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_call_sites_caller_order ON call_sites("
                    + "caller_id,source_file,begin_line,begin_column,ordinal)");
            statement.executeUpdate("CREATE INDEX idx_call_sites_source ON call_sites("
                    + "source_file,begin_line,begin_column,ordinal)");
            statement.executeUpdate("CREATE INDEX idx_call_site_targets_internal "
                    + "ON call_site_targets(target_id)");
            statement.executeUpdate("CREATE INDEX idx_call_site_targets_external "
                    + "ON call_site_targets(external_target_fqn)");
            statement.executeUpdate("CREATE UNIQUE INDEX idx_call_site_targets_identity "
                    + "ON call_site_targets(call_site_pk,COALESCE(target_id,''),"
                    + "COALESCE(external_target_fqn,''))");
        }
    }

    /** Captures callers whose projection can change when files or whole-project producers change. */
    static AffectedScope captureAffected(Connection connection, List<String> sourceFiles,
                                         Set<String> producerIds) throws SQLException {
        List<String> files = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
        Set<String> producers = producerIds == null ? Set.of() : Set.copyOf(producerIds);
        if (files.isEmpty() && producers.isEmpty()) return new AffectedScope(Set.of(), files, producers);

        List<String> predicates = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (!files.isEmpty()) {
            predicates.add("cs.source_file IN (" + qmarks(files.size()) + ")");
            args.addAll(files);
            predicates.add("tgt.source_file IN (" + qmarks(files.size()) + ")");
            args.addAll(files);
        }
        if (!producers.isEmpty()) {
            predicates.add("cs.producer_id IN (" + qmarks(producers.size()) + ")");
            args.addAll(producers);
            predicates.add("cst.producer_id IN (" + qmarks(producers.size()) + ")");
            args.addAll(producers);
        }
        String sql = "SELECT DISTINCT cs.caller_id FROM call_sites cs "
                + "LEFT JOIN call_site_targets cst ON cst.call_site_pk=cs.site_pk "
                + "LEFT JOIN nodes tgt ON tgt.id=cst.target_id WHERE "
                + String.join(" OR ", predicates);
        Set<String> callers = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) callers.add(rows.getString(1));
            }
        }
        return new AffectedScope(Set.copyOf(callers), files, producers);
    }

    /** Refreshes only the call sites affected by an incremental graph replacement. */
    static void refresh(Connection connection, AffectedScope scope) throws SQLException {
        Filter filter = Filter.of(scope.callerIds(), scope.sourceFiles(), scope.producerIds());
        if (filter.empty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM call_sites WHERE " + filter.callSiteClause())) {
            bind(statement, filter.args());
            statement.executeUpdate();
        }
        insert(connection, readSites(connection, filter));
    }

    private static void insert(Connection connection, Map<SiteKey, SiteGroup> sites)
            throws SQLException {
        if (sites.isEmpty()) return;
        MessageDigest digest = sha256();
        List<SiteInsert> inserts = sites.entrySet().stream()
                .map(entry -> new SiteInsert(entry.getKey(), entry.getValue(),
                        stableHash(entry.getKey(), digest)))
                .toList();
        String siteSql = "INSERT INTO call_sites(stable_hash,caller_id,source_file,begin_line,begin_column,"
                + "end_line,end_column,ordinal,syntax_target,receiver_static_type,dispatch_kind,origin,"
                + "resolution_status,producer_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement siteInsert = connection.prepareStatement(siteSql)) {
            for (SiteInsert insert : inserts) {
                SiteKey site = insert.site();
                SiteGroup group = insert.group();
                List<Target> targets = group.targets();
                int i = 1;
                siteInsert.setBytes(i++, insert.stableHash());
                siteInsert.setString(i++, site.callerId); siteInsert.setString(i++, site.sourceFile);
                siteInsert.setInt(i++, site.beginLine); siteInsert.setInt(i++, site.beginColumn);
                siteInsert.setInt(i++, site.endLine); siteInsert.setInt(i++, site.endColumn);
                siteInsert.setInt(i++, site.ordinal); siteInsert.setString(i++, group.syntaxTarget);
                siteInsert.setString(i++, group.receiverStaticType); siteInsert.setString(i++, group.dispatchKind);
                siteInsert.setString(i++, "extracted"); siteInsert.setString(i++, resolutionStatus(targets));
                siteInsert.setString(i, site.producerId);
                siteInsert.addBatch();
            }
            siteInsert.executeBatch();
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_call_sites_caller_order "
                    + "ON call_sites(caller_id,source_file,begin_line,begin_column,ordinal)");
            statement.executeUpdate("INSERT OR IGNORE INTO call_site_targets(call_site_pk,target_id,"
                    + "external_target_fqn,resolution_status,confidence,producer_id) "
                    + "SELECT cs.site_pk,e.target_id,e.external_target_fqn,"
                    + "CASE max(CASE e.confidence WHEN 'EXTRACTED' THEN 3 WHEN 'INFERRED' THEN 2 "
                    + "WHEN 'AMBIGUOUS' THEN 1 ELSE 0 END) WHEN 1 THEN 'ambiguous' "
                    + "WHEN 2 THEN 'heuristic' ELSE 'exact' END,"
                    + "CASE max(CASE e.confidence WHEN 'EXTRACTED' THEN 3 WHEN 'INFERRED' THEN 2 "
                    + "WHEN 'AMBIGUOUS' THEN 1 ELSE 0 END) WHEN 3 THEN 'EXTRACTED' "
                    + "WHEN 2 THEN 'INFERRED' WHEN 1 THEN 'AMBIGUOUS' ELSE 'EXTRACTED' END,"
                    + "cs.producer_id FROM edges e JOIN call_sites cs ON cs.caller_id=e.source_id "
                    + "AND cs.source_file=e.source_file AND cs.begin_line=e.begin_line "
                    + "AND cs.begin_column=e.begin_column AND cs.end_line=e.end_line "
                    + "AND cs.end_column=e.end_column AND cs.ordinal=COALESCE(e.source_ordinal,0) "
                    + "AND cs.producer_id=e.producer_id WHERE e.relation='CALLS' "
                    + "AND NOT EXISTS (SELECT 1 FROM call_site_targets existing "
                    + "WHERE existing.call_site_pk=cs.site_pk) GROUP BY cs.site_pk,e.target_id,"
                    + "e.external_target_fqn");
        }
    }

    private static Map<SiteKey, SiteGroup> readSites(Connection connection, Filter filter)
            throws SQLException {
        String sql = "SELECT e.source_id,src.symbol_id,src.module,src.scope,e.source_file,"
                + "e.begin_line,e.begin_column,e.end_line,e.end_column,COALESCE(e.source_ordinal,0),"
                + "e.syntax_target,e.receiver_static_type,e.call_kind,e.producer_id,e.target_id,"
                + "e.external_target_fqn,e.confidence FROM edges e JOIN nodes src ON src.id=e.source_id "
                + "WHERE e.relation='CALLS' AND e.begin_line IS NOT NULL AND e.begin_column IS NOT NULL "
                + "AND e.end_line IS NOT NULL AND e.end_column IS NOT NULL "
                + (filter.empty() ? "" : "AND (" + filter.edgeClause() + ") ")
                + "ORDER BY e.source_file,e.begin_line,e.begin_column,e.end_line,e.end_column,e.id";
        Map<SiteKey, SiteGroup> sites = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, filter.args());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SiteKey site = new SiteKey(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getInt(6), rows.getInt(7),
                            rows.getInt(8), rows.getInt(9), rows.getInt(10), rows.getString(14));
                    SiteGroup group = sites.get(site);
                    if (group == null) {
                        group = new SiteGroup(rows.getString(11), rows.getString(12), rows.getString(13));
                        sites.put(site, group);
                    }
                    group.addTarget(new Target(rows.getString(15), rows.getString(16),
                            rows.getString(17), rows.getString(14)));
                }
            }
        }
        return sites;
    }

    private static byte[] stableHash(SiteKey site, MessageDigest digest) {
        String canonical = String.join("\n", value(site.module), value(site.scope),
                value(site.sourceFile), value(site.callerSymbol), String.valueOf(site.beginLine),
                String.valueOf(site.beginColumn), String.valueOf(site.endLine),
                String.valueOf(site.endColumn), String.valueOf(site.ordinal), value(site.producerId));
        return digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static String resolutionStatus(List<Target> targets) {
        if (targets.size() > 1 || targets.stream().anyMatch(t -> "AMBIGUOUS".equals(t.confidence))) {
            return "ambiguous";
        }
        return targets.stream().anyMatch(t -> "INFERRED".equals(t.confidence)) ? "heuristic" : "exact";
    }

    private static void bind(PreparedStatement statement, List<String> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) statement.setString(i + 1, args.get(i));
    }

    private static String qmarks(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }

    private static String value(String value) { return value == null ? "" : value; }

    record AffectedScope(Set<String> callerIds, List<String> sourceFiles, Set<String> producerIds) {}

    private record Filter(String callSiteClause, String edgeClause, List<String> args) {
        static Filter all() { return new Filter("", "", List.of()); }

        static Filter of(Set<String> callers, List<String> files, Set<String> producers) {
            List<String> site = new ArrayList<>();
            List<String> edge = new ArrayList<>();
            List<String> args = new ArrayList<>();
            if (callers != null && !callers.isEmpty()) {
                String marks = qmarks(callers.size());
                site.add("caller_id IN (" + marks + ")");
                edge.add("e.source_id IN (" + marks + ")");
                args.addAll(callers);
            }
            if (files != null && !files.isEmpty()) {
                String marks = qmarks(files.size());
                site.add("source_file IN (" + marks + ")");
                edge.add("e.source_file IN (" + marks + ")");
                args.addAll(files);
            }
            if (producers != null && !producers.isEmpty()) {
                String marks = qmarks(producers.size());
                site.add("producer_id IN (" + marks + ")");
                edge.add("e.producer_id IN (" + marks + ")");
                args.addAll(producers);
            }
            return new Filter(String.join(" OR ", site), String.join(" OR ", edge), List.copyOf(args));
        }

        boolean empty() { return callSiteClause.isEmpty(); }
    }

    private record SiteKey(String callerId, String callerSymbol, String module, String scope,
                           String sourceFile, int beginLine, int beginColumn, int endLine,
                           int endColumn, int ordinal, String producerId) {}

    private record SiteInsert(SiteKey site, SiteGroup group, byte[] stableHash) {}

    private static final class SiteGroup {
        private final String syntaxTarget;
        private final String receiverStaticType;
        private final String dispatchKind;
        private final Map<String, Target> targets = new LinkedHashMap<>();

        private SiteGroup(String syntaxTarget, String receiverStaticType, String dispatchKind) {
            this.syntaxTarget = syntaxTarget;
            this.receiverStaticType = receiverStaticType;
            this.dispatchKind = dispatchKind;
        }

        private void addTarget(Target target) {
            String key = value(target.targetId) + "\n" + value(target.externalTarget);
            targets.merge(key, target, SiteGroup::moreCertain);
        }

        private List<Target> targets() { return new ArrayList<>(targets.values()); }

        private static Target moreCertain(Target left, Target right) {
            return confidenceRank(right.confidence) > confidenceRank(left.confidence) ? right : left;
        }

        private static int confidenceRank(String confidence) {
            if ("EXTRACTED".equals(confidence)) return 3;
            if ("INFERRED".equals(confidence)) return 2;
            if ("AMBIGUOUS".equals(confidence)) return 1;
            return 0;
        }
    }

    private record Target(String targetId, String externalTarget, String confidence,
                          String producerId) {
        String resolutionStatus() {
            if ("AMBIGUOUS".equals(confidence)) return "ambiguous";
            if ("INFERRED".equals(confidence)) return "heuristic";
            return "exact";
        }
    }
}
