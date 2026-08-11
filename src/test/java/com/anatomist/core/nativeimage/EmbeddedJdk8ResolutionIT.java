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
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Native-like contract for the embedded Java 8 platform catalog. */
class EmbeddedJdk8ResolutionIT {

    @Test
    void resolvesOptionalAndCryptoApisFromEmbeddedCatalog() throws Exception {
        JdkTypeCatalog catalog;
        try (var input = getClass().getResourceAsStream("/META-INF/anatomist/jdk8-types.bin")) {
            assertNotNull(input);
            catalog = JdkTypeCatalog.readFrom(input);
        }
        CombinedTypeSolver types = new CombinedTypeSolver();
        types.add(new EmbeddedJdkTypeSolver(catalog));
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8)
                .setSymbolResolver(new JavaSymbolSolver(types)));
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
}
