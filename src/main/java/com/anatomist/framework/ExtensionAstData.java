package com.anatomist.framework;

import com.anatomist.core.IndexDiagnostic;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-compilation-unit extension telemetry carried through parser caches. */
public final class ExtensionAstData {
    private static final DiagnosticsKey DIAGNOSTICS = new DiagnosticsKey();
    private static final CountersKey COUNTERS = new CountersKey();
    private static final NanosKey NANOS = new NanosKey();

    private ExtensionAstData() {}

    public static void addDiagnostic(CompilationUnit unit, IndexDiagnostic diagnostic) {
        List<IndexDiagnostic> values = unit.findData(DIAGNOSTICS)
                .map(ArrayList::new).orElseGet(ArrayList::new);
        values.add(diagnostic);
        unit.setData(DIAGNOSTICS, List.copyOf(values));
    }

    public static List<IndexDiagnostic> diagnostics(CompilationUnit unit) {
        return unit.findData(DIAGNOSTICS).orElse(List.of());
    }

    public static void increment(CompilationUnit unit, String key, long amount) {
        if (key == null || amount <= 0) return;
        Map<String, Long> values = unit.findData(COUNTERS)
                .map(LinkedHashMap::new).orElseGet(LinkedHashMap::new);
        values.merge(key, amount, Long::sum);
        unit.setData(COUNTERS, Map.copyOf(values));
    }

    public static Map<String, Long> counters(CompilationUnit unit) {
        return unit.findData(COUNTERS).orElse(Map.of());
    }

    public static void addNanos(CompilationUnit unit, long nanos) {
        if (nanos <= 0) return;
        unit.setData(NANOS, unit.findData(NANOS).orElse(0L) + nanos);
    }

    public static long nanos(CompilationUnit unit) {
        return unit.findData(NANOS).orElse(0L);
    }

    public static void copyFrom(CompilationUnit source, CompilationUnit target) {
        source.findData(DIAGNOSTICS).ifPresent(value -> target.setData(DIAGNOSTICS, value));
        source.findData(COUNTERS).ifPresent(value -> target.setData(COUNTERS, value));
        source.findData(NANOS).ifPresent(value -> target.setData(NANOS, value));
    }

    private static final class DiagnosticsKey extends DataKey<List<IndexDiagnostic>> {}
    private static final class CountersKey extends DataKey<Map<String, Long>> {}
    private static final class NanosKey extends DataKey<Long> {}
}
