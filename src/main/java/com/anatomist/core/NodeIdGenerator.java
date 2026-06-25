package com.anatomist.core;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserAnonymousClassDeclaration;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates stable Node IDs per DESIGN.md §Node ID 生成规则.
 *
 * <ul>
 *   <li>CLASS/INTERFACE/ENUM: FQN preserved as-is</li>
 *   <li>METHOD: {@code <classFqn>#<name>(<erased,param,fqns>)}</li>
 *   <li>FIELD: {@code <classFqn>#<fieldName>}</li>
 *   <li>ANONYMOUS_CLASS: {@code <enclosingMethodId>$anon@L<line>}
 *       — derived from the wrapping {@link ObjectCreationExpr}'s position so
 *       the id is stable across runs (JavaParser's own
 *       {@code JavaParserAnonymousClassDeclaration.getQualifiedName()} embeds
 *       an unstable generated name and must not be used as an id).</li>
 * </ul>
 */
public class NodeIdGenerator {

    public String forType(ResolvedTypeDeclaration t) {
        if (t == null) throw new IllegalArgumentException("declaration is null");
        if (t instanceof JavaParserAnonymousClassDeclaration anon) {
            String stable = stableAnonymousId(anon);
            if (stable != null) return stable;
        }
        return t.getQualifiedName();
    }

    public String forMethod(ResolvedMethodDeclaration m) {
        return forMethodLike(m);
    }

    public String forConstructor(ResolvedConstructorDeclaration c) {
        return forMethodLike(c);
    }

    public String forField(ResolvedFieldDeclaration f) {
        if (f == null) throw new IllegalArgumentException("field is null");
        return forType(f.declaringType()) + "#" + f.getName();
    }

    /** ID for a LAMBDA Node: parent method id + "$lambda@L<line>C<column>". */
    public static String forLambda(String parentId, int line, int column) {
        return parentId + "$lambda@L" + line + "C" + column;
    }

    /** ID for a METHOD_REF Node: parent method id + "$methodref@L<line>C<column>". */
    public static String forMethodRef(String parentId, int line, int column) {
        return parentId + "$methodref@L" + line + "C" + column;
    }

    private String forMethodLike(ResolvedMethodLikeDeclaration m) {
        if (m == null) throw new IllegalArgumentException("method is null");
        String classFqn = forType(m.declaringType());
        String params = IntStream.range(0, m.getNumberOfParams())
                .mapToObj(i -> paramTypeOrUnresolved(m, i))
                .collect(Collectors.joining(","));
        String name = m.getName();
        return classFqn + "#" + name + "(" + params + ")";
    }

    private static String paramTypeOrUnresolved(ResolvedMethodLikeDeclaration m, int i) {
        try {
            String rendered = erasedTypeDescribe(m.getParam(i).getType());
            return rendered == null || rendered.isBlank() || "null".equals(rendered)
                    ? "<unresolved>"
                    : rendered;
        } catch (RuntimeException e) {
            return "<unresolved>";
        }
    }

    /**
     * Compute the stable {@code $anon@L<line>} id for an anonymous class by
     * walking up to the enclosing method/constructor. Returns null when the
     * AST can't be reached (synthesized declaration) — callers fall back to
     * {@link ResolvedTypeDeclaration#getQualifiedName()}.
     */
    private String stableAnonymousId(JavaParserAnonymousClassDeclaration anon) {
        Optional<com.github.javaparser.ast.Node> astOpt = anon.toAst();
        if (astOpt.isEmpty() || !(astOpt.get() instanceof ObjectCreationExpr expr)) return null;
        int line = expr.getBegin().map(p -> p.line).orElse(0);
        // Prefer the enclosing method; fall back to enclosing constructor.
        Optional<MethodDeclaration> m = expr.findAncestor(MethodDeclaration.class);
        if (m.isPresent()) {
            try { return forMethod(m.get().resolve()) + "$anon@L" + line; }
            catch (RuntimeException ignore) { /* fall through */ }
        }
        Optional<ConstructorDeclaration> c = expr.findAncestor(ConstructorDeclaration.class);
        if (c.isPresent()) {
            try { return forConstructor(c.get().resolve()) + "$anon@L" + line; }
            catch (RuntimeException ignore) { /* fall through */ }
        }
        return null;
    }

    /**
     * Render a {@link ResolvedType} as its erased, fully-qualified form so a
     * method signature is stable across overload/generic variants.
     *
     * <p>{@code List<String>} → {@code java.util.List}; {@code int[]} →
     * {@code int[]}; type variables collapse to their first bound.</p>
     */
    public static String erasedTypeDescribe(ResolvedType type) {
        if (type == null) return "<unknown>";
        try {
            ResolvedType erased = type.erasure();
            String rendered = erased.describe();
            return rendered == null || rendered.isBlank() || "null".equals(rendered)
                    ? "<unknown>"
                    : rendered;
        } catch (RuntimeException e) {
            // Some Resolved* types do not implement erasure() (e.g. void); fall
            // back to whatever describe() gives.
            try {
                String rendered = type.describe();
                return rendered == null || rendered.isBlank() || "null".equals(rendered)
                        ? "<unknown>"
                        : rendered;
            }
            catch (RuntimeException e2) { return "<unknown>"; }
        }
    }

    public static String externalMethodFqn(ResolvedMethodLikeDeclaration m) {
        String classFqn;
        try {
            classFqn = m.declaringType().getQualifiedName();
        } catch (RuntimeException e) {
            classFqn = "<unknown>";
        }
        String params = IntStream.range(0, m.getNumberOfParams())
                .mapToObj(i -> paramTypeOrUnresolved(m, i))
                .collect(Collectors.joining(","));
        return classFqn + "#" + m.getName() + "(" + params + ")";
    }

    /** Convenience: external FQN for a reference-type declaration. */
    public static String externalTypeFqn(ResolvedReferenceTypeDeclaration t) {
        return t.getQualifiedName();
    }

    /** Convenience: external FQN for a field declaration (e.g. java.lang.System#out). */
    public static String externalFieldFqn(ResolvedFieldDeclaration f) {
        String classFqn;
        try {
            classFqn = f.declaringType().getQualifiedName();
        } catch (RuntimeException e) {
            classFqn = "<unknown>";
        }
        return classFqn + "#" + f.getName();
    }
}
