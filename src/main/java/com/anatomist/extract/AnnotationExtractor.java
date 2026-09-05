package com.anatomist.extract;

import com.anatomist.json.Json;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Annotation;
import com.anatomist.model.AnnotationMetaRelation;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
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
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

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
            public void visit(RecordDeclaration n, Void arg) {
                emitTypeAnnotations(n, result);
                emitRecordComponentAnnotations(n, result);
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

            @Override
            public void visit(EnumConstantDeclaration n, Void arg) {
                emitEnumConstantAnnotations(n, result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitTypeAnnotations(com.github.javaparser.ast.body.TypeDeclaration<?> decl,
                                     ExtractionResult result) {
        if (decl.getAnnotations().isEmpty()) return;
        String nodeId;
        try { nodeId = ctx.idGenerator().forType(decl.resolve()); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e, decl, decl.getNameAsString()); return; }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, "type", null, null, result);
        }
    }

    private void emitMethodAnnotations(MethodDeclaration decl, ExtractionResult result) {
        String nodeId;
        try { nodeId = CallableIdFactory.forMethod(ctx.idGenerator(), decl); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e, decl, decl.getNameAsString()); return; }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, "callable", null, null, result);
        }
        emitParameterAnnotations(decl.getParameters(), nodeId, result);
    }

    private void emitConstructorAnnotations(ConstructorDeclaration decl, ExtractionResult result) {
        String nodeId;
        try { nodeId = CallableIdFactory.forConstructor(ctx.idGenerator(), decl); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e, decl, decl.getNameAsString()); return; }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, "callable", null, null, result);
        }
        emitParameterAnnotations(decl.getParameters(), nodeId, result);
    }

    private void emitCompactConstructorAnnotations(CompactConstructorDeclaration decl,
                                                   ExtractionResult result) {
        String nodeId;
        try { nodeId = CallableIdFactory.forCompactConstructor(ctx.idGenerator(), decl); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e, decl, decl.getNameAsString()); return; }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, "callable", null, null, result);
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
                collectOne(methodNodeId, ann, "callable", "parameter[" + i + "]", extra, result);
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
            } catch (RuntimeException e) {
                ctx.incrementUnresolved(e, var, var.getNameAsString());
                continue;
            }
            for (AnnotationExpr ann : decl.getAnnotations()) {
                collectOne(nodeId, ann, "value", null, null, result);
            }
        }
    }

    private void emitEnumConstantAnnotations(EnumConstantDeclaration decl,
                                             ExtractionResult result) {
        if (decl.getAnnotations().isEmpty()) return;
        String nodeId;
        try {
            String owner = decl.resolve().getType().describe();
            nodeId = owner + "#" + decl.getNameAsString();
        } catch (RuntimeException e) {
            ctx.incrementUnresolved(e, decl, decl.getNameAsString());
            return;
        }
        for (AnnotationExpr ann : decl.getAnnotations()) {
            collectOne(nodeId, ann, "value", null, null, result);
        }
    }

    private void emitRecordComponentAnnotations(RecordDeclaration declaration,
                                                ExtractionResult result) {
        String owner;
        try { owner = declaration.resolve().getQualifiedName(); }
        catch (RuntimeException e) {
            ctx.incrementUnresolved(e, declaration, declaration.getNameAsString());
            return;
        }
        for (int i = 0; i < declaration.getParameters().size(); i++) {
            Parameter component = declaration.getParameter(i);
            if (component.getAnnotations().isEmpty()) continue;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("_param", i);
            extra.put("_name", component.getNameAsString());
            for (AnnotationExpr ann : component.getAnnotations()) {
                collectOne(owner + "#" + component.getNameAsString(), ann, "value",
                        "component[" + i + "]", extra, result);
            }
        }
    }

    private void collectOne(String nodeId, AnnotationExpr ann, String targetKind,
                            String targetPath, Map<String, Object> extra,
                            ExtractionResult result) {
        ResolvedAnnotation resolved = resolve(ann);
        Annotation a = new Annotation();
        a.nodeId = nodeId;
        a.annotationFqn = resolved.fqn();
        a.rawName = ann.getNameAsString();
        a.attributes = attributesJson(ann, extra);
        a.targetKind = targetKind;
        a.targetPath = targetPath;
        a.resolutionStatus = resolved.status();
        a.sourceFile = ann.findCompilationUnit().map(SourceFiles::of).orElse(null);
        ann.getRange().ifPresent(range -> {
            a.beginLine = range.begin.line;
            a.beginColumn = range.begin.column;
            a.endLine = range.end.line;
            a.endColumn = range.end.column;
            a.sourceLocation = "L" + range.begin.line + ":" + range.begin.column;
        });
        result.annotations.add(a);
        if (resolved.declaration() != null && resolved.fqn() != null) {
            collectMetaRelations(resolved.declaration(), resolved.fqn(), a.sourceFile,
                    a.sourceLocation, new HashSet<>(), 0, result);
        }
    }

    private ResolvedAnnotation resolve(AnnotationExpr ann) {
        try {
            ResolvedAnnotationDeclaration declaration = ann.resolve();
            return new ResolvedAnnotation(declaration.getQualifiedName(), "exact", declaration);
        } catch (RuntimeException e) {
            ctx.incrementUnresolved(e, ann, ann.getNameAsString());
        }
        String raw = ann.getNameAsString();
        if (raw.contains(".")) return new ResolvedAnnotation(raw, "heuristic", null);
        String imported = ann.findCompilationUnit().flatMap(unit -> unit.getImports().stream()
                .filter(value -> !value.isAsterisk() && !value.isStatic())
                .map(value -> value.getNameAsString())
                .filter(value -> value.endsWith("." + raw))
                .findFirst()).orElse(null);
        return imported == null
                ? new ResolvedAnnotation(null, "unresolved", null)
                : new ResolvedAnnotation(imported, "heuristic", null);
    }

    private void collectMetaRelations(ResolvedAnnotationDeclaration declaration,
                                      String annotationFqn,
                                      String sourceFile,
                                      String sourceLocation,
                                      Set<String> path,
                                      int depth,
                                      ExtractionResult result) {
        if (depth >= 16 || !path.add(annotationFqn)) return;
        String declarationSource = declaration.toAst()
                .flatMap(com.github.javaparser.ast.Node::findCompilationUnit)
                .map(SourceFiles::of).map(this::projectRelative).orElse(sourceFile);
        String declarationLocation = declaration.toAst()
                .flatMap(com.github.javaparser.ast.Node::getRange)
                .map(range -> "L" + range.begin.line + ":" + range.begin.column)
                .orElse(sourceLocation);
        try {
            for (ResolvedAnnotationDeclaration meta : declaration.getDeclaredAnnotations()) {
                String metaFqn = meta.getQualifiedName();
                AnnotationMetaRelation relation = new AnnotationMetaRelation();
                relation.annotationFqn = annotationFqn;
                relation.metaAnnotationFqn = metaFqn;
                relation.rawName = metaFqn;
                relation.resolutionStatus = "exact";
                relation.sourceFile = declarationSource;
                relation.sourceLocation = declarationLocation;
                result.annotationMetaRelations.add(relation);
                collectMetaRelations(meta, metaFqn, declarationSource, declarationLocation,
                        new HashSet<>(path), depth + 1, result);
            }
        } catch (RuntimeException ignored) {
            // The direct use remains valid even when classpath meta-data is unavailable.
        }
    }

    private String projectRelative(String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) return sourceFile;
        java.nio.file.Path path = java.nio.file.Path.of(sourceFile);
        if (!path.isAbsolute()) return sourceFile;
        java.nio.file.Path root = ctx.projectRoot().toAbsolutePath().normalize();
        java.nio.file.Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString() : sourceFile;
    }

    private record ResolvedAnnotation(String fqn, String status,
                                      ResolvedAnnotationDeclaration declaration) {}

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
