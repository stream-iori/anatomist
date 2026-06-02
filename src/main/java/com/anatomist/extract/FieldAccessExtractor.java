package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;

public class FieldAccessExtractor implements Extractor {

    private final ExtractionContext ctx;
    private final AstEnclosing enclosing;

    public FieldAccessExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
        this.enclosing = new AstEnclosing(ctx.idGenerator());
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;

        // Pass 1: collect write-site AST node identities so the read pass
        // can skip them.
        //
        // IdentityHashMap is load-bearing: JavaParser's Node.equals() is
        // *structural*, so two NameExpr instances spelling "x" compare equal
        // even when they live on opposite sides of `x = x + 1`. A vanilla
        // HashSet would mark every `x` in the file as a write-site, silently
        // dropping ALL READS for the same name. Identity-only membership keeps
        // the LHS instance distinct from the RHS / return-expression / etc.
        Set<Node> writeSites = Collections.newSetFromMap(new IdentityHashMap<>());
        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(AssignExpr n, Void arg) {
                Node site = nameOrFieldAccess(n.getTarget());
                if (site != null) writeSites.add(site);
                super.visit(n, arg);
            }

            @Override
            public void visit(UnaryExpr n, Void arg) {
                if (isIncDec(n.getOperator())) {
                    Node site = nameOrFieldAccess(n.getExpression());
                    if (site != null) writeSites.add(site);
                }
                super.visit(n, arg);
            }
        }.visit(unit, null);

        // Pass 2: actual emit.
        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(AssignExpr n, Void arg) {
                ResolvedFieldDeclaration field = fieldBindingOf(n.getTarget());
                String enclosingId = enclosingId(n);
                if (field != null && enclosingId != null) {
                    emit(field, enclosingId, "WRITES", n, result);
                    if (n.getOperator() != AssignExpr.Operator.ASSIGN) {
                        emit(field, enclosingId, "READS", n, result);
                    }
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(UnaryExpr n, Void arg) {
                if (isIncDec(n.getOperator())) {
                    ResolvedFieldDeclaration field = fieldBindingOf(n.getExpression());
                    String enclosingId = enclosingId(n);
                    if (field != null && enclosingId != null) {
                        emit(field, enclosingId, "WRITES", n, result);
                    }
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(NameExpr n, Void arg) {
                if (writeSites.contains(n)) { super.visit(n, arg); return; }
                ResolvedFieldDeclaration field = fieldBindingOf(n);
                String enclosingId = enclosingId(n);
                if (field != null && enclosingId != null) {
                    emit(field, enclosingId, "READS", n, result);
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                if (writeSites.contains(n)) { super.visit(n, arg); return; }
                ResolvedFieldDeclaration field = fieldBindingOf(n);
                String enclosingId = enclosingId(n);
                if (field != null && enclosingId != null) {
                    emit(field, enclosingId, "READS", n, result);
                }
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private static Node nameOrFieldAccess(Expression expr) {
        if (expr instanceof NameExpr || expr instanceof FieldAccessExpr) return expr;
        return null;
    }

    private static boolean isIncDec(UnaryExpr.Operator op) {
        return op == UnaryExpr.Operator.PREFIX_INCREMENT
                || op == UnaryExpr.Operator.PREFIX_DECREMENT
                || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                || op == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }

    private ResolvedFieldDeclaration fieldBindingOf(Expression expr) {
        try {
            if (expr instanceof NameExpr name) {
                ResolvedValueDeclaration v = name.resolve();
                return v instanceof ResolvedFieldDeclaration f ? f : null;
            }
            if (expr instanceof FieldAccessExpr fa) {
                ResolvedValueDeclaration v = fa.resolve();
                return v instanceof ResolvedFieldDeclaration f ? f : null;
            }
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
        }
        return null;
    }

    private String enclosingId(Node node) {
        return enclosing.ownerIdOf(node);
    }

    private void emit(ResolvedFieldDeclaration field, String callerId, String relation,
                      Node at, ExtractionResult result) {
        ResolvedTypeDeclaration decl;
        try { decl = field.declaringType(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        if (!ctx.isProjectInternal(decl)) return; // external field access not tracked in Phase 1.5

        Edge e = new Edge();
        e.sourceId = callerId;
        e.targetId = ctx.idGenerator().forField(field);
        e.relation = relation;
        e.confidence = "EXTRACTED";
        e.isExternal = false;
        e.sourceLocation = "L" + at.getBegin().map(p -> p.line).orElse(0);
        e.context = ControlContext.of(at);
        result.edges.add(e);
    }
}
