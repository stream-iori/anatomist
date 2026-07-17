package com.anatomist.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record HealthGateResult(
        HealthPolicy policy,
        boolean passed,
        List<String> blockingCodes
) {
    public HealthGateResult {
        blockingCodes = blockingCodes == null ? List.of() : List.copyOf(blockingCodes);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", policy.wireName());
        out.put("passed", passed);
        out.put("blocking_codes", blockingCodes);
        return out;
    }
}
