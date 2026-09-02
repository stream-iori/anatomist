package com.anatomist.framework.spring;

import com.anatomist.core.ProjectScanner;
import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.ProjectResource;
import com.anatomist.framework.ProjectResourceProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Discovers Spring beans XML without coupling orchestration to the resource kind. */
public final class SpringXmlResourceProvider implements ProjectResourceProvider {
    @Override public String id() { return "spring-xml-resources"; }
    @Override public String producerId() { return "spring-xml-resources"; }
    @Override public boolean enabled(AnalysisContext context) { return context != null && context.springXml(); }
    @Override public Set<String> kinds() { return Set.of("spring-xml"); }
    @Override public boolean mayContain(Path path) {
        return path != null && path.getFileName() != null
                && path.getFileName().toString().endsWith(".xml");
    }

    @Override
    public List<ProjectResource> discover(AnalysisContext context, ProjectScanner scanner) {
        return scanner.scanSpringXml(context.projectRoot()).stream()
                .map(path -> new ProjectResource(path, relative(context.projectRoot(), path), "spring-xml"))
                .toList();
    }

    private static String relative(Path root, Path path) {
        try { return root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString(); }
        catch (IllegalArgumentException ignored) { return path.toString(); }
    }
}
