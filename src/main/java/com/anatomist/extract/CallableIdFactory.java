package com.anatomist.extract;

import com.anatomist.core.NodeIdGenerator;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Builds callable symbol ids from both SymbolSolver declarations and their AST.
 * The AST fallback keeps overloads distinct when one resolved parameter type is
 * unavailable instead of collapsing every failure to {@code <unresolved>}.
 */
final class CallableIdFactory {

    private CallableIdFactory() {}

    static String forMethod(NodeIdGenerator generator, MethodDeclaration declaration) {
        ResolvedMethodDeclaration resolved = declaration.resolve();
        return forMethod(generator, resolved, declaration);
    }

    static String forMethod(NodeIdGenerator generator, ResolvedMethodDeclaration declaration) {
        Optional<MethodDeclaration> ast = ast(declaration, MethodDeclaration.class);
        return ast.map(method -> forMethod(generator, declaration, method))
                .orElseGet(() -> generator.forMethod(declaration));
    }

    static String forConstructor(NodeIdGenerator generator, ConstructorDeclaration declaration) {
        ResolvedConstructorDeclaration resolved = declaration.resolve();
        return forConstructor(generator, resolved, declaration);
    }

    static String forConstructor(NodeIdGenerator generator, ResolvedConstructorDeclaration declaration) {
        Optional<ConstructorDeclaration> ast = ast(declaration, ConstructorDeclaration.class);
        if (ast.isPresent()) return forConstructor(generator, declaration, ast.get());
        Optional<CompactConstructorDeclaration> compact = ast(declaration, CompactConstructorDeclaration.class);
        if (compact.isPresent()) return forCompactConstructor(generator, compact.get());
        return generator.forConstructor(declaration);
    }

    static String forCompactConstructor(NodeIdGenerator generator,
                                        CompactConstructorDeclaration declaration) {
        RecordDeclaration record = declaration.findAncestor(RecordDeclaration.class)
                .orElseThrow(() -> new IllegalArgumentException("compact constructor has no record owner"));
        ResolvedConstructorDeclaration resolved = null;
        String owner;
        try {
            resolved = declaration.resolve();
            owner = generator.forType(resolved.declaringType());
        } catch (RuntimeException e) {
            try { owner = generator.forType(record.resolve()); }
            catch (RuntimeException ignored) { owner = lexicalTypeId(record); }
        }
        return owner + "#" + record.getNameAsString() + "("
                + signature(resolved, record.getParameters(), declaration) + ")";
    }

    static String forAnonymousMethod(String ownerId, MethodDeclaration declaration) {
        return ownerId + "#" + declaration.getNameAsString()
                + "(" + signature(null, declaration.getParameters(), declaration) + ")";
    }

    static String signature(MethodDeclaration declaration) {
        ResolvedMethodDeclaration resolved = null;
        try { resolved = declaration.resolve(); }
        catch (RuntimeException ignore) { }
        return signature(resolved, declaration.getParameters(), declaration);
    }

    private static String forMethod(NodeIdGenerator generator,
                                    ResolvedMethodDeclaration resolved,
                                    MethodDeclaration declaration) {
        String owner = anonymousOwner(generator, declaration)
                .orElseGet(() -> generator.forType(resolved.declaringType()));
        return owner + "#" + resolved.getName() + "("
                + signature(resolved, declaration.getParameters(), declaration) + ")";
    }

    private static Optional<String> anonymousOwner(NodeIdGenerator generator,
                                                   MethodDeclaration declaration) {
        Optional<ObjectCreationExpr> anonymous = declaration.findAncestor(ObjectCreationExpr.class)
                .filter(expr -> expr.getAnonymousClassBody().isPresent());
        if (anonymous.isEmpty()) return Optional.empty();
        return Optional.ofNullable(new AstEnclosing(generator).anonymousClassId(anonymous.get()));
    }

    private static String forConstructor(NodeIdGenerator generator,
                                         ResolvedConstructorDeclaration resolved,
                                         ConstructorDeclaration declaration) {
        String owner = generator.forType(resolved.declaringType());
        return owner + "#" + resolved.getName() + "("
                + signature(resolved, declaration.getParameters(), declaration) + ")";
    }

    private static String signature(ResolvedMethodLikeDeclaration resolved,
                                    List<Parameter> parameters,
                                    Node declaration) {
        int count = resolved == null ? parameters.size() : resolved.getNumberOfParams();
        return IntStream.range(0, count)
                .mapToObj(i -> parameterType(resolved, parameters, declaration, i))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String parameterType(ResolvedMethodLikeDeclaration resolved,
                                        List<Parameter> parameters,
                                        Node declaration,
                                        int index) {
        if (resolved != null) {
            try {
                String type = NodeIdGenerator.erasedTypeDescribe(resolved.getParam(index).getType());
                if (usable(type)) return type;
            } catch (RuntimeException ignore) { }
        }
        if (index < parameters.size()) {
            String type = AstTypeNames.of(parameters.get(index).getType(), parameters.get(index));
            if (usable(type)) return type;
            String lexical = removeAsciiRegexWhitespace(
                    parameters.get(index).getTypeAsString());
            if (!lexical.isBlank()) return "?" + lexical;
        }
        int line = declaration.getBegin().map(p -> p.line).orElse(0);
        return "?param" + index + "@L" + line;
    }

    private static boolean usable(String type) {
        return type != null && !type.isBlank()
                && !"null".equals(type)
                && !"<unknown>".equals(type)
                && !"<unresolved>".equals(type);
    }

    private static String removeAsciiRegexWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character != ' ' && character != '\t' && character != '\n'
                    && character != '\u000B' && character != '\f' && character != '\r') {
                out.append(character);
            }
        }
        return out.length() == value.length() ? value : out.toString();
    }

    private static String lexicalTypeId(RecordDeclaration declaration) {
        String pkg = declaration.findCompilationUnit()
                .flatMap(cu -> cu.getPackageDeclaration())
                .map(p -> p.getNameAsString() + ".")
                .orElse("");
        java.util.List<String> owners = new java.util.ArrayList<>();
        declaration.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                .ifPresent(owner -> owners.add(owner.getNameAsString()));
        owners.add(declaration.getNameAsString());
        return pkg + String.join(".", owners);
    }

    private static <T extends Node> Optional<T> ast(ResolvedMethodLikeDeclaration declaration,
                                                     Class<T> type) {
        try { return declaration.toAst(type); }
        catch (RuntimeException ignore) { return Optional.empty(); }
    }
}
