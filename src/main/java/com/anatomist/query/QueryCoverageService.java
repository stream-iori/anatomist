package com.anatomist.query;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.json.Json;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Converts persisted analysis gaps into query-specific evidence disclosure. */
public final class QueryCoverageService {

    public enum Capability {
        DECLARATION,
        CALL_OUTGOING,
        CALL_INCOMING,
        CALL_PATH,
        TYPE_OUTGOING,
        TYPE_INCOMING,
        REFERENCE_OUTGOING,
        REFERENCE_INCOMING,
        FIELD_ACCESS,
        WIRING,
        FLOW,
        AGGREGATE
    }

    private final Connection connection;

    public QueryCoverageService(Connection connection) {
        this.connection = connection;
    }

    public QueryEvidence assess(Capability capability,
                                List<String> anchors,
                                String module,
                                String scope,
                                boolean positive,
                                boolean aggregate) {
        Set<String> anchorFiles = isOutgoing(capability)
                ? resolveAnchorFiles(anchors) : Set.of();
        List<CoverageGap> coverageGaps = readCoverage(capability).stream()
                .filter(gap -> matchesSelection(gap, module, scope))
                .filter(gap -> !isOutgoing(capability)
                        || "*".equals(gap.sourceFile())
                        || anchorFiles.isEmpty()
                        || anchorFiles.stream().anyMatch(
                                anchorFile -> sameSource(anchorFile, gap.sourceFile())))
                .toList();

        boolean partial = !coverageGaps.isEmpty();
        Map<String, Long> counts = new java.util.TreeMap<>();
        coverageGaps.forEach(gap -> gap.codeCounts().forEach(
                (code, count) -> counts.merge(code, count, Long::sum)));
        List<String> dimensions = counts.keySet().stream()
                .map(QueryCoverageService::dimensionOfCode)
                .distinct().sorted().toList();
        String status;
        String code = null;
        if (aggregate && partial) {
            status = "partial_aggregate";
        } else if (positive) {
            status = "positive";
        } else if (partial) {
            status = "indeterminate";
            code = "QUERY_COVERAGE_INCOMPLETE";
        } else {
            status = "confirmed_empty";
        }
        String firstCode = counts.isEmpty() ? null : counts.keySet().iterator().next();
        String diagnosticQuery = firstCode == null ? null
                : "anatomist doctor --format json --diagnostic-code "
                + firstCode + " --index <same-index>";
        return new QueryEvidence(
                status,
                partial ? "partial" : "complete",
                !partial,
                code,
                dimensions,
                new LinkedHashMap<>(counts),
                diagnosticQuery);
    }

