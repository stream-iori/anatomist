package com.anatomist.core;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JdtParserFactoryTest {

    @Test
    void parseAll_resolvesCrossFileBindings(@TempDir Path tmp) throws Exception {
        Path pkg = Files.createDirectories(tmp.resolve("src/pkg"));
        Files.writeString(pkg.resolve("A.java"), """
                package pkg;
                public class A {
                    public void run(B b) { b.greet(); }
                }
                """);
        Files.writeString(pkg.resolve("B.java"), """
                package pkg;
                public class B {
                    public void greet() {}
                }
                """);

        JdtParserFactory factory = new JdtParserFactory(
                21,
                List.of(),
                List.of(tmp.resolve("src").toString()),
                true
        );

        Map<String, CompilationUnit> units = new HashMap<>();
        factory.parseAll(
                List.of(pkg.resolve("A.java"), pkg.resolve("B.java")),
                new FileASTRequestor() {
                    @Override
                    public void acceptAST(String sourceFilePath, CompilationUnit ast) {
                        units.put(Path.of(sourceFilePath).getFileName().toString(), ast);
                    }
                }
        );

        CompilationUnit aCu = units.get("A.java");
        assertNotNull(aCu, "A.java was not parsed");

        AtomicReference<IMethodBinding> greetBinding = new AtomicReference<>();
        aCu.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if ("greet".equals(node.getName().getIdentifier())) {
                    greetBinding.set(node.resolveMethodBinding());
                }
                return false;
            }
        });

        IMethodBinding binding = greetBinding.get();
        assertNotNull(binding, "createASTs should share binding context across A.java/B.java");
        assertEquals("greet", binding.getName());
        assertEquals("pkg.B", binding.getDeclaringClass().getQualifiedName());
    }
}
