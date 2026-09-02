package com.anatomist.framework.lombok;

import com.anatomist.config.ProjectConfig;
import com.anatomist.core.IndexDiagnostic;
import com.anatomist.framework.PreparedExtensions;
import com.anatomist.model.ProducerIds;
import com.anatomist.store.SqliteStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable project metadata describing the effective Lombok structural model. */
public final class LombokIndexMetadata {
    private LombokIndexMetadata() {}

    public static Map<String, String> snapshot(ProjectConfig config,
                                               PreparedExtensions extensions,
                                               SqliteStore store,
                                               List<IndexDiagnostic> diagnostics) {
        LombokMode mode = config == null ? LombokMode.OFF : config.lombokMode();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("lombok_mode", mode.optionValue());
        values.put("lombok_extension_version", mode == LombokMode.AST ? "1" : "");
        values.put("lombok_fingerprint", extensions == null ? "" : extensions.fingerprint());
        values.put("lombok_generated_members", String.valueOf(
                store == null ? 0 : store.countNodesByProducer(ProducerIds.LOMBOK_AST)));
        values.put("lombok_coverage", coverage(mode, diagnostics));
        return values;
    }

    private static String coverage(LombokMode mode, List<IndexDiagnostic> diagnostics) {
        if (mode == LombokMode.OFF) return "off";
        boolean partial = diagnostics != null && diagnostics.stream().anyMatch(value ->
                value.code().startsWith("LOMBOK_")
                        && !"LOMBOK_BODY_SEMANTICS_OMITTED".equals(value.code()));
        return partial ? "partial" : "signature-only";
    }
}
