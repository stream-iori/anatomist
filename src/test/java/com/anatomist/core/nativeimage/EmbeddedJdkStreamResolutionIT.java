package com.anatomist.core.nativeimage;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedJdkStreamResolutionIT {

    private static EmbeddedJdkTypeSolver jdkSolver;

    @BeforeAll
    static void loadCatalog() throws Exception {
        JdkTypeCatalog cat = new JdkTypeCatalogBuilder().buildFromCurrentJdk();
        jdkSolver = new EmbeddedJdkTypeSolver(cat);
    }

    @Test
    void streamCallResolvesOnList() {
        Path fixture = Path.of("fixtures/mini-spring-shop/service/src/main/java");
        assertTrue(Files.isDirectory(fixture));

        CombinedTypeSolver ts = new CombinedTypeSolver();
        ts.add(new JavaParserTypeSolver(
                Path.of("fixtures/mini-spring-shop/domain/src/main/java")));
        ts.add(new JavaParserTypeSolver(fixture));
        ts.add(jdkSolver);

        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setSymbolResolver(new JavaSymbolSolver(ts));
        JavaParser parser = new JavaParser(cfg);

        try {
            ParseResult<CompilationUnit> pr = parser.parse(
                    fixture.resolve("com/example/shop/service/PriceCalculator.java"));
            assertTrue(pr.isSuccessful());
            CompilationUnit cu = pr.getResult().get();

            // Find items.stream() call
            var streamCalls = cu.findAll(MethodCallExpr.class,
                    mc -> mc.getNameAsString().equals("stream"));
            assertFalse(streamCalls.isEmpty(), "should find stream() call");

            MethodCallExpr streamCall = streamCalls.get(0);
            try {
                ResolvedMethodDeclaration resolved = streamCall.resolve();
                assertEquals("stream", resolved.getName());
            } catch (Exception e) {
                fail("stream() should resolve but got: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        } catch (Exception e) {
            fail("parsing failed: " + e);
        }
    }
}
