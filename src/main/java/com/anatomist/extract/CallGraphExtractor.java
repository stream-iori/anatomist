package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;

import java.util.Optional;

public class CallGraphExtractor implements Extractor {

    private final ExtractionContext ctx;
    private final AstEnclosing enclosing;

    public CallGraphExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
        this.enclosing = new AstEnclosing(ctx.idGenerator());
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                ResolvedMethodDeclaration target;
                try { target = n.resolve(); }
                catch (RuntimeException e) { ctx.incrementUnresolved(e); super.visit(n, arg); return; }
                String callKind = classify(target, n);
                emit(n, target, callKind, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(ObjectCreationExpr n, Void arg) {
                if (n.getAnonymousClassBody().isPresent()) {
                    // Construction of the anonymous class itself; the call
                    // links to the supertype constructor.
                }
                ResolvedConstructorDeclaration target;
                try { target = n.resolve(); }
                catch (RuntimeException e) { ctx.incrementUnresolved(e); super.visit(n, arg); return; }
                emit(n, target, "CONSTRUCTOR", result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private static String classify(ResolvedMethodDeclaration target, MethodCallExpr call) {
        if (call.getScope().isPresent() && call.getScope().get() instanceof SuperExpr) {
            return "SUPER";
        }
        if (target.isStatic()) return "STATIC";
        try {
            ResolvedReferenceTypeDeclaration t = target.declaringType().asReferenceType();
            if (t.isInterface()) return "INTERFACE";
        } catch (RuntimeException ignore) { }
        return "INSTANCE";
    }

    private void emit(com.github.javaparser.ast.Node callNode,
                      ResolvedMethodLikeDeclaration target, String callKind,
                      ExtractionResult result) {
        String enclosingId = enclosingMethodId(callNode);
        if (enclosingId == null) return;

        Edge e = new Edge();
        e.sourceId = enclosingId;
        e.relation = "CALLS";
        e.callKind = callKind;
        e.confidence = "EXTRACTED";
        e.sourceLocation = "L" + callNode.getBegin().map(p -> p.line).orElse(0);

        ResolvedTypeDeclaration decl;
        try { decl = target.declaringType(); }
        catch (RuntimeException ex) { ctx.incrementUnresolved(ex); return; }

        if (ctx.isProjectInternal(decl)) {
            if (target instanceof ResolvedMethodDeclaration m) {
                e.targetId = ctx.idGenerator().forMethod(m);
            } else if (target instanceof ResolvedConstructorDeclaration c) {
                e.targetId = ctx.idGenerator().forConstructor(c);
            } else {
                return;
            }
            e.isExternal = false;
        } else {
            e.externalTargetFqn = NodeIdGenerator.externalMethodFqn(target);
            e.isExternal = true;
        }
        result.edges.add(e);
    }

    private String enclosingMethodId(com.github.javaparser.ast.Node node) {
        return enclosing.ownerIdOf(node);
    }
}
