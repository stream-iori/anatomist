package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;

import java.util.HashSet;
import java.util.Set;

public class FieldAccessExtractor implements Extractor {

    private final ExtractionContext ctx;

    public FieldAccessExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = sourceFileOf(unit);
        // First pass: discover all syntactic write sites so the read-pass can
        // skip them. AST identity is stable across the two visits.
        Set<ASTNode> writeSites = new HashSet<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                Expression lhs = node.getLeftHandSide();
                ASTNode site = nameOrFieldAccess(lhs);
                if (site != null) writeSites.add(site);
                return true;
            }

            @Override
            public boolean visit(PrefixExpression node) {
                if (isIncDec(node.getOperator().toString())) {
                    ASTNode site = nameOrFieldAccess(node.getOperand());
                    if (site != null) writeSites.add(site);
                }
                return true;
            }

            @Override
            public boolean visit(PostfixExpression node) {
                if (isIncDec(node.getOperator().toString())) {
                    ASTNode site = nameOrFieldAccess(node.getOperand());
                    if (site != null) writeSites.add(site);
                }
                return true;
            }
        });

        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                IVariableBinding field = fieldBindingOf(node.getLeftHandSide());
                if (field == null) return true;
                IMethodBinding enclosing = enclosingMethod(node);
                if (enclosing == null) return true;
                emit(field, enclosing, "WRITES", node, sourceFile, result);
                if (!"=".equals(node.getOperator().toString())) {
                    emit(field, enclosing, "READS", node, sourceFile, result);
                }
                return true;
            }

            @Override
            public boolean visit(PrefixExpression node) {
                if (!isIncDec(node.getOperator().toString())) return true;
                IVariableBinding field = fieldBindingOf(node.getOperand());
                IMethodBinding enclosing = enclosingMethod(node);
                if (field == null || enclosing == null) return true;
                emit(field, enclosing, "WRITES", node, sourceFile, result);
                return true;
            }

            @Override
            public boolean visit(PostfixExpression node) {
                if (!isIncDec(node.getOperator().toString())) return true;
                IVariableBinding field = fieldBindingOf(node.getOperand());
                IMethodBinding enclosing = enclosingMethod(node);
                if (field == null || enclosing == null) return true;
                emit(field, enclosing, "WRITES", node, sourceFile, result);
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                if (writeSites.contains(node)) return true;
                IBinding b = node.resolveBinding();
                if (!(b instanceof IVariableBinding vb) || !vb.isField()) return true;
                IMethodBinding enclosing = enclosingMethod(node);
                if (enclosing == null) return true;
                emit(vb, enclosing, "READS", node, sourceFile, result);
                return true;
            }

            @Override
            public boolean visit(FieldAccess node) {
                if (writeSites.contains(node)) return true;
                IVariableBinding b = node.resolveFieldBinding();
                if (b == null || !b.isField()) return true;
                IMethodBinding enclosing = enclosingMethod(node);
                if (enclosing == null) return true;
                emit(b, enclosing, "READS", node, sourceFile, result);
                return true;
            }
        });
    }

    private void emit(IVariableBinding field, IMethodBinding caller, String relation,
                      ASTNode at, String sourceFile, ExtractionResult result) {
        ITypeBinding decl = field.getDeclaringClass();
        if (decl == null || !ctx.isProjectInternal(decl)) return;
        Edge e = new Edge();
        e.sourceId = ctx.idGenerator().forMethod(caller);
        e.targetId = ctx.idGenerator().forField(field);
        e.relation = relation;
        e.confidence = "EXTRACTED";
        e.isExternal = false;
        e.sourceFile = sourceFile;
        e.sourceLocation = "L" + ((CompilationUnit) at.getRoot()).getLineNumber(at.getStartPosition());
        result.edges.add(e);
    }

    private static IVariableBinding fieldBindingOf(Expression expr) {
        if (expr instanceof SimpleName name) {
            IBinding b = name.resolveBinding();
            return (b instanceof IVariableBinding vb && vb.isField()) ? vb : null;
        }
        if (expr instanceof FieldAccess fa) {
            IVariableBinding b = fa.resolveFieldBinding();
            return (b != null && b.isField()) ? b : null;
        }
        return null;
    }

    private static ASTNode nameOrFieldAccess(Expression expr) {
        if (expr instanceof SimpleName || expr instanceof FieldAccess) return expr;
        return null;
    }

    private static boolean isIncDec(String op) {
        return "++".equals(op) || "--".equals(op);
    }

    private static IMethodBinding enclosingMethod(ASTNode node) {
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
