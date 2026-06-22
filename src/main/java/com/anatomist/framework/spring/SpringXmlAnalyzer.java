package com.anatomist.framework.spring;

import com.anatomist.core.ProjectScanner;
import com.anatomist.core.SpringBeanParser;
import com.anatomist.extract.XmlBeanExtractor;
import com.anatomist.framework.AnalysisContext;
import com.anatomist.framework.ProjectAnalyzer;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;

import java.nio.file.Path;
import java.util.HashSet;
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
        XmlBeanExtractor xmlExtractor = new XmlBeanExtractor("MAIN");
        SpringBeanParser beanParser = new SpringBeanParser();
        for (Path xml : xmlFiles) {
            String rel = relativize(context.projectRoot(), xml.toAbsolutePath().normalize());
            xmlExtractor.extract(beanParser.parse(xml), knownIds, rel, result);
        }
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }
}
