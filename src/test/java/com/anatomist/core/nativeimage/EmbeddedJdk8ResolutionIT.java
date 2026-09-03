package com.anatomist.core.nativeimage;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.extract.CallGraphExtractor;
import com.anatomist.extract.FieldAccessExtractor;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.util.Set;
import java.util.List;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Native-like contract for the embedded Java 8 platform catalog. */
class EmbeddedJdk8ResolutionIT {

    private static final List<ParserConfiguration.LanguageLevel> SUPPORTED_LEVELS = List.of(
            ParserConfiguration.LanguageLevel.JAVA_8,
            ParserConfiguration.LanguageLevel.JAVA_17,
            ParserConfiguration.LanguageLevel.JAVA_25);

    @Test
    void resolvesOptionalAndCryptoApisFromEmbeddedCatalog() throws Exception {
        JavaParser parser = embeddedParser(ParserConfiguration.LanguageLevel.JAVA_8);
        String source = "import java.util.Optional;"
                + "import javax.crypto.Cipher; import javax.crypto.KeyGenerator;"
                + "import javax.crypto.spec.SecretKeySpec; import javax.crypto.spec.IvParameterSpec;"
                + "class Sample { void run() throws Exception {"
                + "String value = Optional.of(\"x\").orElse(\"y\");"
                + "Cipher cipher = Cipher.getInstance(\"AES\");"
                + "KeyGenerator generator = KeyGenerator.getInstance(\"AES\");"
                + "SecretKeySpec key = new SecretKeySpec(new byte[16], \"AES\");"
                + "IvParameterSpec iv = new IvParameterSpec(new byte[16]);"
                + "cipher.init(Cipher.ENCRYPT_MODE, key, iv); } }";
        CompilationUnit unit = parser.parse(source).getResult().orElseThrow();

        Set<String> methods = unit.findAll(MethodCallExpr.class).stream()
                .map(call -> call.resolve().getQualifiedName())
                .collect(Collectors.toSet());
        assertTrue(methods.contains("java.util.Optional.orElse"));
        assertTrue(methods.contains("javax.crypto.Cipher.getInstance"));
        assertTrue(methods.contains("javax.crypto.KeyGenerator.getInstance"));
        assertTrue(methods.contains("javax.crypto.Cipher.init"));
        Set<String> constructors = unit.findAll(ObjectCreationExpr.class).stream()
                .map(creation -> creation.resolve().getQualifiedName())
                .collect(Collectors.toSet());
        assertTrue(constructors.contains("javax.crypto.spec.SecretKeySpec.SecretKeySpec"));
        assertTrue(constructors.contains("javax.crypto.spec.IvParameterSpec.IvParameterSpec"));

        ExtractionContext context = new ExtractionContext(
                Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");
        ExtractionResult graph = new ExtractionResult();
        new CallGraphExtractor(context).extract(unit, graph);
        new FieldAccessExtractor(context).extract(unit, graph);
        assertEquals(0, context.unresolvedCount(),
                "embedded JDK 8 fixture must not produce resolution diagnostics: "
                        + context.resolutionSummary(false).diagnostics());
        assertTrue(graph.edges.stream().anyMatch(edge -> "CALLS".equals(edge.relation)
                && "javax.crypto.Cipher#getInstance(java.lang.String)"
                .equals(edge.externalTargetFqn)), graph.edges.toString());
        assertTrue(graph.edges.stream().anyMatch(edge -> "READS".equals(edge.relation)
                && "javax.crypto.Cipher#ENCRYPT_MODE".equals(edge.externalTargetFqn)),
                graph.edges.toString());
    }

    @Test
    void resolvesOptionalCollectionChainAcrossSupportedLanguageLevels() throws Exception {
        String source = """
                import java.util.Collections;
                import java.util.List;
                import java.util.Optional;
                class Sample {
                  long count(List<String> values) {
                    return Optional.ofNullable(values)
                        .orElse(Collections.emptyList()).stream().count();
                  }
                }
                """;

        for (ParserConfiguration.LanguageLevel level : SUPPORTED_LEVELS) {
            ExtractionResult graph = extract(level, source);
            assertCalls(graph, level,
                    "java.util.Optional#orElse(java.lang.Object)",
                    "java.util.Collection#stream()",
                    "java.util.stream.Stream#count()");
        }
    }

    @Test
    void resolvesOptionalCollectionChainInsideFlatMapAcrossSupportedLanguageLevels(
            @TempDir Path sourceRoot) throws Exception {
        String source = """
                import java.util.Collections;
                import java.util.List;
                import java.util.Optional;
                import java.util.function.Function;
                import java.util.stream.Collectors;
                class Config {
                  List<String> getCoAssessors() { return null; }
                }
                class Sample {
                  List<String> collect(List<Config> configs) {
                    Function<List<Config>, List<String>> dataProcessor = configData ->
                        configData.stream()
                            .filter(config -> config != null)
                            .flatMap(config -> Optional.ofNullable(config.getCoAssessors())
                                .orElse(Collections.emptyList()).stream())
                            .collect(Collectors.toList());
                    return dataProcessor.apply(configs);
                  }
                }
                """;
        Path sourceFile = sourceRoot.resolve("Sample.java");
        Files.writeString(sourceFile, source);

        for (ParserConfiguration.LanguageLevel level : SUPPORTED_LEVELS) {
            CompilationUnit unit = embeddedParser(level, sourceRoot).parse(sourceFile)
                    .getResult().orElseThrow();
            ExtractionResult graph = extract(level, unit);
            assertCalls(graph, level,
                    "java.util.stream.Stream#filter(java.util.function.Predicate)",
                    "java.util.stream.Stream#flatMap(java.util.function.Function)",
                    "java.util.Optional#orElse(java.lang.Object)",
                    "java.util.Collection#stream()",
                    "java.util.stream.Stream#collect(java.util.stream.Collector)");
        }
    }

    @Test
    void doesNotInventApisMissingFromEmbeddedJdkRelease() throws Exception {
        String source = """
                import java.util.List;
                class Sample {
                  List<String> copy(List<String> values) {
                    return values.stream().toList();
                  }
                }
                """;
        CompilationUnit unit = embeddedParser(ParserConfiguration.LanguageLevel.JAVA_17)
                .parse(source).getResult().orElseThrow();
        ExtractionContext context = new ExtractionContext(
                Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");
        ExtractionResult graph = new ExtractionResult();

        new CallGraphExtractor(context).extract(unit, graph);

        assertFalse(graph.edges.stream().anyMatch(edge -> edge.externalTargetFqn != null
                && edge.externalTargetFqn.contains("#toList(")), graph.edges.toString());
        assertTrue(context.unresolvedCount() > 0,
                "Stream#toList must remain unresolved against the Java 8 catalog");
    }

    private JavaParser embeddedParser(ParserConfiguration.LanguageLevel level) throws Exception {
        return embeddedParser(level, null);
    }

    private JavaParser embeddedParser(ParserConfiguration.LanguageLevel level, Path sourceRoot)
            throws Exception {
        JdkTypeCatalog catalog;
        try (var input = getClass().getResourceAsStream("/META-INF/anatomist/jdk8-types.bin")) {
            assertNotNull(input);
            catalog = JdkTypeCatalog.readFrom(input);
        }
        CombinedTypeSolver types = new CombinedTypeSolver();
        if (sourceRoot != null) types.add(new JavaParserTypeSolver(sourceRoot));
        types.add(new EmbeddedJdkTypeSolver(catalog));
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(level)
                .setSymbolResolver(new JavaSymbolSolver(types)));
    }

    private ExtractionResult extract(ParserConfiguration.LanguageLevel level, String source)
            throws Exception {
        CompilationUnit unit = embeddedParser(level).parse(source).getResult().orElseThrow();
        return extract(level, unit);
    }

    private ExtractionResult extract(ParserConfiguration.LanguageLevel level, CompilationUnit unit) {
        ExtractionContext context = new ExtractionContext(
                Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");
        ExtractionResult graph = new ExtractionResult();
        new CallGraphExtractor(context).extract(unit, graph);
        assertEquals(0, context.unresolvedCount(),
                level + " diagnostics: " + context.resolutionSummary(false).diagnostics());
        return graph;
    }

    private static void assertCalls(ExtractionResult graph,
                                    ParserConfiguration.LanguageLevel level,
                                    String... targets) {
        Set<String> calls = graph.edges.stream()
                .filter(edge -> "CALLS".equals(edge.relation))
                .map(edge -> edge.externalTargetFqn)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        for (String target : targets) {
            assertTrue(calls.contains(target), level + " missing " + target + ": " + calls);
        }
    }
}
