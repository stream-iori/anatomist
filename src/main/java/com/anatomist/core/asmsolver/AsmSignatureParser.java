package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration.Bound;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedTypeVariable;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedVoidType;
import com.github.javaparser.resolution.types.ResolvedWildcard;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AsmSignatureParser {

    private AsmSignatureParser() {}

    static ResolvedTypeParameterDeclaration typeParam(
            String name, String containerQName, List<ResolvedTypeParameterDeclaration.Bound> bounds,
            TypeSolver solver) {
        return new ResolvedTypeParameterDeclaration() {
            @Override public String getName() { return name; }
            @Override public String getContainerQualifiedName() { return containerQName; }
            @Override public String getContainerId() { return containerQName; }
            @Override public com.github.javaparser.resolution.declarations.ResolvedTypeParametrizable getContainer() { return null; }
            @Override public List<Bound> getBounds() { return bounds; }
            @Override public java.util.Optional<com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration> containerType() {
                return java.util.Optional.empty();
            }
            @Override public ResolvedReferenceType object() {
                try {
                    var ref = solver.getRoot().tryToSolveType("java.lang.Object");
                    if (ref.isSolved()) return new ReferenceTypeImpl(ref.getCorrespondingDeclaration());
                } catch (RuntimeException ignored) {}
                return new ReferenceTypeImpl(new AsmDescriptorParser.UnsolvedTypeDeclaration("java.lang.Object"));
            }
            @Override public String toString() { return "TypeParameter " + name; }
        };
    }

    // ── Type parameter extraction (class/method formal type params) ──

    /**
     * Re-entrancy guard keyed by container FQN. Recursive generic bounds such as
     * {@code Enum<E extends Enum<E>>} or {@code T extends Comparable<T>} would otherwise
     * loop forever: resolving a bound builds a {@link ReferenceTypeImpl}, whose constructor
     * eagerly derives that declaration's type parameters, re-parsing the same signature and
     * hitting the same self-referential bound. While a given FQN is being parsed on this
     * thread, nested requests for the same FQN return no type parameters, breaking the cycle.
     */
    private static final ThreadLocal<java.util.Set<String>> PARSING =
            ThreadLocal.withInitial(java.util.HashSet::new);

    public static List<ResolvedTypeParameterDeclaration> parseClassTypeParameters(
            String signature, String containerQName, TypeSolver solver) {
        if (signature == null || signature.isEmpty()) return Collections.emptyList();
        java.util.Set<String> inProgress = PARSING.get();
        if (containerQName != null && !inProgress.add(containerQName)) {
            // Already parsing this container's type parameters higher in the stack.
            return Collections.emptyList();
        }
        try {
            TypeParamCollector collector = new TypeParamCollector(containerQName, solver);
            new SignatureReader(signature).accept(collector);
            return collector.result();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        } finally {
            if (containerQName != null) inProgress.remove(containerQName);
        }
    }

    public static List<ResolvedTypeParameterDeclaration> parseMethodTypeParameters(
            String signature, String containerQName, TypeSolver solver) {
        return parseClassTypeParameters(signature, containerQName, solver);
    }

    // ── Method return type / parameter types (generic-aware) ──

    public static ResolvedType parseMethodReturnType(
            String signature, String descriptor, TypeSolver solver) {
        if (signature == null) return null;
        try {
            MethodSignatureCollector c = new MethodSignatureCollector(solver);
            new SignatureReader(signature).accept(c);
            return c.returnType;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static List<ResolvedType> parseMethodParameterTypes(
            String signature, String descriptor, TypeSolver solver) {
        if (signature == null) return null;
        try {
            MethodSignatureCollector c = new MethodSignatureCollector(solver);
            new SignatureReader(signature).accept(c);
            return c.paramTypes;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── Method signature visitor: collects parameter types + return type ──

    private static final class MethodSignatureCollector extends SignatureVisitor {
        private final TypeSolver solver;
        final List<ResolvedType> paramTypes = new ArrayList<>();
        ResolvedType returnType;

        MethodSignatureCollector(TypeSolver solver) {
            super(Opcodes.ASM9);
            this.solver = solver;
        }

        @Override
        public void visitFormalTypeParameter(String name) {}

        @Override
        public SignatureVisitor visitClassBound() { return IGNORE; }

        @Override
        public SignatureVisitor visitInterfaceBound() { return IGNORE; }

        @Override
        public SignatureVisitor visitParameterType() {
            return new TypeCollector(solver, t -> paramTypes.add(t));
        }

        @Override
        public SignatureVisitor visitReturnType() {
            return new TypeCollector(solver, t -> returnType = t);
        }

        @Override
        public SignatureVisitor visitExceptionType() { return IGNORE; }
        @Override
        public SignatureVisitor visitSuperclass() { return IGNORE; }
        @Override
        public SignatureVisitor visitInterface() { return IGNORE; }
    }

    // ── Recursively builds a ResolvedType from a signature fragment ──

    private static final class TypeCollector extends SignatureVisitor {
        private final TypeSolver solver;
        private final java.util.function.Consumer<ResolvedType> sink;

        private String classFqn;
        private List<ResolvedType> typeArgs;
        private boolean isArray;
        private TypeCollector arrayElementCollector;

        TypeCollector(TypeSolver solver, java.util.function.Consumer<ResolvedType> sink) {
            super(Opcodes.ASM9);
            this.solver = solver;
            this.sink = sink;
        }

        @Override
        public void visitBaseType(char descriptor) {
            ResolvedType t = switch (descriptor) {
                case 'V' -> ResolvedVoidType.INSTANCE;
                case 'I' -> ResolvedPrimitiveType.INT;
                case 'J' -> ResolvedPrimitiveType.LONG;
                case 'Z' -> ResolvedPrimitiveType.BOOLEAN;
                case 'D' -> ResolvedPrimitiveType.DOUBLE;
                case 'F' -> ResolvedPrimitiveType.FLOAT;
                case 'B' -> ResolvedPrimitiveType.BYTE;
                case 'S' -> ResolvedPrimitiveType.SHORT;
                case 'C' -> ResolvedPrimitiveType.CHAR;
                default -> throw new IllegalArgumentException("Unknown base type: " + descriptor);
            };
            sink.accept(t);
        }

        @Override
        public void visitTypeVariable(String name) {
            ResolvedTypeParameterDeclaration tp =
                    typeParam(name, "", List.of(), solver);
            sink.accept(new ResolvedTypeVariable(tp));
        }

        @Override
        public SignatureVisitor visitArrayType() {
            arrayElementCollector = new TypeCollector(solver, t -> sink.accept(new ResolvedArrayType(t)));
            return arrayElementCollector;
        }

        @Override
        public void visitClassType(String name) {
            classFqn = name.replace('/', '.');
            typeArgs = new ArrayList<>();
        }

        @Override
        public void visitTypeArgument() {
            // unbounded wildcard '?'
            typeArgs.add(ResolvedWildcard.UNBOUNDED);
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return new TypeCollector(solver, argType -> {
                switch (wildcard) {
                    case '=' -> typeArgs.add(argType);
                    case '+' -> typeArgs.add(ResolvedWildcard.extendsBound(argType));
                    case '-' -> typeArgs.add(ResolvedWildcard.superBound(argType));
                }
            });
        }

        @Override
        public void visitEnd() {
            if (classFqn == null) return;
            try {
                SymbolReference<ResolvedReferenceTypeDeclaration> ref =
                        solver.getRoot().tryToSolveType(classFqn);
                ResolvedReferenceTypeDeclaration decl = ref.isSolved()
                        ? ref.getCorrespondingDeclaration()
                        : new AsmDescriptorParser.UnsolvedTypeDeclaration(classFqn);
                if (typeArgs.isEmpty()) {
                    sink.accept(new ReferenceTypeImpl(decl));
                } else {
                    sink.accept(new ReferenceTypeImpl(decl, typeArgs));
                }
            } catch (RuntimeException e) {
                sink.accept(new ReferenceTypeImpl(
                        new AsmDescriptorParser.UnsolvedTypeDeclaration(classFqn)));
            }
            classFqn = null;
            typeArgs = null;
        }
    }

    // ── Type parameter collector (for class/method formal type params) ──

    private static final class TypeParamCollector extends SignatureVisitor {
        private final String containerQName;
        private final TypeSolver solver;
        private final List<ResolvedTypeParameterDeclaration> params = new ArrayList<>();

        private String currentName;
        private List<Bound> currentBounds;

        TypeParamCollector(String containerQName, TypeSolver solver) {
            super(Opcodes.ASM9);
            this.containerQName = containerQName;
            this.solver = solver;
        }

        List<ResolvedTypeParameterDeclaration> result() {
            flushCurrent();
            return params;
        }

        @Override
        public void visitFormalTypeParameter(String name) {
            flushCurrent();
            currentName = name;
            currentBounds = new ArrayList<>();
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return new BoundCollector();
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return new BoundCollector();
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            flushCurrent();
            return IGNORE;
        }

        @Override public SignatureVisitor visitInterface() { return IGNORE; }
        @Override public SignatureVisitor visitParameterType() { return IGNORE; }
        @Override public SignatureVisitor visitReturnType() { return IGNORE; }
        @Override public SignatureVisitor visitExceptionType() { return IGNORE; }

        private void flushCurrent() {
            if (currentName != null) {
                List<Bound> bounds = currentBounds != null ? currentBounds : List.of();
                params.add(typeParam(
                        currentName, containerQName, bounds, solver));
                currentName = null;
                currentBounds = null;
            }
        }

        private class BoundCollector extends SignatureVisitor {
            private String boundFqn;

            BoundCollector() { super(Opcodes.ASM9); }

            @Override
            public void visitClassType(String name) {
                boundFqn = name.replace('/', '.');
            }

            @Override
            public void visitEnd() {
                if (boundFqn != null && currentBounds != null
                        && !"java.lang.Object".equals(boundFqn)) {
                    try {
                        SymbolReference<ResolvedReferenceTypeDeclaration> ref =
                                solver.getRoot().tryToSolveType(boundFqn);
                        if (ref.isSolved()) {
                            currentBounds.add(Bound.extendsBound(
                                    new ReferenceTypeImpl(ref.getCorrespondingDeclaration())));
                        }
                    } catch (RuntimeException ignored) {}
                }
            }

            @Override
            public SignatureVisitor visitTypeArgument(char wildcard) { return IGNORE; }
        }
    }

    static final SignatureVisitor IGNORE = new SignatureVisitor(Opcodes.ASM9) {
        @Override public SignatureVisitor visitClassBound() { return this; }
        @Override public SignatureVisitor visitInterfaceBound() { return this; }
        @Override public SignatureVisitor visitSuperclass() { return this; }
        @Override public SignatureVisitor visitInterface() { return this; }
        @Override public SignatureVisitor visitParameterType() { return this; }
        @Override public SignatureVisitor visitReturnType() { return this; }
        @Override public SignatureVisitor visitExceptionType() { return this; }
        @Override public SignatureVisitor visitTypeArgument(char wildcard) { return this; }
        @Override public SignatureVisitor visitArrayType() { return this; }
    };
}
