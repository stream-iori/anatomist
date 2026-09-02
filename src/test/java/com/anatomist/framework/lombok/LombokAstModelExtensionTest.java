package com.anatomist.framework.lombok;

import com.anatomist.framework.ExtensionAstData;
import com.anatomist.framework.SyntheticOrigin;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LombokAstModelExtensionTest {

    @Test
    void dataGeneratesSignaturesAndRespectsExplicitAndFinalMembers(@TempDir Path root) {
        CompilationUnit unit = StaticJavaParser.parse("""
                package p;
                import lombok.Data;
                import lombok.NonNull;
                @Data class User {
                  private final String id;
                  @NonNull private String name;
                  private boolean active;
                  public String getName() { return name; }
                }
                """);

        new LombokAstModelExtension(root, false).augment(unit);

        ClassOrInterfaceDeclaration type = unit.getClassByName("User").orElseThrow();
        assertEquals(1, type.getMethodsByName("getName").size(), "explicit member wins");
        assertEquals(1, type.getMethodsByName("getId").size());
        assertEquals(1, type.getMethodsByName("isActive").size());
        assertEquals(1, type.getMethodsByName("setName").size());
        assertEquals(1, type.getMethodsByName("setActive").size());
        assertTrue(type.getMethodsByName("setId").isEmpty(), "final field has no setter");
        assertEquals(1, type.getConstructors().size());
        assertEquals(2, type.getConstructors().getFirst().getParameters().size());
        assertEquals(1, type.getMethodsByName("equals").size());
        assertEquals(1, type.getMethodsByName("hashCode").size());
        assertEquals(1, type.getMethodsByName("toString").size());

        MethodDeclaration generated = type.getMethodsByName("getId").getFirst();
        SyntheticOrigin origin = SyntheticOrigin.of(generated);
        assertNotNull(origin);
        assertEquals("lombok-ast", origin.producerId());
        assertFalse(origin.bodyAvailable());
        assertTrue(ExtensionAstData.diagnostics(unit).stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("LOMBOK_BODY_SEMANTICS_OMITTED")));
    }

    @Test
    void accessNoneSuppressesGenerationAndLoggerIsSynthetic(@TempDir Path root) {
        CompilationUnit unit = StaticJavaParser.parse("""
                package p;
                import lombok.AccessLevel;
                import lombok.Getter;
                import lombok.extern.slf4j.Slf4j;
                @Slf4j class Service {
                  @Getter(AccessLevel.NONE) private String secret;
                }
                """);

        new LombokAstModelExtension(root, false).augment(unit);

        ClassOrInterfaceDeclaration type = unit.getClassByName("Service").orElseThrow();
        assertTrue(type.getMethodsByName("getSecret").isEmpty());
        var log = type.getFieldByName("log").orElseThrow();
        assertTrue(log.isPrivate());
        assertTrue(log.isStatic());
        assertTrue(log.isFinal());
        assertNotNull(SyntheticOrigin.of(log));
    }

    @Test
    void supportedRootConfigChangesNamesAndReportsPartial(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("lombok.config"), """
                lombok.getter.noIsPrefix = true
                lombok.log.fieldName = LOGGER
                lombok.unknown.signature.option = true
                """);
        CompilationUnit unit = StaticJavaParser.parse("""
                import lombok.Getter;
                import lombok.extern.slf4j.Slf4j;
                @Getter @Slf4j class Flag { private boolean ready; }
                """);

        new LombokAstModelExtension(root, false).augment(unit);

        ClassOrInterfaceDeclaration type = unit.getClassByName("Flag").orElseThrow();
        assertEquals(1, type.getMethodsByName("getReady").size());
        assertTrue(type.getMethodsByName("isReady").isEmpty());
        assertTrue(type.getFieldByName("LOGGER").isPresent());
        assertTrue(ExtensionAstData.diagnostics(unit).stream()
                .anyMatch(diagnostic -> diagnostic.code().equals("LOMBOK_CONFIG_PARTIAL")));
    }

    @Test
    void unsupportedFeatureIsVisibleAndStrictPromotesSeverity(@TempDir Path root) {
        CompilationUnit unit = StaticJavaParser.parse("""
                import lombok.Builder;
                @Builder class Item { String name; }
                """);

        new LombokAstModelExtension(root, true).augment(unit);

        var diagnostic = ExtensionAstData.diagnostics(unit).stream()
                .filter(value -> value.code().equals("LOMBOK_FEATURE_UNSUPPORTED"))
                .findFirst().orElseThrow();
        assertEquals("error", diagnostic.severity());
        assertEquals("Builder", diagnostic.symbol());
    }
}
