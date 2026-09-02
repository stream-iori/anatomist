package com.anatomist.framework;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.ProjectScanner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs enabled providers and builds one deterministic shared inventory. */
public final class ProjectResourceDiscovery {
    public List<ProjectResource> discover(PreparedExtensions extensions,
                                          AnalysisContext context,
                                          ProjectScanner scanner,
                                          ExtensionReport report) {
        Map<String, ProjectResource> unique = new LinkedHashMap<>();
        for (ProjectResourceProvider provider : extensions.registry().projectResourceProviders()) {
            if (!provider.enabled(context)) continue;
            try {
                for (ProjectResource resource : provider.discover(context, scanner)) {
                    if (resource == null || resource.kind() == null || resource.sourceFile() == null) continue;
                    unique.putIfAbsent(resource.kind() + "\u0000" + resource.sourceFile(), resource);
                }
            } catch (RuntimeException failure) {
                if (report != null) report.diagnostic(new IndexDiagnostic(
                        "warning", "EXTENSION_RESOURCE_DISCOVERY_FAILED", "EXTENSION_RESOURCE",
                        null, null, null, provider.id(), 1,
                        failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            }
        }
        List<ProjectResource> out = new ArrayList<>(unique.values());
        out.sort(Comparator.comparing(ProjectResource::kind)
                .thenComparing(ProjectResource::sourceFile));
        return List.copyOf(out);
    }
}
