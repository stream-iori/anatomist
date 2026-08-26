package com.anatomist.query;

import java.util.LinkedHashMap;
import java.util.Map;

public record QueryBudget(String mode, int emitted, int total, boolean truncated) {
    public QueryBudget(String mode, int emitted, int total) {
        this(mode, emitted, total, emitted < total);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("emitted", emitted);
        out.put("total", total);
        out.put("truncated", truncated);
        return out;
    }
}
