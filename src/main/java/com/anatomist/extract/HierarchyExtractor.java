package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.anatomist.core.NodeIdGenerator.erasedTypeDescribe;

public class HierarchyExtractor implements Extractor {

    private final ExtractionContext ctx;

    public HierarchyExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;

        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                emitTypeAncestry(n, result);
                emitOverrides(n, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                emitTypeAncestry(n, result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitTypeAncestry(com.github.javaparser.ast.body.TypeDeclaration<?> decl,
                                  ExtractionResult result) {
        ResolvedReferenceTypeDeclaration rt;
        try { rt = decl.resolve(); }
        catch (RuntimeException e) {
            ctx.incrementUnresolved(e);
            if (decl instanceof ClassOrInterfaceDeclaration cid) {
                emitTypeAncestryFallback(cid, result);
            }
            return;
        }
        String sourceId = ctx.idGenerator().forType(rt);
        boolean isInterface = rt.isInterface();

        // Superclass (only meaningful for class declarations; interfaces and
        // enums special-case as below).
        if (!isInterface) {
            try {
                rt.asClass().getSuperClass()
                        .filter(s -> !"java.lang.Object".equals(s.getQualifiedName()))
                        .filter(s -> !"java.lang.Enum".equals(s.getQualifiedName()))
                        .ifPresent(s -> result.edges.add(
                                hierarchyEdge(sourceId, s, "INHERITS")));
            } catch (RuntimeException ignore) { ctx.incrementUnresolved(ignore); }
        }

        // Direct interfaces / parent interfaces.
        try {
            List<ResolvedReferenceType> ifs = isInterface
                    ? rt.asInterface().getInterfacesExtended()
                    : rt.asClass().getInterfaces();
            for (ResolvedReferenceType i : ifs) {
                String relation = isInterface ? "INHERITS" : "IMPLEMENTS";
                result.edges.add(hierarchyEdge(sourceId, i, relation));
            }
        } catch (RuntimeException ignore) { ctx.incrementUnresolved(ignore); }
    }

    private Edge hierarchyEdge(String sourceId, ResolvedReferenceType target, String relation) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.relation = relation;
        e.confidence = "EXTRACTED";

        ResolvedReferenceTypeDeclaration t = target.getTypeDeclaration().orElse(null);
        if (t != null && ctx.isProjectInternal(t)) {
            e.targetId = ctx.idGenerator().forType(t);
            e.isExternal = false;
        } else {
            e.externalTargetFqn = target.getQualifiedName();
            e.isExternal = true;
        }
        return e;
    }

    private void emitOverrides(ClassOrInterfaceDeclaration decl, ExtractionResult result) {
        if (decl.isInterface()) return; // skip — interfaces overriding interface methods is rarely meaningful here
        ResolvedReferenceTypeDeclaration rt;
        try { rt = decl.resolve(); }
        catch (RuntimeException e) {
            ctx.incrementUnresolved(e);
            emitInterfaceOverridesFallback(decl, result);
            return;
        }

        // Gather candidate super methods (BFS via getAllAncestors, dedup by FQN+erased-signature).
        List<ResolvedMethodDeclaration> superMethods;
        try {
            superMethods = rt.getAllAncestors().stream()
                    .flatMap(anc -> anc.getTypeDeclaration().stream())
                    .flatMap(td -> td.getDeclaredMethods().stream())
                    .filter(m -> m.accessSpecifier() != com.github.javaparser.ast.AccessSpecifier.PRIVATE
                              && !m.isStatic())
                    .collect(Collectors.toList());
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            emitInterfaceOverridesFallback(decl, result);
            return;
        }

        for (MethodDeclaration md : decl.getMethods()) {
            ResolvedMethodDeclaration sub;
            try { sub = md.resolve(); }
            catch (RuntimeException e) { ctx.incrementUnresolved(e); continue; }

            String subSig = methodSignatureKey(sub);
            Set<String> seenTargets = new HashSet<>();
            for (ResolvedMethodDeclaration sup : superMethods) {
                if (!sub.getName().equals(sup.getName())) continue;
                if (!methodSignatureKey(sup).equals(subSig)) continue;
                String supId = NodeIdGenerator.externalMethodFqn(sup);
                if (!seenTargets.add(supId)) continue;
                result.edges.add(overrideEdge(sub, sup));
            }
        }
    }

    private static String methodSignatureKey(ResolvedMethodDeclaration m) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < m.getNumberOfParams(); i++) {
            if (i > 0) sb.append(',');
            ResolvedType pt;
            try { pt = m.getParam(i).getType(); }
            catch (RuntimeException e) { sb.append("<unknown>"); continue; }
            sb.append(erasedTypeDescribe(pt));
        }
        return sb.toString();
    }

    private Edge overrideEdge(ResolvedMethodDeclaration sub, ResolvedMethodDeclaration sup) {
        Edge e = new Edge();
        e.sourceId = ctx.idGenerator().forMethod(sub);
        e.relation = "OVERRIDES";
        e.confidence = "EXTRACTED";
        if (ctx.isProjectInternal(sup.declaringType())) {
            e.targetId = ctx.idGenerator().forMethod(sup);
            e.isExternal = false;
        } else {
            e.externalTargetFqn = NodeIdGenerator.externalMethodFqn(sup);
            e.isExternal = true;
        }
        return e;
    }

    private void emitTypeAncestryFallback(ClassOrInterfaceDeclaration decl, ExtractionResult result) {
        String sourceId = typeIdFallback(decl);
        if (sourceId == null) return;
        for (ClassOrInterfaceType iface : decl.getImplementedTypes()) {
            String target = resolveTypeName(decl, iface.getNameAsString());
            if (target != null) result.edges.add(hierarchyEdgeFallback(sourceId, target, "IMPLEMENTS"));
        }
        for (ClassOrInterfaceType parent : decl.getExtendedTypes()) {
            String target = resolveTypeName(decl, parent.getNameAsString());
            if (target != null) result.edges.add(hierarchyEdgeFallback(sourceId, target, "INHERITS"));
        }
    }

    private void emitInterfaceOverridesFallback(ClassOrInterfaceDeclaration decl, ExtractionResult result) {
        String sourceType = typeIdFallback(decl);
        if (sourceType == null) return;
        Set<String> seen = new HashSet<>();
        for (ClassOrInterfaceType iface : decl.getImplementedTypes()) {
            String ifaceType = resolveTypeName(decl, iface.getNameAsString());
            if (ifaceType == null || looksExternal(ifaceType)) continue;
            if (!isKnownInternalInterface(decl, iface.getNameAsString(), ifaceType)) continue;
            for (MethodDeclaration method : decl.getMethods()) {
                String source = methodIdFallback(method, sourceType);
                if (source == null) continue;
                String target = ifaceType + "#" + method.getNameAsString()
                        + "(" + methodSignatureKey(method) + ")";
                String key = source + "->" + target;
                if (!seen.add(key)) continue;

                Edge e = new Edge();
                e.sourceId = source;
                e.targetId = target;
                e.relation = "OVERRIDES";
                e.confidence = "INFERRED";
                e.isExternal = false;
                result.edges.add(e);
            }
        }
    }

    private Edge hierarchyEdgeFallback(String sourceId, String targetType, String relation) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.relation = relation;
        e.confidence = "INFERRED";
        if (looksExternal(targetType)) {
            e.externalTargetFqn = targetType;
            e.isExternal = true;
        } else {
            e.targetId = targetType;
            e.isExternal = false;
        }
        return e;
    }

    private String methodIdFallback(MethodDeclaration method, String sourceType) {
        try { return ctx.idGenerator().forMethod(method.resolve()); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); }
        return sourceType + "#" + method.getNameAsString()
                + "(" + methodSignatureKey(method) + ")";
    }

    private static String methodSignatureKey(MethodDeclaration method) {
        return method.getParameters().stream()
                .map(p -> {
                    try { return erasedTypeDescribe(p.getType().resolve()); }
                    catch (RuntimeException e) { return AstTypeNames.of(p.getType(), p); }
                })
                .collect(Collectors.joining(","));
    }

    private String typeIdFallback(ClassOrInterfaceDeclaration decl) {
        try { return ctx.idGenerator().forType(decl.resolve()); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); }
        Optional<CompilationUnit> cuOpt = decl.findCompilationUnit();
        String pkg = cuOpt.flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString() + ".")
                .orElse("");
        return pkg + decl.getNameAsString();
    }

    private static String resolveTypeName(ClassOrInterfaceDeclaration decl, String simpleName) {
        if (simpleName == null || simpleName.isBlank()) return null;
        return AstTypeNames.qualifySimpleName(decl, simpleName);
    }

    private static boolean isKnownInternalInterface(ClassOrInterfaceDeclaration decl,
                                                    String simpleName,
                                                    String resolvedName) {
        Optional<CompilationUnit> cuOpt = decl.findCompilationUnit();
        if (cuOpt.isEmpty()) return false;
        CompilationUnit cu = cuOpt.get();

        boolean localInterface = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .anyMatch(t -> t.isInterface() && t.getNameAsString().equals(simpleName));
        if (localInterface) return true;

        return cu.getImports().stream()
                .filter(i -> !i.isAsterisk())
                .map(i -> i.getNameAsString())
                .anyMatch(imported -> imported.equals(resolvedName) && !looksExternal(imported));
    }

    private static boolean looksExternal(String fqn) {
        return fqn.startsWith("java.")
                || fqn.startsWith("javax.")
                || fqn.startsWith("jakarta.")
                || fqn.startsWith("org.")
                || fqn.startsWith("com.github.")
                || fqn.startsWith("com.google.")
                || fqn.startsWith("com.alibaba.")
                || fqn.startsWith("com.alipay.");
    }
}
