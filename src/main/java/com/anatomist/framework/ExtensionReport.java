package com.anatomist.framework;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.IndexTimings;
import com.github.javaparser.ast.CompilationUnit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable report for one full or incremental extension execution batch. */
public final class ExtensionReport {
    private final List<IndexDiagnostic> diagnostics = new ArrayList<>();
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final IndexTimings timings;

    public ExtensionReport() {
        this(null);
    }

    public ExtensionReport(IndexTimings timings) {
        this.timings = timings;
    }

    public void consume(CompilationUnit unit) {
        consume(unit, unit.getStorage().map(storage -> storage.getPath().toString()).orElse(null));
    }

    public void consume(CompilationUnit unit, String sourceFile) {
        for (IndexDiagnostic diagnostic : ExtensionAstData.diagnostics(unit)) {
            diagnostics.add(sourceFile == null ? diagnostic : new IndexDiagnostic(
                    diagnostic.severity(), diagnostic.code(), diagnostic.phase(), sourceFile,
                    diagnostic.module(), diagnostic.scope(), diagnostic.symbol(),
                    diagnostic.count(), diagnostic.sample(), diagnostic.language(),
                    diagnostic.providerId(), diagnostic.providerReason()));
        }
        ExtensionAstData.counters(unit).forEach((key, value) -> counters.merge(key, value, Long::sum));
        if (timings != null) timings.addNanos("extension_ast_augment", ExtensionAstData.nanos(unit));
    }

    public void diagnostic(IndexDiagnostic diagnostic) {
        if (diagnostic != null) diagnostics.add(diagnostic);
    }

    public void increment(String key, long amount) {
        if (key != null && amount > 0) counters.merge(key, amount, Long::sum);
    }

    public List<IndexDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public Map<String, Long> counters() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(counters));
    }
}
