package com.anatomist.extract;

import com.anatomist.json.Json;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Annotation;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnnotationExtractor implements Extractor {

    private final ExtractionContext ctx;

    public AnnotationExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                emitTypeAnnotations(n, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                emitTypeAnnotations(n, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(AnnotationDeclaration n, Void arg) {
                emitTypeAnnotations(n, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                emitMethodAnnotations(n, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                emitConstructorAnnotations(n, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(CompactConstructorDeclaration n, Void arg) {
                emitCompactConstructorAnnotations(n, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(FieldDeclaration n, Void arg) {
                emitFieldAnnotations(n, result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitTypeAnnotations(com.github.javaparser.ast.body.TypeDeclaration<?> decl,
                                     ExtractionResult result) {
        if (decl.getAnnotations().isEmpty()) return;
        String nodeId;
        try { nodeId = ctx.idGenerator().forType(decl.resolve()); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, null, result);
        }
    }

    private void emitMethodAnnotations(MethodDeclaration decl, ExtractionResult result) {
        String nodeId;
        try { nodeId = CallableIdFactory.forMethod(ctx.idGenerator(), decl); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, null, result);
        }
        emitParameterAnnotations(decl.getParameters(), nodeId, result);
    }

    private void emitConstructorAnnotations(ConstructorDeclaration decl, ExtractionResult result) {
        String nodeId;
        try { nodeId = CallableIdFactory.forConstructor(ctx.idGenerator(), decl); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, null, result);
        }
        emitParameterAnnotations(decl.getParameters(), nodeId, result);
    }

    private void emitCompactConstructorAnnotations(CompactConstructorDeclaration decl,
                                                   ExtractionResult result) {
        String nodeId;
        try { nodeId = CallableIdFactory.forCompactConstructor(ctx.idGenerator(), decl); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, null, result);
        }
    }

    private void emitParameterAnnotations(List<Parameter> params, String methodNodeId,
                                          ExtractionResult result) {
        for (int i = 0; i < params.size(); i++) {
            Parameter p = params.get(i);
            if (p.getAnnotations().isEmpty()) continue;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("_param", i);
            extra.put("_name", p.getNameAsString());
            for (AnnotationExpr ann : p.getAnnotations()) {
                collectOne(methodNodeId, ann, extra, result);
            }
        }
    }

    private void emitFieldAnnotations(FieldDeclaration decl, ExtractionResult result) {
        if (decl.getAnnotations().isEmpty()) return;
        for (VariableDeclarator var : decl.getVariables()) {
            String nodeId;
            try {
                ResolvedValueDeclaration v = var.resolve();
                if (!(v instanceof com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration field)) continue;
                nodeId = ctx.idGenerator().forField(field);
            } catch (RuntimeException e) { ctx.incrementUnresolved(e); continue; }
            for (AnnotationExpr ann : decl.getAnnotations()) {
                collectOne(nodeId, ann, null, result);
            }
        }
    }

    private void collectOne(String nodeId, AnnotationExpr ann, Map<String, Object> extra,
                            ExtractionResult result) {
        String fqn;
        try {
            fqn = ann.resolve().getQualifiedName();
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }
        Annotation a = new Annotation();
        a.nodeId = nodeId;
        a.annotationFqn = fqn;
        a.attributes = attributesJson(ann, extra);
        a.sourceFile = ann.findCompilationUnit().map(SourceFiles::of).orElse(null);
        result.annotations.add(a);
    }

    private static String attributesJson(AnnotationExpr ann, Map<String, Object> extra) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (extra != null) attrs.putAll(extra);
        if (ann instanceof NormalAnnotationExpr norm) {
            for (MemberValuePair p : norm.getPairs()) {
                attrs.put(p.getNameAsString(), stringify(p.getValue()));
            }
        } else if (ann instanceof SingleMemberAnnotationExpr sm) {
            attrs.put("value", stringify(sm.getMemberValue()));
        }
        return Json.writeCompact(attrs);
    }

    private static Object stringify(Expression e) {
        if (e instanceof StringLiteralExpr s) return s.asString();
        if (e instanceof BooleanLiteralExpr b) return b.getValue();
        if (e instanceof IntegerLiteralExpr i) return i.asNumber();
        if (e instanceof LongLiteralExpr l) return l.asNumber();
        if (e instanceof DoubleLiteralExpr d) return d.asDouble();
        if (e instanceof CharLiteralExpr c) return String.valueOf(c.asChar());
        if (e instanceof ArrayInitializerExpr arr) {
            List<Object> out = new ArrayList<>();
            for (Expression el : arr.getValues()) out.add(stringify(el));
            return out;
        }
        return e.toString();
    }
}
