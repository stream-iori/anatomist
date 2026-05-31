package com.anatomist.core;

import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaParserFactoryTest {

    @Test
    void parseAll_parsesEverySourceFile(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("A.java"), "package p; public class A {}");
        Files.writeString(src.resolve("B.java"), "package p; public class B {}");

        JavaParserFactory factory = new JavaParserFactory(
                21, List.of(), List.of(src), /*vmClasspath*/ true);

        List<String> seen = new ArrayList<>();
        factory.parseAll((path, cu) -> {
            cu.getTypes().forEach(t -> seen.add(t.getNameAsString()));
        });
        seen.sort(String::compareTo);
        assertEquals(List.of("A", "B"), seen);
    }

    @Test
    void parseFiles_attachesSymbolResolver(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path a = src.resolve("A.java");
        Files.writeString(a, "package p; public class A { public void foo() {} }");

        JavaParserFactory factory = new JavaParserFactory(
                21, List.of(), List.of(src), /*vmClasspath*/ true);

        List<CompilationUnit> cus = factory.parseFiles(List.of(a));
        assertEquals(1, cus.size());
        // resolve() should not throw with the SymbolResolver attached
        cus.get(0).findAll(com.github.javaparser.ast.body.MethodDeclaration.class)
                .forEach(m -> assertDoesNotThrow(m::resolve));
    }

    @Test
    void toLanguageLevel_supportsJava17() {
        assertEquals(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_17,
                JavaParserFactory.toLanguageLevel(17));
    }

    @Test
    void toLanguageLevel_supportsJava16ForRecords() {
        assertEquals(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_16,
                JavaParserFactory.toLanguageLevel(16));
    }
}
