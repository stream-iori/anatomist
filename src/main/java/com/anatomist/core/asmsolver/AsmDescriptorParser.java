package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedVoidType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Converts JVM bytecode descriptor strings into {@link ResolvedType} instances.
 *  Handles erased types only; generic signatures are parsed by {@link AsmSignatureParser}. */
public final class AsmDescriptorParser {

    private AsmDescriptorParser() {}

    /** Parse a single field-style descriptor. Examples:
     *  {@code "I"}, {@code "Ljava/lang/String;"}, {@code "[I"}, {@code "V"}. */
    public static ResolvedType parseFieldDescriptor(String descriptor, TypeSolver solver) {
        return parse(descriptor, new int[]{0}, solver);
    }

    /** Parse the parameter list out of a method descriptor like {@code "(II)V"}. */
    public static List<ResolvedType> parseMethodParameters(String descriptor, TypeSolver solver) {
        if (descriptor.charAt(0) != '(') {
            throw new IllegalArgumentException("not a method descriptor: " + descriptor);
        }
        List<ResolvedType> params = new ArrayList<>();
        int[] cursor = {1};
        while (descriptor.charAt(cursor[0]) != ')') {
            params.add(parse(descriptor, cursor, solver));
        }
        return params;
    }

    /** Parse the return type out of a method descriptor like {@code "(II)V"}. */
    public static ResolvedType parseMethodReturnType(String descriptor, TypeSolver solver) {
        if (descriptor.charAt(0) != '(') {
            throw new IllegalArgumentException("not a method descriptor: " + descriptor);
        }
        int close = descriptor.indexOf(')');
        if (close < 0) throw new IllegalArgumentException("unterminated parameter list: " + descriptor);
        int[] cursor = {close + 1};
        return parse(descriptor, cursor, solver);
    }

    /** Advance {@code cursor[0]} past one type token. */
    private static ResolvedType parse(String d, int[] cursor, TypeSolver solver) {
        char c = d.charAt(cursor[0]);
        switch (c) {
            case 'V': cursor[0]++; return ResolvedVoidType.INSTANCE;
            case 'I': cursor[0]++; return ResolvedPrimitiveType.INT;
            case 'J': cursor[0]++; return ResolvedPrimitiveType.LONG;
            case 'Z': cursor[0]++; return ResolvedPrimitiveType.BOOLEAN;
            case 'D': cursor[0]++; return ResolvedPrimitiveType.DOUBLE;
            case 'F': cursor[0]++; return ResolvedPrimitiveType.FLOAT;
            case 'B': cursor[0]++; return ResolvedPrimitiveType.BYTE;
            case 'S': cursor[0]++; return ResolvedPrimitiveType.SHORT;
            case 'C': cursor[0]++; return ResolvedPrimitiveType.CHAR;
            case '[':
                cursor[0]++;
                return new ResolvedArrayType(parse(d, cursor, solver));
            case 'L': {
                int end = d.indexOf(';', cursor[0]);
                if (end < 0) throw new IllegalArgumentException(
                        "unterminated reference at offset " + cursor[0] + ": " + d);
                String internal = d.substring(cursor[0] + 1, end);
                cursor[0] = end + 1;
                String fqn = internal.replace('/', '.');
                TypeSolver root = solver.getRoot();
                SymbolReference<ResolvedReferenceTypeDeclaration> ref = root.tryToSolveType(fqn);
                if (!ref.isSolved()) {
                    return new ReferenceTypeImpl(new UnsolvedTypeDeclaration(fqn));
                }
                return new ReferenceTypeImpl(ref.getCorrespondingDeclaration());
            }
            default:
                throw new IllegalArgumentException(
                        "unsupported descriptor char '" + c + "' in " + d);
        }
    }

    /** Minimal stub for types that cannot be resolved. Allows method resolution
     *  to proceed with parameter-count matching instead of crashing. */
    static final class UnsolvedTypeDeclaration implements ResolvedReferenceTypeDeclaration {
        private final String fqn;
        UnsolvedTypeDeclaration(String fqn) { this.fqn = fqn; }

        @Override public String getQualifiedName() { return fqn; }
        @Override public String getName() { return FqnUtil.simpleName(fqn); }
        @Override public String getPackageName() { return FqnUtil.packageName(fqn); }
        @Override public String getClassName() { return FqnUtil.className(fqn); }
        @Override public List<ResolvedFieldDeclaration> getAllFields() { return Collections.emptyList(); }
        @Override public List<ResolvedReferenceType> getAncestors(boolean b) { return Collections.emptyList(); }
        @Override public Set<ResolvedMethodDeclaration> getDeclaredMethods() { return Collections.emptySet(); }
        @Override public Set<MethodUsage> getAllMethods() { return Collections.emptySet(); }
        @Override public boolean isAssignableBy(ResolvedType t) { return false; }
        @Override public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) { return false; }
        @Override public boolean hasDirectlyAnnotation(String qn) { return false; }
        @Override public List<ResolvedTypeParameterDeclaration> getTypeParameters() { return Collections.emptyList(); }
        @Override public List<ResolvedConstructorDeclaration> getConstructors() { return Collections.emptyList(); }
        @Override public Set<ResolvedReferenceTypeDeclaration> internalTypes() { return Collections.emptySet(); }
        @Override public Optional<ResolvedReferenceTypeDeclaration> containerType() { return Optional.empty(); }
        @Override public boolean isClass() { return true; }
        @Override public boolean isInterface() { return false; }
        @Override public boolean isEnum() { return false; }
        @Override public boolean isRecord() { return false; }
        @Override public boolean isAnnotation() { return false; }
        @Override public boolean isTypeParameter() { return false; }
        @Override public boolean isType() { return true; }
        @Override public boolean isField() { return false; }
        @Override public boolean isParameter() { return false; }
        @Override public boolean isFunctionalInterface() { return false; }
        @Override public List<ResolvedFieldDeclaration> getDeclaredFields() { return Collections.emptyList(); }

        @Override public boolean equals(Object o) {
            return this == o || (o instanceof UnsolvedTypeDeclaration u && fqn.equals(u.fqn));
        }
        @Override public int hashCode() { return fqn.hashCode(); }
        @Override public String toString() { return "UnsolvedTypeDeclaration(" + fqn + ")"; }
    }
}
