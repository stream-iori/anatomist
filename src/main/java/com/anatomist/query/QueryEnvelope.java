package com.anatomist.query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Uniform JSON envelope emitted by every query subcommand.
 * <pre>
 * {
 *   "query":   "callees-of OrderService#createOrder --depth 5",
 *   "results": [...],
 *   "stats":   { "total": 6, "max_depth": 2 }
 * }
 * </pre>
 */
public class QueryEnvelope {
    public String query;
    public List<?> results;
    public Map<String, Object> stats = new LinkedHashMap<>();
    public Map<String, Object> budget = new LinkedHashMap<>();
    public List<String> nextQueries;
    public SliceResult blocks;

    public QueryEnvelope(String query, List<?> results) {
        this.query = query;
        this.results = results;
        this.stats.put("total", results.size());
    }
}