    private List<CoverageGap> readCoverage(Capability capability) {
        List<CoverageGap> out = new ArrayList<>();
        String sql = "SELECT source_file,module,scope,status,code_counts,details_truncated"
                + " FROM analysis_coverage WHERE capability=?"
                + " ORDER BY source_file,module,scope";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, capability.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    out.add(new CoverageGap(
                            rows.getString(1), rows.getString(2), rows.getString(3),
                            rows.getString(4), parseCodeCounts(rows.getString(5)),
                            rows.getInt(6) != 0));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read analysis coverage", e);
        }
        return out;
    }

    private static Map<String, Long> parseCodeCounts(String json) {
        Map<String, Long> out = new TreeMap<>();
        if (json == null) return out;
        Object tree = Json.parseTree(json);
        if (tree instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null && value instanceof Number count) {
                    out.put(String.valueOf(key), count.longValue());
                }
            });
        }
        return out;
    }

    private List<IndexDiagnostic> readDiagnostics() {
        List<IndexDiagnostic> out = new ArrayList<>();
        String sql = "SELECT severity,code,phase,source_file,module,scope,symbol,"
                + "occurrence_count,sample FROM index_diagnostics";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                out.add(new IndexDiagnostic(
                        rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getString(6),
                        rows.getString(7), rows.getLong(8), rows.getString(9)));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read query coverage diagnostics", e);
        }
        return out;
    }

    private Set<String> resolveAnchorFiles(List<String> anchors) {
        if (anchors == null || anchors.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        String sql = "SELECT DISTINCT source_file FROM nodes WHERE id=? OR symbol_id=?"
                + " OR qualified_name=? OR label=? OR symbol_id LIKE ? OR qualified_name LIKE ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String anchor : anchors) {
                if (anchor == null || anchor.isBlank()) continue;
                for (int i = 1; i <= 4; i++) statement.setString(i, anchor);
                statement.setString(5, anchor + "%");
                statement.setString(6, anchor + "%");
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String source = rows.getString(1);
                        if (source != null) out.add(source);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve query coverage anchors", e);
        }
        return out;
    }

    private static boolean relevantToCapability(IndexDiagnostic diagnostic,
                                                Capability capability) {
        String code = upper(diagnostic.code());
        String phase = upper(diagnostic.phase());
        if ("UNRESOLVED_SYMBOLS".equals(code)) return false;
        if ("JAVA_PARSE_FAILED".equals(code)) return true;
        if ("DANGLING_FACTS_DROPPED".equals(code)) return true;
        if ("DIAGNOSTIC_STORAGE_TRUNCATED".equals(code)) {
            return capability != Capability.DECLARATION;
        }
        if (code.startsWith("CLASSPATH_")) {
            return capability != Capability.DECLARATION;
        }
        return switch (capability) {
            case DECLARATION -> false;
            case CALL_OUTGOING, CALL_INCOMING, CALL_PATH ->
                    phase.contains("CALL_GRAPH") || phase.contains("METHOD");
            case TYPE_OUTGOING, TYPE_INCOMING ->
                    phase.contains("HIERARCHY") || phase.contains("REFERENCE");
            case REFERENCE_OUTGOING, REFERENCE_INCOMING ->
                    phase.contains("REFERENCE") || phase.contains("METHOD")
                            || phase.contains("FIELD");
            case FIELD_ACCESS -> phase.contains("FIELD_ACCESS");
            case WIRING -> phase.contains("ANNOTATION") || phase.contains("REFERENCE");
            case FLOW -> phase.contains("FLOW") || phase.contains("CALL_GRAPH")
                    || phase.contains("METHOD");
            case AGGREGATE -> isResolutionFailure(diagnostic);
        };
    }

    private static boolean matchesSelection(IndexDiagnostic diagnostic,
                                            String module,
                                            String scope) {
        if (module != null && !module.isBlank()
                && diagnostic.module() != null
                && !module.equals(diagnostic.module())) {
            return false;
        }
        return scope == null || scope.isBlank() || "ALL".equalsIgnoreCase(scope)
                || diagnostic.scope() == null
                || scope.equalsIgnoreCase(diagnostic.scope());
    }

    private static boolean matchesSelection(CoverageGap gap,
                                            String module,
                                            String scope) {
        if (module != null && !module.isBlank()
                && !"*".equals(gap.module())
                && !module.equals(gap.module())) {
            return false;
        }
        return scope == null || scope.isBlank() || "ALL".equalsIgnoreCase(scope)
                || "*".equals(gap.scope())
                || scope.equalsIgnoreCase(gap.scope());
    }

    private static boolean isOutgoing(Capability capability) {
        return switch (capability) {
            case CALL_OUTGOING, TYPE_OUTGOING, REFERENCE_OUTGOING, FLOW -> true;
            default -> false;
        };
    }

    private static boolean isResolutionFailure(IndexDiagnostic diagnostic) {
        String code = upper(diagnostic.code());
        String phase = upper(diagnostic.phase());
        return phase.contains("RESOLUTION") || phase.contains("CALL_GRAPH")
                || phase.contains("HIERARCHY") || phase.contains("REFERENCE")
                || phase.contains("METHOD") || phase.contains("FIELD")
                || code.endsWith("_NOT_FOUND") || code.endsWith("_RESOLUTION")
                || code.contains("INFERENCE") || code.contains("OVERLOAD")
                || code.contains("SYMBOL_MISSING") || code.startsWith("CLASSPATH_");
    }

    private static String dimensionOf(IndexDiagnostic diagnostic) {
        return dimensionOfCode(diagnostic.code());
    }

    private static String dimensionOfCode(String code) {
        return switch (upper(code)) {
            case "JAVA_PARSE_FAILED" -> "parse";
            case "INTERNAL_SYMBOL_MISSING" -> "internal_resolution";
            case "THIRDPARTY_SYMBOL_MISSING", "CLASSPATH_PARTIAL",
                 "CLASSPATH_UNAVAILABLE" -> "external_resolution";
            case "JDK_SYMBOL_MISMATCH" -> "jdk_resolution";
            case "DANGLING_FACTS_DROPPED" -> "graph_integrity";
            default -> "other_resolution";
        };
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static boolean sameSource(String left, String right) {
        if (left == null || right == null) return false;
        String normalizedLeft = left.replace('\\', '/');
        String normalizedRight = right.replace('\\', '/');
        return normalizedLeft.equals(normalizedRight)
                || normalizedLeft.endsWith("/" + normalizedRight)
                || normalizedRight.endsWith("/" + normalizedLeft);
    }

    private record CoverageGap(String sourceFile,
                               String module,
                               String scope,
                               String status,
                               Map<String, Long> codeCounts,
                               boolean detailsTruncated) {}
}
