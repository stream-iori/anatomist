package com.anatomist.cli;

import com.anatomist.query.JsonFormatter;
import com.anatomist.query.MarkdownFormatter;
import com.anatomist.query.OverviewResult;
import com.anatomist.query.PackageStat;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryCoverageService;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "overview",
        mixinStandardHelpOptions = true,
        description = "Top-down project summary: node-kind counts, edge counts, "
                    + "per-package type/method tallies, and the package dependency skeleton.")
public class OverviewCommand implements Callable<Integer> {

    @Option(names = "--format", description = "Output format: markdown | json (default: markdown).")
    String format = "markdown";

    @Option(names = "--depth",
            description = "Collapse package tree to the first N dot-segments (default: 0 = no collapse).")
    int depth = 0;

    @Option(names = "--deps-only",
            description = "Output only package dependency edges (replaces package-deps command).")
    boolean depsOnly;

    @Option(names = "--limit", description = "Max package-deps results (default 30, 0=all).") int limit = 30;
    @Option(names = "--offset", description = "Skip N package-deps results.") int offset = 0;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/indexes/<repo-key>/index.db).")
    Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            if (depsOnly) {
                List<Map<String, Object>> rows = q.packageDeps();
                int total = rows.size();
                int effectiveLimit = limit > 0 ? limit : total;
                int safeOffset = Math.max(0, Math.min(offset, total));
                int end = Math.min(safeOffset + effectiveLimit, total);
                List<Map<String, Object>> page = rows.subList(safeOffset, end);
                QueryEnvelope env = new QueryEnvelope(buildQueryString(), page);
                env.stats.put("total", total);
                env.stats.put("offset", safeOffset);
                env.stats.put("truncated", end < total);
                if (end < total) {
                    env.stats.put("limit", effectiveLimit);
                    env.stats.put("next_offset", end);
                    env.nextQueries = List.of("overview --deps-only --limit " + effectiveLimit
                            + " --offset " + end);
                    Disclosure.putBudget(env, "package_deps", page.size(), total);
                }
                env.evidence.putAll(new QueryCoverageService(q.connection()).assess(
                        QueryCoverageService.Capability.AGGREGATE,
                        List.of(), null, "MAIN", total > 0, true).toMap());
                JsonFormatter.emit(System.out, env);
                return 0;
            }
            OverviewResult ov = q.overview();
            if (depth > 0) ov.packages = collapse(ov.packages, depth);
            if ("json".equalsIgnoreCase(format)) {
                QueryEnvelope env = new QueryEnvelope(buildQueryString(), List.of(ov));
                env.stats.clear();
                env.stats.putAll(ov.toStats());
                env.evidence.putAll(new QueryCoverageService(q.connection()).assess(
                        QueryCoverageService.Capability.AGGREGATE,
                        List.of(), null, "MAIN", true, true).toMap());
                JsonFormatter.emit(System.out, env);
            } else {
                System.out.print(MarkdownFormatter.format(ov));
            }
            return 0;
        }
    }

    /** Fold packages to their first {@code n} dot-segments, summing tallies. */
    static List<PackageStat> collapse(List<PackageStat> packages, int n) {
        LinkedHashMap<String, PackageStat> byPrefix = new LinkedHashMap<>();
        for (PackageStat p : packages) {
            String prefix = prefix(p.name, n);
            PackageStat agg = byPrefix.computeIfAbsent(prefix, PackageStat::new);
            agg.types += p.types;
            agg.methods += p.methods;
        }
        return new ArrayList<>(byPrefix.values());
    }

    private static String prefix(String pkg, int n) {
        if (pkg == null || pkg.isEmpty()) return pkg;
        String[] parts = pkg.split("\\.");
        if (parts.length <= n) return pkg;
        return String.join(".", Arrays.copyOfRange(parts, 0, n));
    }

    private String buildQueryString() {
        if (depsOnly) return "overview --deps-only";
        StringBuilder sb = new StringBuilder("overview --format ").append(format);
        if (depth > 0) sb.append(" --depth ").append(depth);
        return sb.toString();
    }
}
