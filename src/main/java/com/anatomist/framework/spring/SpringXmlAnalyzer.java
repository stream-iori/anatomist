package com.anatomist.framework.spring;

import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SpringBeanParser;
import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.extract.XmlBeanExtractor;
import com.anatomist.extract.XmlBeanExtractor.BeanRefTarget;
import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.ProjectAnalyzer;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SpringXmlAnalyzer implements ProjectAnalyzer {

    @Override public String id() { return "spring-xml"; }

    @Override
    public boolean enabled(AnalysisContext context) {
        return context.springXml();
    }

    @Override
    public void analyze(AnalysisContext context, ExtractionResult result) {
        if (!enabled(context)) return;
        var xmlFiles = new ProjectScanner().scanSpringXml(context.projectRoot());
        if (xmlFiles.isEmpty()) return;

        Set<String> knownIds = new HashSet<>();
        for (Node n : result.nodes) knownIds.add(n.id);
        extractXmlBeans(context.projectRoot(), xmlFiles, knownIds, existingBeanTargets(result), result);
    }

    public static void extractXmlBeans(Path projectRoot, List<Path> xmlFiles, Set<String> knownIds,
                                       Map<String, BeanRefTarget> existingBeans,
                                       ExtractionResult result) {
        List<XmlSource> parsed = new ArrayList<>();
        SpringBeanParser beanParser = new SpringBeanParser();
        List<Path> sorted = new ArrayList<>(xmlFiles);
        sorted.sort(Comparator.comparing(p -> relativize(projectRoot, p.toAbsolutePath().normalize())));
        for (Path xml : sorted) {
            String rel = relativize(projectRoot, xml.toAbsolutePath().normalize());
            parsed.add(new XmlSource(rel, beanParser.parse(xml)));
        }

        Set<String> allKnownIds = new HashSet<>(knownIds);
        Map<String, BeanRefTarget> resolvedBeans = new HashMap<>(existingBeans);
        Set<String> ambiguous = new HashSet<>();
        for (XmlSource src : parsed) {
            for (ParsedBean b : src.beans()) {
                String beanId = XmlBeanExtractor.beanNodeId(b.name(), src.sourceFile());
                allKnownIds.add(beanId);
                putUnique(resolvedBeans, ambiguous, "bean:" + b.name(),
                        new BeanRefTarget(beanId, b.className()));
            }
        }

        XmlBeanExtractor xmlExtractor = new XmlBeanExtractor("MAIN");
        for (XmlSource src : parsed) {
            xmlExtractor.extractWithResolvedBeans(src.beans(), allKnownIds, resolvedBeans, src.sourceFile(), result);
        }
    }

    public static Map<String, BeanRefTarget> fromBeanClassMap(Map<String, String> beanClassById) {
        Map<String, BeanRefTarget> out = new HashMap<>();
        for (Map.Entry<String, String> e : beanClassById.entrySet()) {
            out.put(e.getKey(), new BeanRefTarget(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static Map<String, BeanRefTarget> existingBeanTargets(ExtractionResult result) {
        Map<String, BeanRefTarget> out = new HashMap<>();
        for (com.anatomist.model.Edge e : result.edges) {
            if (!GraphConstants.Relation.DEFINED_BY.equals(e.relation)) continue;
            if (e.sourceId == null || !e.sourceId.startsWith("bean:")) continue;
            if (e.targetId != null) out.put(e.sourceId, new BeanRefTarget(e.sourceId, e.targetId));
            else if (e.externalTargetFqn != null) out.put(e.sourceId, new BeanRefTarget(e.sourceId, e.externalTargetFqn));
        }
        return out;
    }

    private static void putUnique(Map<String, BeanRefTarget> beans, Set<String> ambiguous,
                                  String key, BeanRefTarget target) {
        if (ambiguous.contains(key)) return;
        BeanRefTarget prior = beans.putIfAbsent(key, target);
        if (prior != null && !prior.equals(target)) {
            beans.remove(key);
            ambiguous.add(key);
        }
    }

    private record XmlSource(String sourceFile, List<ParsedBean> beans) {}

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }
}
