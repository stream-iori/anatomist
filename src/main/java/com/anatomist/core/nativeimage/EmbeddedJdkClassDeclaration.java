package com.anatomist.core.nativeimage;

import com.anatomist.core.asmsolver.AsmDescriptorParser;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.logic.MethodResolutionCapability;
import com.github.javaparser.resolution.logic.MethodResolutionLogic;
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
        MethodResolutionCapability {

    private final JdkType type;
    private final TypeSolver solver;

    public EmbeddedJdkClassDeclaration(JdkType type, TypeSolver solver) {
        this.type = type;
        this.solver = solver;
    }

    // ── identity ──

    @Override
    public String getQualifiedName() { return type.fqn; }

    @Override
    public String getName() {
        int dot = type.fqn.lastIndexOf('.');
        int dollar = type.fqn.lastIndexOf('$');
        int idx = Math.max(dot, dollar);
        return idx < 0 ? type.fqn : type.fqn.substring(idx + 1);
    }

    @Override
    public String getPackageName() {
        int dot = type.fqn.lastIndexOf('.');
        return dot < 0 ? "" : type.fqn.substring(0, dot);
    }

    @Override
    public String getClassName() {
        String pkg = getPackageName();
        if (pkg.isEmpty()) return type.fqn;
        return type.fqn.substring(pkg.length() + 1);
    }

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
        // The catalog doesn't (yet) record ctors separately. JdkTypeCatalogBuilder
        // would route <init> into MethodEntry today — they'd appear in
        // getDeclaredMethods. Acceptable for anatomist's usage: ctor-as-call edges
        // are emitted from JavaParserTypeSolver (source side), not the JDK side.
        return Collections.emptyList();
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
        return Collections.emptyList();
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
        for (ResolvedMethodDeclaration m : getDeclaredMethods()) {
            if (m.getName().equals(name) && (!staticOnly || m.isStatic())) candidates.add(m);
        }
        getSuperClass().flatMap(rt -> rt.getTypeDeclaration()).ifPresent(td -> {
            for (ResolvedMethodDeclaration m : td.getDeclaredMethods()) {
                if (m.getName().equals(name) && (!staticOnly || m.isStatic())) candidates.add(m);
            }
        });
        for (ResolvedReferenceType i : getInterfaces()) {
            i.getTypeDeclaration().ifPresent(td -> {
                for (ResolvedMethodDeclaration m : td.getDeclaredMethods()) {
                    if (m.getName().equals(name) && (!staticOnly || m.isStatic())) candidates.add(m);
                }
            });
        }
        return MethodResolutionLogic.findMostApplicable(candidates, name, argumentTypes, solver);
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
