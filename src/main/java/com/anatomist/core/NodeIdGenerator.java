package com.anatomist.core;

import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates stable Node IDs per DESIGN.md §Node ID 生成规则.
 *
 * <ul>
 *   <li>CLASS/INTERFACE/ENUM: FQN preserved as-is</li>
 *   <li>METHOD: {@code <classFqn>#<name>(<erased,param,fqns>)}</li>
 *   <li>FIELD: {@code <classFqn>#<fieldName>}</li>
 * </ul>
 */
public class NodeIdGenerator {

    public String forType(ResolvedTypeDeclaration t) {
        if (t == null) throw new IllegalArgumentException("declaration is null");
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
        ResolvedTypeDeclaration decl = f.declaringType();
        return decl.getQualifiedName() + "#" + f.getName();
    }

    private String forMethodLike(ResolvedMethodLikeDeclaration m) {
        if (m == null) throw new IllegalArgumentException("method is null");
        String classFqn = m.declaringType().getQualifiedName();
        String params = IntStream.range(0, m.getNumberOfParams())
                .mapToObj(i -> paramTypeOrUnresolved(m, i))
                .collect(Collectors.joining(","));
        String name = m.getName();
        return classFqn + "#" + name + "(" + params + ")";
    }

    private static String paramTypeOrUnresolved(ResolvedMethodLikeDeclaration m, int i) {
        try {
            return erasedTypeDescribe(m.getParam(i).getType());
        } catch (RuntimeException e) {
            return "<unresolved>";
        }
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
            return erased.describe();
        } catch (RuntimeException e) {
            // Some Resolved* types do not implement erasure() (e.g. void); fall
            // back to whatever describe() gives.
            try { return type.describe(); }
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
}
