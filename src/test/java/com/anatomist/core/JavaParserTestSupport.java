package com.anatomist.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.MemoryTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Helpers for spinning up JavaParser + SymbolSolver inside unit tests without
 * standing up a full Maven project — we parse an in-memory source string with
 * resolution enabled (ReflectionTypeSolver so {@code String} / {@code List}
 * etc. resolve) and walk the resulting AST.
 */
public final class JavaParserTestSupport {

    private JavaParserTestSupport() {}

    /** Parse {@code source} (single file) with SymbolSolver attached. */
    public static CompilationUnit parse(String source) {
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setSymbolResolver(new JavaSymbolSolver(combinedTypeSolver()));
        JavaParser parser = new JavaParser(cfg);
        ParseResult<CompilationUnit> r = parser.parse(source);
        if (!r.isSuccessful() || r.getResult().isEmpty()) {
            throw new AssertionError("parse failed: " + r.getProblems());
        }
        return r.getResult().get();
    }

    public static CombinedTypeSolver combinedTypeSolver() {
        CombinedTypeSolver ts = new CombinedTypeSolver();
        ts.add(new ReflectionTypeSolver(/*jreOnly*/ true));
        ts.add(new MemoryTypeSolver());
        return ts;
    }

    public static List<TypeDeclaration<?>> topTypes(CompilationUnit cu) {
        return cu.getTypes().stream().map(t -> (TypeDeclaration<?>) t).collect(Collectors.toList());
    }

    public static ResolvedReferenceTypeDeclaration resolveType(CompilationUnit cu, String simpleName) {
        TypeDeclaration<?> match = cu.findAll(TypeDeclaration.class).stream()
                .filter(t -> t.getNameAsString().equals(simpleName))
                .findFirst()
                .map(t -> (TypeDeclaration<?>) t)
                .orElseThrow(() -> new AssertionError("no type with name " + simpleName));
        return match.resolve();
    }

    public static List<ResolvedMethodDeclaration> resolveMethods(CompilationUnit cu,
                                                                 String typeName,
                                                                 String methodName) {
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.findAncestor(TypeDeclaration.class)
                        .map(t -> ((TypeDeclaration<?>) t).getNameAsString().equals(typeName))
                        .orElse(false))
                .filter(m -> m.getNameAsString().equals(methodName))
                .map(MethodDeclaration::resolve)
                .collect(Collectors.toList());
    }

    public static ResolvedConstructorDeclaration resolveConstructor(CompilationUnit cu,
                                                                    String typeName) {
        return cu.findAll(ConstructorDeclaration.class).stream()
                .filter(m -> m.findAncestor(TypeDeclaration.class)
                        .map(t -> ((TypeDeclaration<?>) t).getNameAsString().equals(typeName))
                        .orElse(false))
                .map(ConstructorDeclaration::resolve)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no constructor in " + typeName));
    }
}
