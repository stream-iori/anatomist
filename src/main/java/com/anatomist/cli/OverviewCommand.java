package com.anatomist.cli;

import com.anatomist.query.OverviewResult;
import com.anatomist.query.PackageStat;
import com.anatomist.query.QueryService;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticStreamWriter;
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
        description = "Emit project, package, and package-dependency semantic summaries.",
        footer = "%nAccepts: CLI index selector%nEmits: project_summary | package_summary | package_dependency + evidence%n%nExample:%n  anatomist overview --format ndjson")
public class OverviewCommand implements Callable<Integer> {

    @Option(names = "--format", description = "Semantic projection: ndjson | json | table.")
    String format = "ndjson";

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
        try {
            format = CliValidation.choice("--format", format, "ndjson", "json", "table");
            CliValidation.nonNegative("--depth", depth);
            CliValidation.nonNegative("--limit", limit);
            CliValidation.nonNegative("--offset", offset);
            Path db = IndexPath.resolve(index);
            try (QueryService q = new QueryService(db);
                 SemanticStreamWriter writer = new SemanticStreamWriter(System.out, format)) {
                SemanticIdentity identity = SemanticIdentity.read(q.connection());
                OverviewResult ov = q.overview();
                if (depth > 0) ov.packages = collapse(ov.packages, depth);
                String root = SemanticRecords.rootSeed("overview", identity.sourceSnapshotId());
                int emitted = 0;
                if (!depsOnly) {
                    var project = SemanticRecords.common("project_summary", root, null, identity);
                    project.put("id", "project:sha256:" + SemanticIdentity.sha256(
                            identity.sourceSnapshotId()));
                    project.put("kind_counts", ov.kindCounts);
                    project.put("internal_relation_counts", ov.internalEdgeCounts);
                    project.put("external_relation_counts", ov.externalEdgeCounts);
                    project.put("producer_counts", ov.producerCounts);
                    project.put("totals", ov.toStats());
                    project.put("origin", "derived"); project.put("resolution_status", "exact");
                    writer.write(project); emitted++;
                    for (PackageStat pkg : ov.packages) {
                        String seed = SemanticRecords.childSeed(root, "package", pkg.name);
                        var row = SemanticRecords.common("package_summary", seed, root, identity);
                        row.put("id", "package:sha256:" + SemanticIdentity.sha256(pkg.name));
                        row.put("package", pkg.name); row.put("types", pkg.types);
                        row.put("callables", pkg.methods); row.put("producer_counts", pkg.producerCounts);
                        row.put("origin", "derived"); row.put("resolution_status", "exact");
                        writer.write(row); emitted++;
                    }
                }
                List<Map<String, Object>> deps = ov.packageDeps;
                int effectiveLimit = limit > 0 ? limit : deps.size();
                int start = Math.min(offset, deps.size());
                int end = Math.min(start + effectiveLimit, deps.size());
                for (Map<String, Object> dep : deps.subList(start, end)) {
                    String stable = dep.get("source_package") + "\n" + dep.get("target_package")
                            + "\n" + dep.get("relation");
                    String seed = SemanticRecords.childSeed(root, "package-dependency", stable);
                    var row = SemanticRecords.common("package_dependency", seed, root, identity);
                    row.put("id", "package-dependency:sha256:" + SemanticIdentity.sha256(stable));
                    row.putAll(dep); row.put("origin", "derived"); row.put("resolution_status", "exact");
                    writer.write(row); emitted++;
                }
                boolean complete = end >= deps.size();
                writer.write(SemanticRecords.seedEvidence(root, null, emitted, complete,
                        complete ? null : "RESULT_LIMIT", !complete, identity));
                writer.write(SemanticRecords.streamEvidence(1, emitted, complete, !complete, identity));
                return 0;
            }
        } catch (IllegalArgumentException failure) {
            return CliValidation.emit(failure);
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
