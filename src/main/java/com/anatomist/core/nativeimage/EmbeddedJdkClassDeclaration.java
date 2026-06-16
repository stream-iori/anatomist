package com.anatomist.core.nativeimage;

import com.anatomist.core.asmsolver.AsmDescriptorParser;
import com.anatomist.core.asmsolver.AsmSignatureParser;
import com.anatomist.core.asmsolver.FqnUtil;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationMemberDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedClassDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedEnumDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedInterfaceDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.Context;
import com.github.javaparser.resolution.logic.MethodResolutionCapability;
import com.github.javaparser.resolution.logic.MethodResolutionLogic;
import com.github.javaparser.symbolsolver.core.resolution.MethodUsageResolutionCapability;
import com.github.javaparser.symbolsolver.javaparsermodel.contexts.ContextHelper;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** {@link ResolvedReferenceTypeDeclaration} backed by a {@link JdkType} record
 *  from the embedded JDK catalog. Sibling of
 *  {@link com.anatomist.core.asmsolver.AsmClassDeclaration} — same javaparser
 *  interface, different bytecode source. */
public class EmbeddedJdkClassDeclaration implements ResolvedReferenceTypeDeclaration,
        ResolvedClassDeclaration,
        ResolvedInterfaceDeclaration,
        ResolvedAnnotationDeclaration,
        MethodResolutionCapability,
        MethodUsageResolutionCapability {

    private final JdkType type;
    private final TypeSolver solver;
    private volatile List<ResolvedTypeParameterDeclaration> cachedTypeParams;

    public EmbeddedJdkClassDeclaration(JdkType type, TypeSolver solver) {
        this.type = type;
        this.solver = solver;
    }

    // ── identity ──

    @Override
    public String getQualifiedName() { return type.fqn; }

    @Override
    public String getName() { return FqnUtil.simpleName(type.fqn); }

    @Override
    public String getPackageName() { return FqnUtil.packageName(type.fqn); }

    @Override
    public String getClassName() { return FqnUtil.className(type.fqn); }

    // ── kind ──

    @Override
    public boolean isClass() {
        return (type.flags & JdkType.FLAG_INTERFACE) == 0
                && (type.flags & JdkType.FLAG_ENUM) == 0
                && (type.flags & JdkType.FLAG_ANNOTATION) == 0;
    }

    @Override
    public boolean isInterface() {
        return (type.flags & JdkType.FLAG_INTERFACE) != 0
                && (type.flags & JdkType.FLAG_ANNOTATION) == 0;
    }

    @Override
    public boolean isEnum() {
        return (type.flags & JdkType.FLAG_ENUM) != 0;
    }

    @Override
    public boolean isAnnotation() {
        return (type.flags & JdkType.FLAG_ANNOTATION) != 0;
    }

    @Override
    public boolean isRecord() {
        return (type.flags & JdkType.FLAG_RECORD) != 0;
    }

    @Override
    public boolean isTypeParameter() { return false; }
    @Override public boolean isField() { return false; }
    @Override public boolean isParameter() { return false; }
    @Override public boolean isType() { return true; }

    @Override
    @SuppressWarnings("unchecked")
    public ResolvedClassDeclaration asClass() { return this; }

    @Override
    @SuppressWarnings("unchecked")
    public ResolvedInterfaceDeclaration asInterface() { return this; }

    @Override
    @SuppressWarnings("unchecked")
    public ResolvedAnnotationDeclaration asAnnotation() { return this; }

    @Override
    @SuppressWarnings("unchecked")
    public ResolvedEnumDeclaration asEnum() {
        throw new UnsupportedOperationException(this + " is not an enum");
    }

    // ── hierarchy ──

    public Optional<ResolvedReferenceType> getSuperClass() {
        if (type.superFqn == null) return Optional.empty();
        var ref = solver.getRoot().tryToSolveType(type.superFqn);
        if (!ref.isSolved()) return Optional.empty();
        return Optional.of(new ReferenceTypeImpl(ref.getCorrespondingDeclaration()));
    }

    public List<ResolvedReferenceType> getInterfaces() {
        List<ResolvedReferenceType> out = new ArrayList<>();
        for (String iFqn : type.interfaceFqns) {
            var ref = solver.getRoot().tryToSolveType(iFqn);
            if (ref.isSolved()) out.add(new ReferenceTypeImpl(ref.getCorrespondingDeclaration()));
        }
        return out;
    }

    // ── ResolvedClassDeclaration extras ──

    @Override
    public List<ResolvedReferenceType> getAllSuperClasses() {
        List<ResolvedReferenceType> out = new ArrayList<>();
        getSuperClass().ifPresent(sc -> {
            out.add(sc);
            sc.getTypeDeclaration()
                    .filter(td -> td instanceof ResolvedClassDeclaration)
                    .map(td -> ((ResolvedClassDeclaration) td).getAllSuperClasses())
                    .ifPresent(out::addAll);
        });
        return out;
    }

    @Override
    public List<ResolvedReferenceType> getAllInterfaces() {
        List<ResolvedReferenceType> out = new ArrayList<>(getInterfaces());
        for (ResolvedReferenceType i : getInterfaces()) {
            i.getTypeDeclaration().ifPresent(td -> out.addAll(td.getAllAncestors()));
        }
        getSuperClass().flatMap(rt -> rt.getTypeDeclaration())
                .filter(td -> td instanceof ResolvedClassDeclaration)
                .map(td -> ((ResolvedClassDeclaration) td).getAllInterfaces())
                .ifPresent(out::addAll);
        return out;
    }

    // ── ResolvedInterfaceDeclaration extras ──

    @Override
    public List<ResolvedReferenceType> getInterfacesExtended() {
        return getInterfaces();
    }

    @Override
    public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) {
        List<ResolvedReferenceType> out = new ArrayList<>();
        getSuperClass().ifPresent(out::add);
        out.addAll(getInterfaces());
        return out;
    }

    @Override
    public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) {
        if (other == null) return false;
        if (other.getQualifiedName().equals(type.fqn)) return true;
        for (ResolvedReferenceType anc : other.getAllAncestors()) {
            if (type.fqn.equals(anc.getQualifiedName())) return true;
        }
        return false;
    }

    @Override
    public boolean isAssignableBy(ResolvedType t) {
        if (!t.isReferenceType()) return false;
        ResolvedReferenceType ref = t.asReferenceType();
        if (ref.getQualifiedName().equals(type.fqn)) return true;
        return ref.getTypeDeclaration().map(this::isAssignableBy).orElse(false);
    }

    // ── members ──

    @Override
    public List<ResolvedFieldDeclaration> getDeclaredFields() {
        List<ResolvedFieldDeclaration> out = new ArrayList<>();
        for (JdkType.FieldEntry f : type.fields) {
            out.add(new EmbeddedJdkFieldDeclaration(f, this, solver));
        }
        return out;
    }

    @Override
    public List<ResolvedFieldDeclaration> getAllFields() {
        List<ResolvedFieldDeclaration> out = new ArrayList<>(getDeclaredFields());
        getSuperClass().flatMap(rt -> rt.getTypeDeclaration())
                .ifPresent(td -> out.addAll(td.getAllFields()));
        return out;
    }

    @Override
    public Set<ResolvedMethodDeclaration> getDeclaredMethods() {
        Set<ResolvedMethodDeclaration> out = new LinkedHashSet<>();
        for (JdkType.MethodEntry m : type.methods) {
            if ("<init>".equals(m.name) || "<clinit>".equals(m.name)) continue;
            out.add(new EmbeddedJdkMethodDeclaration(m, this, solver));
        }
        return out;
    }

    @Override
    public Set<MethodUsage> getAllMethods() {
        Set<MethodUsage> out = new LinkedHashSet<>();
        for (ResolvedMethodDeclaration m : getDeclaredMethods()) out.add(new MethodUsage(m));
        getSuperClass().flatMap(rt -> rt.getTypeDeclaration())
                .ifPresent(td -> out.addAll(td.getAllMethods()));
        for (ResolvedReferenceType i : getInterfaces()) {
            i.getTypeDeclaration().ifPresent(td -> out.addAll(td.getAllMethods()));
        }
        return out;
    }

    @Override
    public List<ResolvedConstructorDeclaration> getConstructors() {
        List<ResolvedConstructorDeclaration> out = new ArrayList<>();
        for (JdkType.MethodEntry m : type.methods) {
            if ("<init>".equals(m.name)) {
                out.add(new EmbeddedJdkConstructorDeclaration(m, this, solver));
            }
        }
        return out;
    }

    @Override
    public Set<ResolvedReferenceTypeDeclaration> internalTypes() {
        return Collections.emptySet();
    }

    @Override
    public Optional<ResolvedReferenceTypeDeclaration> containerType() {
        return Optional.empty();
    }

    @Override
    public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
        if (type.signature == null) return Collections.emptyList();
        if (cachedTypeParams != null) return cachedTypeParams;
        // Set empty first to break recursion: ReferenceTypeImpl constructor calls
        // deriveParams() → getTypeParameters() on bound types, which would recurse.
        cachedTypeParams = Collections.emptyList();
        cachedTypeParams = AsmSignatureParser.parseClassTypeParameters(type.signature, type.fqn, solver);
        return cachedTypeParams;
    }

    @Override
    public boolean isFunctionalInterface() {
        // Not derivable from erased descriptors alone. Conservative: false.
        return false;
    }

    @Override
    public Set<ResolvedAnnotationDeclaration> getDeclaredAnnotations() {
        return Collections.emptySet();
    }

    // ── ResolvedAnnotationDeclaration ──

    @Override
    public List<ResolvedAnnotationMemberDeclaration> getAnnotationMembers() {
        return Collections.emptyList();
    }

    @Override
    public boolean isInheritable() {
        return false;
    }

    @Override
    public boolean hasDirectlyAnnotation(String qualifiedName) {
        // Catalog doesn't (yet) record annotations on JDK types. anatomist's
        // annotation extraction comes from user source via JavaParserTypeSolver.
        return false;
    }

    // ── MethodResolutionCapability ──

    @Override
    public SymbolReference<ResolvedMethodDeclaration> solveMethod(
            String name, List<ResolvedType> argumentTypes, boolean staticOnly) {
        List<ResolvedMethodDeclaration> candidates = new ArrayList<>();
        for (MethodUsage mu : getAllMethods()) {
            ResolvedMethodDeclaration m = mu.getDeclaration();
            if (m.getName().equals(name) && (!staticOnly || m.isStatic())) candidates.add(m);
        }
        return MethodResolutionLogic.findMostApplicable(candidates, name, argumentTypes, solver);
    }

    // ── MethodUsageResolutionCapability ──

    @Override
    public Optional<MethodUsage> solveMethodAsUsage(
            String name, List<ResolvedType> argumentTypes,
            Context invocationContext, List<ResolvedType> typeParameterValues) {
        List<MethodUsage> methodUsages = new ArrayList<>();
        for (ResolvedMethodDeclaration m : getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            MethodUsage mu = new MethodUsage(m);
            for (int i = 0; i < getTypeParameters().size() && i < typeParameterValues.size(); i++) {
                mu = mu.replaceTypeParameter(getTypeParameters().get(i), typeParameterValues.get(i));
            }
            methodUsages.add(mu);
            if (argumentTypes.isEmpty() && mu.getNoParams() == 0) {
                return Optional.of(mu);
            }
        }
        getSuperClass().flatMap(rt -> rt.getTypeDeclaration()).ifPresent(td ->
                ContextHelper.solveMethodAsUsage(td, name, argumentTypes, invocationContext, typeParameterValues)
                        .ifPresent(methodUsages::add));
        for (ResolvedReferenceType iface : getInterfaces()) {
            iface.getTypeDeclaration().ifPresent(td ->
                    ContextHelper.solveMethodAsUsage(td, name, argumentTypes, invocationContext, typeParameterValues)
                            .ifPresent(methodUsages::add));
        }
        return MethodResolutionLogic.findMostApplicableUsage(methodUsages, name, argumentTypes, solver);
    }

    public AccessSpecifier accessSpecifier() {
        if ((type.flags & JdkType.FLAG_PUBLIC) != 0) return AccessSpecifier.PUBLIC;
        return AccessSpecifier.NONE;
    }

    JdkType jdkType() { return type; }
    TypeSolver solver() { return solver; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmbeddedJdkClassDeclaration that)) return false;
        return type.fqn.equals(that.type.fqn);
    }

    @Override
    public int hashCode() { return type.fqn.hashCode(); }

    @Override
    public String toString() { return "EmbeddedJdkClassDeclaration(" + type.fqn + ")"; }
}
