package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationMemberDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.logic.MethodResolutionCapability;
import com.github.javaparser.resolution.logic.MethodResolutionLogic;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** ASM-backed implementation of {@link ResolvedReferenceTypeDeclaration}. */
public class AsmClassDeclaration implements ResolvedReferenceTypeDeclaration,
        ResolvedAnnotationDeclaration,
        MethodResolutionCapability {

    private final String fqn;
    /** Raw bytes are needed only until the lazy ASM pass completes. */
    private byte[] classBytes;
    private final int rawByteSize;
    private final TypeSolver solver;

    // Parsed lazily from ASM on first need.
    private boolean parsed;
    private String superFqn;        // null for Object/interfaces
    private List<String> interfaceFqns = Collections.emptyList();
    private int classAccess;
    private String classSignature;
    private List<AsmFieldDeclaration> declaredFields = Collections.emptyList();
    private Set<AsmMethodDeclaration> declaredMethods = Collections.emptySet();
    private List<AsmConstructorDeclaration> constructors = Collections.emptyList();
    private Set<String> directAnnotationFqns = Collections.emptySet();
    private List<String> nestedTypeFqns = Collections.emptyList();

    public AsmClassDeclaration(String fqn, byte[] classBytes, TypeSolver solver) {
        this.fqn = fqn;
        this.classBytes = classBytes;
        this.rawByteSize = classBytes == null ? 0 : classBytes.length;
        this.solver = solver;
    }

    AsmClassDeclaration(AsmTypeMetadata metadata, TypeSolver solver) {
        this.fqn = metadata.fqn();
        this.classBytes = null;
        this.rawByteSize = 0;
        this.solver = solver;
        this.superFqn = metadata.superFqn();
        this.interfaceFqns = List.copyOf(metadata.interfaceFqns());
        this.classAccess = metadata.access();
        this.classSignature = metadata.signature();
        this.declaredFields = metadata.fields().stream()
                .map(field -> new AsmFieldDeclaration(field.name(), field.descriptor(),
                        field.access(), this)).toList();
        this.declaredMethods = metadata.methods().stream()
                .map(method -> new AsmMethodDeclaration(method.name(), method.access(), this,
                        new LazyDescriptorResolver(method.signature(), method.descriptor(), solver)))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        Collections::unmodifiableSet));
        this.constructors = metadata.constructors().stream()
                .map(constructor -> new AsmConstructorDeclaration(constructor.descriptor(),
                        constructor.access(), this)).toList();
        this.directAnnotationFqns = Set.copyOf(metadata.annotations());
        this.nestedTypeFqns = List.copyOf(metadata.nestedTypes());
        this.parsed = true;
    }

    // ── identity ──

    @Override
    public String getQualifiedName() {
        return fqn;
    }

    @Override
    public String getName() { return FqnUtil.simpleName(fqn); }

    @Override
    public String getPackageName() { return FqnUtil.packageName(fqn); }

    @Override
    public String getClassName() { return FqnUtil.className(fqn); }

    // ── kind ──

    @Override
    public boolean isClass() {
        ensureParsed();
        return !AccessFlags.isInterface(classAccess)
                && !AccessFlags.isEnum(classAccess)
                && !AccessFlags.isAnnotation(classAccess);
    }

    @Override
    public boolean isInterface() {
        ensureParsed();
        return AccessFlags.isInterface(classAccess)
                && !AccessFlags.isAnnotation(classAccess);
    }

    @Override
    public boolean isEnum() {
        ensureParsed();
        return AccessFlags.isEnum(classAccess);
    }

    @Override
    public boolean isAnnotation() {
        ensureParsed();
        return AccessFlags.isAnnotation(classAccess);
    }

    @Override
    public boolean isRecord() {
        ensureParsed();
        return AccessFlags.isRecord(classAccess);
    }

    @Override
    public boolean isTypeParameter() { return false; }

    @Override
    public boolean isField() { return false; }

    @Override
    public boolean isParameter() { return false; }

    @Override
    public boolean isType() { return true; }

    // ── members ──

    @Override
    public List<ResolvedFieldDeclaration> getDeclaredFields() {
        ensureParsed();
        return new ArrayList<>(declaredFields);
    }

    @Override
    public List<ResolvedFieldDeclaration> getAllFields() {
        ensureParsed();
        List<ResolvedFieldDeclaration> out = new ArrayList<>(declaredFields);
        // Inherited fields from super class chain.
        getSuperClass().flatMap(rt -> rt.getTypeDeclaration())
                .ifPresent(td -> out.addAll(td.getAllFields()));
        return out;
    }

    @Override
    public Set<ResolvedMethodDeclaration> getDeclaredMethods() {
        ensureParsed();
        return new LinkedHashSet<>(declaredMethods);
    }

    @Override
    public List<ResolvedConstructorDeclaration> getConstructors() {
        ensureParsed();
        return new ArrayList<>(constructors);
    }

    @Override
    public Set<ResolvedReferenceTypeDeclaration> internalTypes() {
        ensureParsed();
        Set<ResolvedReferenceTypeDeclaration> out = new LinkedHashSet<>();
        for (String nestedFqn : nestedTypeFqns) {
            var ref = solver.getRoot().tryToSolveType(nestedFqn);
            if (ref.isSolved()) out.add(ref.getCorrespondingDeclaration());
        }
        return out;
    }

    @Override
    public boolean hasInternalType(String name) {
        ensureParsed();
        String want = fqn + "$" + name;
        return nestedTypeFqns.contains(want);
    }

    @Override
    public ResolvedReferenceTypeDeclaration getInternalType(String name) {
        ensureParsed();
        String want = fqn + "$" + name;
        if (!nestedTypeFqns.contains(want)) {
            throw new IllegalArgumentException("no internal type named " + name);
        }
        var ref = solver.getRoot().tryToSolveType(want);
        if (!ref.isSolved()) {
            throw new IllegalArgumentException("internal type unresolved: " + want);
        }
        return ref.getCorrespondingDeclaration();
    }

    @Override
    public Optional<ResolvedReferenceTypeDeclaration> containerType() {
        return Optional.empty();
    }

    @Override
    public Set<MethodUsage> getAllMethods() {
        ensureParsed();
        Set<MethodUsage> out = new LinkedHashSet<>();
        // Declared on this type
        for (AsmMethodDeclaration m : declaredMethods) {
            out.add(new MethodUsage(m));
        }
        // Inherited from super
        getSuperClass().flatMap(rt -> rt.getTypeDeclaration())
                .ifPresent(td -> out.addAll(td.getAllMethods()));
        // Inherited from interfaces
        for (ResolvedReferenceType i : getInterfaces()) {
            i.getTypeDeclaration().ifPresent(td -> out.addAll(td.getAllMethods()));
        }
        return out;
    }

    @Override
    public boolean isFunctionalInterface() {
        return false;
    }

    @Override
    public List<ResolvedTypeParameterDeclaration> getTypeParameters() {
        ensureParsed();
        if (classSignature == null) return Collections.emptyList();
        return AsmSignatureParser.parseClassTypeParameters(classSignature, fqn, solver);
    }

    @Override
    public List<ResolvedReferenceType> getAncestors(boolean acceptIncompleteList) {
        ensureParsed();
        java.util.List<ResolvedReferenceType> ancestors = new java.util.ArrayList<>();

        // super class — implicit java.lang.Object when null (interface/Object itself).
        Optional<ResolvedReferenceType> sc = getSuperClass();
        sc.ifPresent(ancestors::add);
        if (sc.isEmpty() && !"java.lang.Object".equals(fqn) && !isInterface()) {
            // No declared super and not Object: defer to acceptIncomplete decision.
            if (!acceptIncompleteList) {
                // strict — silently omit; downstream MethodResolutionLogic handles
            }
        }
        ancestors.addAll(getInterfaces());
        return ancestors;
    }

    /** Resolved super class as a reference type. Empty for {@code java.lang.Object},
     *  interfaces, and synthetic root-like classes whose super is null. */
    public Optional<ResolvedReferenceType> getSuperClass() {
        ensureParsed();
        if (superFqn == null) return Optional.empty();
        var ref = solver.getRoot().tryToSolveType(superFqn);
        if (!ref.isSolved()) return Optional.empty();
        return Optional.of(new ReferenceTypeImpl(ref.getCorrespondingDeclaration()));
    }

    /** Directly declared interfaces (not inherited). */
    public List<ResolvedReferenceType> getInterfaces() {
        ensureParsed();
        java.util.List<ResolvedReferenceType> out = new java.util.ArrayList<>();
        for (String i : interfaceFqns) {
            var ref = solver.getRoot().tryToSolveType(i);
            if (ref.isSolved()) {
                out.add(new ReferenceTypeImpl(ref.getCorrespondingDeclaration()));
            }
        }
        return out;
    }

    @Override
    public boolean isAssignableBy(ResolvedReferenceTypeDeclaration other) {
        if (other == null) return false;
        if (other.getQualifiedName().equals(fqn)) return true;
        // Walk other's ancestor chain — if any ancestor's FQN matches this, true.
        for (ResolvedReferenceType anc : other.getAllAncestors()) {
            if (fqn.equals(anc.getQualifiedName())) return true;
        }
        return false;
    }

    @Override
    public boolean isAssignableBy(ResolvedType type) {
        if (!type.isReferenceType()) return false;
        ResolvedReferenceType ref = type.asReferenceType();
        if (ref.getQualifiedName().equals(fqn)) return true;
        return ref.getTypeDeclaration()
                .map(this::isAssignableBy)
                .orElse(false);
    }

    public AccessSpecifier accessSpecifier() {
        ensureParsed();
        return AccessFlags.toSpecifier(classAccess);
    }

    @Override
    public boolean hasDirectlyAnnotation(String qualifiedName) {
        ensureParsed();
        return directAnnotationFqns.contains(qualifiedName);
    }

    // ── MethodResolutionCapability ──

    @Override
    public SymbolReference<ResolvedMethodDeclaration> solveMethod(
            String name, List<ResolvedType> argumentTypes, boolean staticOnly) {
        ensureParsed();
        List<ResolvedMethodDeclaration> candidates = new ArrayList<>();
        for (AsmMethodDeclaration m : declaredMethods) {
            if (m.getName().equals(name) && (!staticOnly || m.isStatic())) {
                candidates.add(m);
            }
        }
        // Walk super class chain for inherited methods.
        getSuperClass().flatMap(rt -> rt.getTypeDeclaration())
                .ifPresent(td -> {
                    for (ResolvedMethodDeclaration m : td.getDeclaredMethods()) {
                        if (m.getName().equals(name) && (!staticOnly || m.isStatic())) {
                            candidates.add(m);
                        }
                    }
                });
        // And interfaces.
        for (ResolvedReferenceType i : getInterfaces()) {
            i.getTypeDeclaration().ifPresent(td -> {
                for (ResolvedMethodDeclaration m : td.getDeclaredMethods()) {
                    if (m.getName().equals(name) && (!staticOnly || m.isStatic())) {
                        candidates.add(m);
                    }
                }
            });
        }
        return MethodResolutionLogic.findMostApplicable(candidates, name, argumentTypes, solver);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AsmClassDeclaration that)) return false;
        return fqn.equals(that.fqn);
    }

    @Override
    public int hashCode() {
        return fqn.hashCode();
    }

    @Override
    public String toString() {
        return "AsmClassDeclaration(" + fqn + ")";
    }

    // ── internals ──

    /** Lazily extract super + interfaces + access + members + annotations + nested
     *  types via a SINGLE ASM pass. */
    private void ensureParsed() {
        if (parsed) return;
        List<AsmFieldDeclaration> fields = new ArrayList<>();
        Set<AsmMethodDeclaration> methods = new LinkedHashSet<>();
        List<AsmConstructorDeclaration> ctors = new ArrayList<>();
        Set<String> annos = new LinkedHashSet<>();
        List<String> nested = new ArrayList<>();
        String internalSelf = fqn.replace('.', '/');
        byte[] bytes = classBytes;
        if (bytes == null) {
            throw new IllegalStateException("class bytes released before parsing " + fqn);
        }
        ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                AsmClassDeclaration.this.classAccess = access;
                AsmClassDeclaration.this.classSignature = signature;
                AsmClassDeclaration.this.superFqn = (superName == null)
                        ? null
                        : superName.replace('/', '.');
                if (interfaces != null && interfaces.length > 0) {
                    java.util.ArrayList<String> ifaces = new java.util.ArrayList<>(interfaces.length);
                    for (String i : interfaces) ifaces.add(i.replace('/', '.'));
                    AsmClassDeclaration.this.interfaceFqns = List.copyOf(ifaces);
                }
            }

            @Override
            public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                // descriptor is "Lpkg/Foo;" — strip L and ; then dotify
                if (descriptor != null && descriptor.length() > 2
                        && descriptor.charAt(0) == 'L' && descriptor.charAt(descriptor.length() - 1) == ';') {
                    annos.add(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
                }
                return null;
            }

            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                // ASM lists ALL referenced inner classes (not just our own). Only keep
                // those whose outerName matches us — they're nested inside this class.
                if (outerName != null && outerName.equals(internalSelf) && innerName != null) {
                    nested.add(name.replace('/', '.'));
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                            String signature, Object value) {
                if (AccessFlags.isSynthetic(access)) return null;
                fields.add(new AsmFieldDeclaration(name, descriptor, access,
                        AsmClassDeclaration.this));
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (AccessFlags.isSynthetic(access)) return null;
                if (AccessFlags.isBridge(access)) return null;
                if ("<init>".equals(name)) {
                    ctors.add(new AsmConstructorDeclaration(descriptor, access,
                            AsmClassDeclaration.this));
                } else if (!"<clinit>".equals(name)) {
                    methods.add(new AsmMethodDeclaration(name, access,
                            AsmClassDeclaration.this,
                            new LazyDescriptorResolver(signature, descriptor, solver)));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        this.declaredFields = List.copyOf(fields);
        this.declaredMethods = Collections.unmodifiableSet(new LinkedHashSet<>(methods));
        this.constructors = List.copyOf(ctors);
        this.directAnnotationFqns = Set.copyOf(annos);
        this.nestedTypeFqns = List.copyOf(nested);
        parsed = true;
        // Do not retain both the compressed class-file representation and the
        // parsed declaration graph for the lifetime of the type cache.
        classBytes = null;
    }

    // Package-visible accessors for future C-tasks
    String superFqn() { ensureParsed(); return superFqn; }
    List<String> interfaceFqns() { ensureParsed(); return interfaceFqns; }
    int access() { ensureParsed(); return classAccess; }
    TypeSolver solver() { return solver; }
    byte[] classBytes() { return classBytes; }
    int estimatedCacheWeight() {
        // Guava computes weight on insertion, before lazy parsing. Raw class
        // size is a useful conservative proxy for the later member graph.
        return Math.max(256, rawByteSize + fqn.length() * 2 + 128);
    }
    boolean rawBytesReleased() { return classBytes == null; }
    AsmTypeMetadata metadataIfParsed() {
        if (!parsed) return null;
        List<AsmTypeMetadata.Field> fields = declaredFields.stream()
                .map(field -> new AsmTypeMetadata.Field(field.getName(), field.descriptor(), field.access()))
                .toList();
        List<AsmTypeMetadata.Method> methods = declaredMethods.stream()
                .map(method -> new AsmTypeMetadata.Method(method.getName(), method.toDescriptor(),
                        method.signature(), method.access())).toList();
        List<AsmTypeMetadata.Constructor> ctors = constructors.stream()
                .map(constructor -> new AsmTypeMetadata.Constructor(constructor.descriptor(),
                        constructor.access())).toList();
        return new AsmTypeMetadata(fqn, superFqn, interfaceFqns, classAccess, classSignature,
                fields, methods, ctors, List.copyOf(directAnnotationFqns), nestedTypeFqns);
    }

    // ── ResolvedAnnotationDeclaration ───────────────────────────────────

    @Override
    public List<ResolvedAnnotationMemberDeclaration> getAnnotationMembers() {
        return Collections.emptyList();
    }

    @Override
    public boolean isInheritable() {
        ensureParsed();
        return directAnnotationFqns.contains("java.lang.annotation.Inherited");
    }
}
