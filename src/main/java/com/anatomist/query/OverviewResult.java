package com.anatomist.query;

import com.anatomist.model.GraphConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Top-down project summary for the {@code overview} command. All counts are
 *  project-internal aggregates over the index; no per-node detail. */
public class OverviewResult {
    /** Node kind → count (CLASS / INTERFACE / METHOD / FIELD / BEAN / ...). */
    public Map<String, Long> kindCounts = new LinkedHashMap<>();
    /** Edge relation → count, split into internal / external buckets. */
    public Map<String, Long> internalEdgeCounts = new LinkedHashMap<>();
    public Map<String, Long> externalEdgeCounts = new LinkedHashMap<>();
    /** Per-package type/method tallies, sorted by package name. */
    public List<PackageStat> packages = new ArrayList<>();
    /** Reuses {@link QueryService#packageDeps()}: (source_package, target_package, relation, edge_count). */
    public List<Map<String, Object>> packageDeps = new ArrayList<>();
    public Map<String, Object> toStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        long types = 0;
        for (Map.Entry<String, Long> e : kindCounts.entrySet()) {
            if (TYPE_KIND(e.getKey())) types += e.getValue();
        }
        s.put("packages", packages.size());
        s.put("types", types);
        s.put("methods", kindCounts.getOrDefault(GraphConstants.Kind.METHOD, 0L)
                + kindCounts.getOrDefault(GraphConstants.Kind.CONSTRUCTOR, 0L));
        s.put("package_deps", packageDeps.size());
        return s;
    }

    private static boolean TYPE_KIND(String kind) {
        return GraphConstants.TYPE_KINDS.contains(kind);
    }
}
