package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HierarchyExtractor implements Extractor {

    private final ExtractionContext ctx;

    public HierarchyExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = sourceFileOf(unit);
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                emitTypeHierarchy(node.resolveBinding(), sourceFile, result);
                emitOverrides(node, sourceFile, result);
                return true;
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                emitTypeHierarchy(node.resolveBinding(), sourceFile, result);
                return true;
            }
        });
    }

    private void emitTypeHierarchy(ITypeBinding binding, String sourceFile, ExtractionResult result) {
        if (binding == null) return;
        String classId = ctx.idGenerator().forType(binding);
        ITypeBinding sc = binding.getSuperclass();
        if (sc != null && !"java.lang.Object".equals(sc.getQualifiedName())) {
            result.edges.add(hierarchyEdge(classId, sc, "INHERITS", sourceFile));
        }
        for (ITypeBinding iface : binding.getInterfaces()) {
            String relation = binding.isInterface() ? "INHERITS" : "IMPLEMENTS";
            result.edges.add(hierarchyEdge(classId, iface, relation, sourceFile));
        }
    }

    private Edge hierarchyEdge(String sourceId, ITypeBinding target, String relation, String sourceFile) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.relation = relation;
        e.confidence = "EXTRACTED";
        e.sourceFile = sourceFile;
        if (ctx.isProjectInternal(target)) {
            e.targetId = ctx.idGenerator().forType(target);
            e.isExternal = false;
        } else {
            e.externalTargetFqn = target.getErasure().getQualifiedName();
            e.isExternal = true;
        }
        return e;
    }

    private void emitOverrides(TypeDeclaration decl, String sourceFile, ExtractionResult result) {
        ITypeBinding type = decl.resolveBinding();
        if (type == null) return;
        for (MethodDeclaration md : decl.getMethods()) {
            IMethodBinding subBinding = md.resolveBinding();
            if (subBinding == null || subBinding.isConstructor()) continue;
            for (IMethodBinding superMethod : collectSuperMethods(type)) {
                if (subBinding.overrides(superMethod)) {
                    result.edges.add(overrideEdge(subBinding, superMethod, sourceFile));
                }
            }
        }
    }

    private List<IMethodBinding> collectSuperMethods(ITypeBinding type) {
        List<IMethodBinding> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<ITypeBinding> queue = new ArrayDeque<>();
        ITypeBinding sc = type.getSuperclass();
        if (sc != null) queue.add(sc);
        for (ITypeBinding i : type.getInterfaces()) queue.add(i);
        while (!queue.isEmpty()) {
            ITypeBinding t = queue.poll();
            if (t == null) continue;
            String key = t.getKey();
            if (key != null && !seen.add(key)) continue;
            for (IMethodBinding m : t.getDeclaredMethods()) {
                if (!m.isConstructor()) out.add(m);
            }
            if (t.getSuperclass() != null) queue.add(t.getSuperclass());
            for (ITypeBinding i : t.getInterfaces()) queue.add(i);
        }
        return out;
    }

    private Edge overrideEdge(IMethodBinding sub, IMethodBinding sup, String sourceFile) {
        Edge e = new Edge();
        e.sourceId = ctx.idGenerator().forMethod(sub);
        e.relation = "OVERRIDES";
        e.confidence = "EXTRACTED";
        e.sourceFile = sourceFile;
        ITypeBinding supDecl = sup.getDeclaringClass();
        if (supDecl != null && ctx.isProjectInternal(supDecl)) {
            e.targetId = ctx.idGenerator().forMethod(sup);
            e.isExternal = false;
        } else {
            e.externalTargetFqn = externalMethodFqn(sup);
            e.isExternal = true;
        }
        return e;
    }

    static String externalMethodFqn(IMethodBinding m) {
        IMethodBinding decl = m.getMethodDeclaration();
        ITypeBinding declClass = decl.getDeclaringClass();
        String classFqn = declClass == null ? "<unknown>" : declClass.getErasure().getQualifiedName();
        StringBuilder params = new StringBuilder();
        ITypeBinding[] pts = decl.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) params.append(',');
            params.append(pts[i].getErasure().getQualifiedName());
        }
        return classFqn + "#" + decl.getName() + "(" + params + ")";
    }

    private static String sourceFileOf(CompilationUnit unit) {
        Object prop = unit.getProperty("source_file");
        return prop instanceof String s ? s : null;
    }
}
