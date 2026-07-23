package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
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

import java.util.Optional;

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

        // A single pass emits writes from their parent expression and skips the
        // exact target node when the normal read visitor reaches it. Identity
        // comparison is load-bearing because JavaParser Node.equals is structural.
        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(AssignExpr n, Void arg) {
                ResolvedFieldDeclaration field = fieldBindingOf(n.getTarget());
                String enclosingId = enclosingId(n);
                if (field != null && enclosingId != null) {
                    emit(field, enclosingId, GraphConstants.Relation.WRITES, n, result);
                    if (n.getOperator() != AssignExpr.Operator.ASSIGN) {
                        emit(field, enclosingId, GraphConstants.Relation.READS, n, result);
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
                        emit(field, enclosingId, GraphConstants.Relation.WRITES, n, result);
                    }
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(NameExpr n, Void arg) {
                if (isWriteTarget(n)) { super.visit(n, arg); return; }
                ResolvedFieldDeclaration field = fieldBindingOf(n);
                String enclosingId = enclosingId(n);
                if (field != null && enclosingId != null) {
                    emit(field, enclosingId, GraphConstants.Relation.READS, n, result);
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(FieldAccessExpr n, Void arg) {
                if (isWriteTarget(n)) { super.visit(n, arg); return; }
                ResolvedFieldDeclaration field = fieldBindingOf(n);
                String enclosingId = enclosingId(n);
                if (field != null && enclosingId != null) {
                    emit(field, enclosingId, GraphConstants.Relation.READS, n, result);
                }
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private static boolean isWriteTarget(Expression expression) {
        Optional<Node> parent = expression.getParentNode();
        if (parent.isEmpty()) return false;
        if (parent.get() instanceof AssignExpr assign) {
            return assign.getTarget() == expression;
        }
        if (parent.get() instanceof UnaryExpr unary && isIncDec(unary.getOperator())) {
            return unary.getExpression() == expression;
        }
        return false;
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

        Edge edge = new Edge();
        edge.sourceId = callerId;
        edge.relation = relation;
        edge.confidence = GraphConstants.Confidence.EXTRACTED;
        edge.sourceLocation = "L" + at.getBegin().map(p -> p.line).orElse(0);
        edge.context = ControlContext.of(at);

        if (ctx.isProjectInternal(decl)) {
            edge.targetId = ctx.idGenerator().forField(field);
            edge.isExternal = false;
        } else {
            String fqn = NodeIdGenerator.externalFieldFqn(field);
            if (ctx.isExternalExcluded(fqn)) return;
            edge.externalTargetFqn = fqn;
            edge.isExternal = true;
            edge.resolution = GraphConstants.Resolution.CLASSPATH;
        }
        result.edges.add(edge);
    }
}
