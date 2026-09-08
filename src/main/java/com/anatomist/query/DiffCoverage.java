package com.anatomist.query;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Coverage belongs to a selected capability, never to a whole diff implicitly. */
final class DiffCoverage {
    private record Root(String module, String scope, String path) {}
    private final Connection connection;
    private final Map<String,String> metadata;
    private final Set<String> manifest;
    private final List<Root> roots = new ArrayList<>();
    private final Set<String> scopes = new TreeSet<>();
    private boolean validLayout = true;

    DiffCoverage(Connection connection, Map<String,String> metadata, Set<String> manifest) {
        this.connection = connection; this.metadata = metadata; this.manifest = manifest;
        for (String line : metadata.getOrDefault("scan_policy", "").lines().toList()) {
            if (line.startsWith("scope=")) scopes.add(line.substring(6));
        }
        for (String line : metadata.getOrDefault("source_layout", "").lines().toList()) {
            int at = line.indexOf('@'), eq = line.indexOf('=', at + 1);
            if (at < 0 || eq < 0) { validLayout = false; continue; }
            try {
                Path project = Path.of(metadata.getOrDefault("source_root", ""));
                String path = project.relativize(Path.of(line.substring(eq + 1))).toString().replace('\\','/');
                roots.add(new Root(line.substring(0, at), line.substring(at + 1, eq), path));
                scopes.add(line.substring(at + 1, eq));
            } catch (IllegalArgumentException ignored) { validLayout = false; }
        }
    }

    boolean known() { return metadata.getOrDefault("scan_policy", "").startsWith("anatomist-scan-policy-v1\n")
            && validLayout && metadata.containsKey("source_layout") && metadata.containsKey("source_root"); }
    boolean hasModule(String module) { return roots.stream().anyMatch(r -> r.module().equals(module)); }

    Set<String> reasons(String scope, String module, String capability) throws SQLException {
        Set<String> reasons = new TreeSet<>();
        if (!known()) reasons.add("SCAN_COVERAGE_UNKNOWN");
        if (!scope.equals("ALL") && !scopes.contains(scope)) reasons.add("SCOPE_NOT_INDEXED");
        Set<String> indexed = new HashSet<>();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT source_file FROM file_cache")) {
            while (r.next()) indexed.add(r.getString(1));
        }
        for (String file : manifest) {
            if (!file.endsWith(".java") || indexed.contains(file)) continue;
            var matching = roots.stream().filter(r -> r.path().isEmpty() || file.startsWith(r.path() + "/"))
                    .max(Comparator.comparingInt(r -> r.path().length()));
            if (matching.isPresent()) {
                Root root = matching.get();
                if ((scope.equals("ALL") || scope.equals(root.scope())) && (module == null || module.equals(root.module())))
                    reasons.add("SOURCE_NOT_INDEXED");
            } else {
                String hint = file.contains("src/test/java/") ? "TEST" : file.contains("src/main/java/") ? "MAIN" : null;
                if (scope.equals("ALL") || hint == null || scope.equals(hint)) reasons.add("SOURCE_SCOPE_UNKNOWN");
            }
        }
        if (capability.equals("declarations")) {
            try (PreparedStatement s = connection.prepareStatement("SELECT 1 FROM nodes WHERE declaration_kind IS NULL "
                    + "AND kind IN ('CLASS','INTERFACE','ENUM','ANNOTATION','RECORD','METHOD','CONSTRUCTOR') "
                    + "AND (?='ALL' OR scope=?) AND (? IS NULL OR module=?) LIMIT 1")) {
                s.setString(1,scope);s.setString(2,scope);s.setString(3,module);s.setString(4,module);
                try (ResultSet r = s.executeQuery()) { if (r.next()) reasons.add("DECLARATION_METADATA_INCOMPLETE"); }
            }
        }
        String filter = capability.equals("declarations") ? "capability='DECLARATION'"
                : capability.equals("impact") ? "capability IN ('CALL_INCOMING','CALL_OUTGOING','CALL_PATH')"
                : "capability<>'DECLARATION'";
        try (PreparedStatement s = connection.prepareStatement("SELECT DISTINCT capability FROM analysis_coverage WHERE status<>'complete' AND "
                + filter + " AND (?='ALL' OR scope=? OR scope IS NULL OR scope='*') AND (? IS NULL OR module=? OR module IS NULL OR module='*')")) {
            s.setString(1,scope); s.setString(2,scope); s.setString(3,module); s.setString(4,module);
            try (ResultSet r = s.executeQuery()) { while (r.next()) reasons.add("INCOMPLETE_" + r.getString(1)); }
        }
        try (PreparedStatement s = connection.prepareStatement("SELECT DISTINCT code FROM index_diagnostics WHERE severity IN ('warning','error') "
                + "AND (?='ALL' OR scope=? OR scope IS NULL OR scope='*') AND (? IS NULL OR module=? OR module IS NULL OR module='*')")) {
            s.setString(1,scope); s.setString(2,scope); s.setString(3,module); s.setString(4,module);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String code = r.getString(1);
                    // Resolution warnings limit relationships, but do not erase valid text navigation.
                    if (!capability.equals("declarations") || code.contains("PARSE") || code.contains("SOURCE") || code.contains("SCAN"))
                        reasons.add(code);
                }
            }
        }
        return reasons;
    }

    static Map<String,Object> evidence(Set<String> reasons, boolean environmentChanged, boolean truncated) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("status", reasons.isEmpty() && !truncated ? "complete" : "partial");
        out.put("complete", reasons.isEmpty() && !truncated);
        out.put("truncated", truncated);
        out.put("negative_conclusion_safe", reasons.isEmpty() && !environmentChanged && !truncated);
        var all = new TreeSet<>(reasons);
        if (environmentChanged) all.add("ENVIRONMENT_CHANGED");
        out.put("reasons", List.copyOf(all));
        return out;
    }
}
