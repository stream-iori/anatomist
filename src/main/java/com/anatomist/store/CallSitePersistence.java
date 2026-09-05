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

/** Persists canonical call-site/target facts from staged source CALLS facts. */
final class CallSitePersistence {
    private CallSitePersistence() {}

    static void rebuild(Connection connection) throws SQLException {
        rebuild(connection, readSites(connection, Filter.all(), null));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM edges WHERE relation='CALLS'");
        }
    }

    static void rebuildFromStage(Connection connection, String stageAlias) throws SQLException {
        rebuild(connection, readSites(connection, Filter.all(), stageAlias));
    }

    private static void rebuild(Connection connection, Map<SiteKey, SiteGroup> sites)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_sites_caller_order");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_site_owners_source");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_site_targets_internal");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_site_targets_external");
            statement.executeUpdate("DROP INDEX IF EXISTS idx_call_site_targets_identity");
            statement.executeUpdate("DELETE FROM call_site_targets");
            statement.executeUpdate("DELETE FROM call_sites");
            statement.executeUpdate("DELETE FROM call_site_owners");
        }
        insert(connection, sites);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_call_sites_caller_order ON call_sites("
                    + "owner_pk,begin_line,begin_column,ordinal)");
            statement.executeUpdate("CREATE INDEX idx_call_site_owners_source ON call_site_owners("
                    + "source_file,caller_id)");
            statement.executeUpdate("CREATE INDEX idx_call_site_targets_internal "
                    + "ON call_site_targets(target_id) WHERE target_id IS NOT NULL");
            statement.executeUpdate("CREATE INDEX idx_call_site_targets_external "
                    + "ON call_site_targets(external_target_fqn) WHERE external_target_fqn IS NOT NULL");
            statement.executeUpdate("CREATE UNIQUE INDEX idx_call_site_targets_identity "
                    + "ON call_site_targets(call_site_pk,COALESCE(target_id,''),"
                    + "COALESCE(external_target_fqn,''))");
        }
    }

    /** Captures callers whose persisted sites can change with files or project producers. */
    static AffectedScope captureAffected(Connection connection, List<String> sourceFiles,
                                         Set<String> producerIds) throws SQLException {
        List<String> files = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
        Set<String> producers = producerIds == null ? Set.of() : Set.copyOf(producerIds);
        if (files.isEmpty() && producers.isEmpty()) return new AffectedScope(Set.of(), files, producers);

        List<String> predicates = new ArrayList<>();
        List<String> args = new ArrayList<>();
        if (!files.isEmpty()) {
            predicates.add("cso.source_file IN (" + qmarks(files.size()) + ")");
            args.addAll(files);
        }
        if (!producers.isEmpty()) {
            predicates.add("cs.producer_id IN (" + qmarks(producers.size()) + ")");
            args.addAll(producers);
            predicates.add("cst.producer_id IN (" + qmarks(producers.size()) + ")");
            args.addAll(producers);
        }
        String sql = "SELECT DISTINCT cso.caller_id FROM call_sites cs "
                + "JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "LEFT JOIN call_site_targets cst ON cst.call_site_pk=cs.site_pk WHERE "
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
        refresh(connection, null, scope);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM edges WHERE relation='CALLS'");
        }
    }

    static void refreshFromStage(Connection connection, String stageAlias, AffectedScope scope)
            throws SQLException {
        refresh(connection, stageAlias, scope);
    }

    private static void refresh(Connection connection, String stageAlias, AffectedScope scope)
            throws SQLException {
        Filter filter = Filter.of(scope.callerIds(), scope.sourceFiles(), scope.producerIds());
        if (filter.empty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM call_sites WHERE site_pk IN (SELECT cs.site_pk FROM call_sites cs "
                        + "JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk WHERE "
                        + filter.callSiteClause() + ")")) {
            bind(statement, filter.args());
            statement.executeUpdate();
        }
        insert(connection, readSites(connection, filter, stageAlias));
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM call_site_owners WHERE NOT EXISTS "
                    + "(SELECT 1 FROM call_sites cs WHERE cs.owner_pk=call_site_owners.owner_pk)");
        }
    }

    private static void insert(Connection connection, Map<SiteKey, SiteGroup> sites)
            throws SQLException {
        if (sites.isEmpty()) return;
        MessageDigest digest = sha256();
        List<SiteInsert> inserts = sites.entrySet().stream()
                .map(entry -> new SiteInsert(entry.getKey(), entry.getValue(),
                        stableHash(entry.getKey(), digest)))
                .toList();
        Map<OwnerKey, Long> owners = ensureOwners(connection, inserts);
        String siteSql = "INSERT INTO call_sites(stable_hash,owner_pk,begin_line,begin_column,"
                + "end_line,end_column,ordinal,context,syntax_target,receiver_static_type,dispatch_kind,metadata,origin,"
                + "resolution_status,producer_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement siteInsert = connection.prepareStatement(siteSql)) {
            for (SiteInsert insert : inserts) {
                SiteKey site = insert.site();
                SiteGroup group = insert.group();
                List<Target> targets = group.targets();
                int i = 1;
                siteInsert.setBytes(i++, insert.stableHash());
                siteInsert.setLong(i++, owners.get(new OwnerKey(site.callerId, site.sourceFile)));
                siteInsert.setInt(i++, site.beginLine); siteInsert.setInt(i++, site.beginColumn);
                siteInsert.setInt(i++, site.endLine); siteInsert.setInt(i++, site.endColumn);
                siteInsert.setInt(i++, site.ordinal); siteInsert.setString(i++, group.context);
                siteInsert.setString(i++, group.syntaxTarget);
                siteInsert.setString(i++, group.receiverStaticType); siteInsert.setString(i++, group.dispatchKind);
                siteInsert.setString(i++, group.metadata);
                siteInsert.setString(i++, "extracted"); siteInsert.setString(i++, resolutionStatus(targets));
                siteInsert.setString(i, site.producerId);
                siteInsert.addBatch();
            }
            siteInsert.executeBatch();
        }
        String targetSql = "INSERT OR IGNORE INTO call_site_targets(call_site_pk,target_id,"
                + "external_target_fqn,resolution_status,confidence,producer_id) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement findSite = connection.prepareStatement(
                    "SELECT site_pk FROM call_sites WHERE stable_hash=?");
             PreparedStatement targetInsert = connection.prepareStatement(targetSql)) {
            for (SiteInsert site : inserts) {
                findSite.setBytes(1, site.stableHash());
                long sitePk;
                try (ResultSet row = findSite.executeQuery()) {
                    if (!row.next()) throw new SQLException("inserted call site not found");
                    sitePk = row.getLong(1);
                }
                for (Target target : site.group().targets()) {
                    targetInsert.setLong(1, sitePk);
                    targetInsert.setString(2, target.targetId());
                    targetInsert.setString(3, target.externalTarget());
                    targetInsert.setString(4, target.resolutionStatus());
                    targetInsert.setString(5, target.confidence());
                    targetInsert.setString(6, target.producerId());
                    targetInsert.addBatch();
                }
            }
            targetInsert.executeBatch();
        }
    }

    private static Map<OwnerKey, Long> ensureOwners(Connection connection, List<SiteInsert> sites)
            throws SQLException {
        Set<OwnerKey> keys = new LinkedHashSet<>();
        for (SiteInsert insert : sites) {
            keys.add(new OwnerKey(insert.site().callerId(), insert.site().sourceFile()));
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO call_site_owners(caller_id,source_file) VALUES(?,?)")) {
            for (OwnerKey key : keys) {
                statement.setString(1, key.callerId());
                statement.setString(2, key.sourceFile());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        Map<OwnerKey, Long> owners = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT owner_pk FROM call_site_owners WHERE caller_id=? AND source_file=?")) {
            for (OwnerKey key : keys) {
                statement.setString(1, key.callerId());
                statement.setString(2, key.sourceFile());
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) throw new SQLException("call-site owner not found");
                    owners.put(key, row.getLong(1));
                }
            }
        }
        return owners;
    }

    private static Map<SiteKey, SiteGroup> readSites(Connection connection, Filter filter,
                                                     String stageAlias)
            throws SQLException {
        boolean staged = stageAlias != null;
        String table = staged ? stageAlias + ".stage_edges" : "edges";
        String source = staged ? "e.resolved_source" : "e.source_id";
        String target = staged ? "e.resolved_target" : "e.target_id";
        String edgeFilter = staged
                ? filter.edgeClause().replace("e.source_id", "e.resolved_source")
                : filter.edgeClause();
        String sql = "SELECT " + source + ",src.symbol_id,src.module,src.scope,e.source_file,"
                + "e.begin_line,e.begin_column,e.end_line,e.end_column,COALESCE(e.source_ordinal,0),"
                + "e.context,e.syntax_target,e.receiver_static_type,e.call_kind,e.metadata,e.producer_id," + target + ","
                + "e.external_target_fqn,e.confidence FROM " + table + " e JOIN nodes src ON src.id=" + source + " "
                + "WHERE e.relation='CALLS' AND e.begin_line IS NOT NULL AND e.begin_column IS NOT NULL "
                + "AND e.end_line IS NOT NULL AND e.end_column IS NOT NULL "
                + (filter.empty() ? "" : "AND (" + edgeFilter + ") ")
                + "ORDER BY e.source_file,e.begin_line,e.begin_column,e.end_line,e.end_column,"
                + (staged ? "e.seq" : "e.id");
        Map<SiteKey, SiteGroup> sites = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, filter.args());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    SiteKey site = new SiteKey(rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getInt(6), rows.getInt(7),
                            rows.getInt(8), rows.getInt(9), rows.getInt(10), rows.getString(16));
                    SiteGroup group = sites.get(site);
                    if (group == null) {
                        group = new SiteGroup(rows.getString(11), rows.getString(12),
                                rows.getString(13), rows.getString(14), rows.getString(15));
                        sites.put(site, group);
                    } else {
                        group.mergeEvidence(rows.getString(11), rows.getString(12),
                                rows.getString(13), rows.getString(14), rows.getString(15));
                    }
                    group.addTarget(new Target(rows.getString(17), rows.getString(18),
                            rows.getString(19), rows.getString(16)));
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
                site.add("cso.caller_id IN (" + marks + ")");
                edge.add("e.source_id IN (" + marks + ")");
                args.addAll(callers);
            }
            if (files != null && !files.isEmpty()) {
                String marks = qmarks(files.size());
                site.add("cso.source_file IN (" + marks + ")");
                edge.add("e.source_file IN (" + marks + ")");
                args.addAll(files);
            }
            if (producers != null && !producers.isEmpty()) {
                String marks = qmarks(producers.size());
                site.add("cs.producer_id IN (" + marks + ")");
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

    private record OwnerKey(String callerId, String sourceFile) {}

    private static final class SiteGroup {
        private String context;
        private String syntaxTarget;
        private String receiverStaticType;
        private String dispatchKind;
        private String metadata;
        private final Map<String, Target> targets = new LinkedHashMap<>();

        private SiteGroup(String context, String syntaxTarget, String receiverStaticType,
                          String dispatchKind, String metadata) {
            this.context = context;
            this.syntaxTarget = syntaxTarget;
            this.receiverStaticType = receiverStaticType;
            this.dispatchKind = dispatchKind;
            this.metadata = metadata;
        }

        private void addTarget(Target target) {
            String key = value(target.targetId) + "\n" + value(target.externalTarget);
            targets.merge(key, target, SiteGroup::moreCertain);
        }

        private void mergeEvidence(String nextContext, String nextSyntaxTarget,
                                   String nextReceiverStaticType, String nextDispatchKind,
                                   String nextMetadata) {
            if (context == null) context = nextContext;
            if (syntaxTarget == null) syntaxTarget = nextSyntaxTarget;
            if (receiverStaticType == null) receiverStaticType = nextReceiverStaticType;
            // An inferred reflection target decorates the same source invocation as the
            // raw Method.invoke/newInstance target. Keep one site, but retain the richer
            // dispatch/evidence instead of whichever staged edge happened to sort first.
            if ("REFLECTION".equals(nextDispatchKind)) {
                dispatchKind = nextDispatchKind;
                if (nextReceiverStaticType != null) receiverStaticType = nextReceiverStaticType;
                if (nextMetadata != null) metadata = nextMetadata;
            } else if (metadata == null && nextMetadata != null) {
                metadata = nextMetadata;
            }
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
