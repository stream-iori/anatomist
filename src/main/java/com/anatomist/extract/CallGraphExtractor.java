package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;

import java.lang.reflect.Modifier;

public class CallGraphExtractor implements Extractor {

    private final ExtractionContext ctx;

    public CallGraphExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = sourceFileOf(unit);
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                IMethodBinding b = node.resolveMethodBinding();
                if (b != null) emit(node, b, classifyInvocation(b), sourceFile, result);
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                IMethodBinding b = node.resolveConstructorBinding();
                if (b != null) emit(node, b, "CONSTRUCTOR", sourceFile, result);
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                IMethodBinding b = node.resolveMethodBinding();
                if (b != null) emit(node, b, "SUPER", sourceFile, result);
                return true;
            }
        });
    }

    private static String classifyInvocation(IMethodBinding b) {
        if (Modifier.isStatic(b.getModifiers())) return "STATIC";
        ITypeBinding decl = b.getDeclaringClass();
        if (decl != null && decl.isInterface()) return "INTERFACE";
        return "INSTANCE";
    }

    private void emit(ASTNode call, IMethodBinding target, String callKind,
                      String sourceFile, ExtractionResult result) {
        IMethodBinding caller = enclosingMethodBinding(call);
        if (caller == null) return;  // call appears in initializer outside a method
        String sourceId = ctx.idGenerator().forMethod(caller);

        CompilationUnit cu = (CompilationUnit) call.getRoot();
        int line = cu.getLineNumber(call.getStartPosition());

        Edge e = new Edge();
        e.sourceId = sourceId;
        e.relation = "CALLS";
        e.callKind = callKind;
        e.confidence = "EXTRACTED";
        e.sourceFile = sourceFile;
        e.sourceLocation = "L" + line;

        ITypeBinding decl = target.getDeclaringClass();
        if (decl != null && ctx.isProjectInternal(decl)) {
            e.targetId = ctx.idGenerator().forMethod(target);
            e.isExternal = false;
        } else {
            e.externalTargetFqn = HierarchyExtractor.externalMethodFqn(target);
            e.isExternal = true;
        }
        result.edges.add(e);
    }

    private static IMethodBinding enclosingMethodBinding(ASTNode node) {
        ASTNode cur = node.getParent();
        while (cur != null) {
            if (cur instanceof MethodDeclaration md) return md.resolveBinding();
            cur = cur.getParent();
        }
        return null;
    }

    private static String sourceFileOf(CompilationUnit unit) {
        Object prop = unit.getProperty("source_file");
        return prop instanceof String s ? s : null;
    }
}
