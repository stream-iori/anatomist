package com.anatomist.framework;

import com.anatomist.model.ExtractionResult;
import com.anatomist.model.FactOrigin;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

/** Runs every matching project analyzer over one shared resource inventory. */
public final class ProjectAnalysisRunner {
    public Set<String> run(PreparedExtensions extensions, AnalysisContext context,
                           List<ProjectResource> inventory, ProjectFactView facts,
                           ExtractionResult result) {
        return run(extensions, context, inventory, facts, result, null);
    }

    public Set<String> run(PreparedExtensions extensions, AnalysisContext context,
                           List<ProjectResource> inventory, ProjectFactView facts,
                           ExtractionResult result, ExtensionReport report) {
        Set<String> producers = new LinkedHashSet<>();
        for (ProjectResourceAnalyzer analyzer : extensions.registry().projectResourceAnalyzers()) {
            if (!analyzer.enabled(context)) continue;
            List<ProjectResource> selected = inventory.stream()
                    .filter(analyzer.selector()::matches).toList();
            if (selected.isEmpty()) continue;
            ExtractionResult isolated = new ExtractionResult();
            try {
                analyzer.analyze(context, selected, facts, isolated);
                FactOrigin.stamp(isolated, FactOrigin.beginning(),
                        null, analyzer.producerId());
                result.addFacts(isolated);
                producers.add(analyzer.producerId());
            } catch (RuntimeException failure) {
                if (report != null) report.diagnostic(new com.anatomist.core.IndexDiagnostic(
                        "warning", "EXTENSION_PROJECT_ANALYZER_FAILED", "EXTENSION_PROJECT",
                        selected.getFirst().sourceFile(), null, null, analyzer.id(), 1,
                        failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            }
        }
        return Set.copyOf(producers);
    }
}
