package com.anatomist.cli;

import com.anatomist.query.JsonFormatter;
import com.anatomist.query.MarkdownFormatter;
import com.anatomist.query.OverviewResult;
import com.anatomist.query.PackageStat;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "overview",
        description = "Top-down project summary: node-kind counts, edge counts, "
                    + "per-package type/method tallies, and the package dependency skeleton.")
public class OverviewCommand implements Callable<Integer> {

    @Option(names = "--format", description = "Output format: markdown | json (default: markdown).")
    String format = "markdown";

    @Option(names = "--depth",
            description = "Collapse package tree to the first N dot-segments (default: 0 = no collapse).")
    int depth = 0;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            OverviewResult ov = q.overview();
            if (depth > 0) ov.packages = collapse(ov.packages, depth);
            if ("json".equalsIgnoreCase(format)) {
                QueryEnvelope env = new QueryEnvelope(buildQueryString(), List.of(ov));
                env.stats.clear();
                env.stats.putAll(ov.toStats());
                JsonFormatter.emit(System.out, env);
            } else {
                System.out.print(MarkdownFormatter.format(ov));
            }
            return 0;
        }
    }

    /** Fold packages to their first {@code n} dot-segments, summing tallies. */
    static List<PackageStat> collapse(List<PackageStat> packages, int n) {
        java.util.LinkedHashMap<String, PackageStat> byPrefix = new java.util.LinkedHashMap<>();
        for (PackageStat p : packages) {
            String prefix = prefix(p.name, n);
            PackageStat agg = byPrefix.computeIfAbsent(prefix, PackageStat::new);
            agg.types += p.types;
            agg.methods += p.methods;
        }
        return new java.util.ArrayList<>(byPrefix.values());
    }

    private static String prefix(String pkg, int n) {
        if (pkg == null || pkg.isEmpty()) return pkg;
        String[] parts = pkg.split("\\.");
        if (parts.length <= n) return pkg;
        return String.join(".", java.util.Arrays.copyOfRange(parts, 0, n));
    }

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder("overview --format ").append(format);
        if (depth > 0) sb.append(" --depth ").append(depth);
        return sb.toString();
    }
}
