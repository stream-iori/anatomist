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
        Set<String> producers = new LinkedHashSet<>();
        for (ProjectResourceAnalyzer analyzer : extensions.registry().projectResourceAnalyzers()) {
            if (!analyzer.enabled(context)) continue;
            List<ProjectResource> selected = inventory.stream()
                    .filter(analyzer.selector()::matches).toList();
            if (selected.isEmpty()) continue;
            FactOrigin.Cursor start = FactOrigin.cursor(result);
            analyzer.analyze(context, selected, facts, result);
            FactOrigin.stamp(result, start, null, analyzer.producerId());
            producers.add(analyzer.producerId());
        }
        return Set.copyOf(producers);
    }
}
