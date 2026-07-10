package com.anatomist.core;

import java.util.LinkedHashMap;
import java.util.Map;

public record IndexDiagnostic(
        String severity,
        String code,
        String phase,
        String sourceFile,
        String module,
        String scope,
        String symbol,
        long count,
        String sample
) {
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("severity", severity);
        out.put("code", code);
        out.put("phase", phase);
        if (sourceFile != null) out.put("source_file", sourceFile);
        if (module != null) out.put("module", module);
        if (scope != null) out.put("scope", scope);
        if (symbol != null) out.put("symbol", symbol);
        out.put("count", count);
        if (sample != null) out.put("sample", sample.length() > 500 ? sample.substring(0, 500) : sample);
        return out;
    }
}
