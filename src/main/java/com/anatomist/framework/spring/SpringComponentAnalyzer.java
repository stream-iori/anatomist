package com.anatomist.framework.spring;

import com.anatomist.core.ExtractionContext;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SpringComponentAnalyzer implements com.anatomist.framework.JavaAstAnalyzer {

    private static final Set<String> COMPONENTS = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration");
    private static final Set<String> BEAN = Set.of("org.springframework.context.annotation.Bean");
    private static final Set<String> INJECTION = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.annotation.Resource", "javax.annotation.Resource",
            "jakarta.inject.Inject", "javax.inject.Inject");
    private static final Set<String> QUALIFIER = Set.of(
            "org.springframework.beans.factory.annotation.Qualifier",
            "jakarta.inject.Named", "javax.inject.Named");

    private final ExtractionContext ctx;

    public SpringComponentAnalyzer(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override public String id() { return "spring-components"; }

    @Override
    public void analyze(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = sourceFileOf(unit);
        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                emitComponentBean(n, sourceFile, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                emitBeanMethod(n, sourceFile, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(FieldDeclaration n, Void arg) {
                emitFieldInjection(n, sourceFile, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                emitConstructorInjection(n, sourceFile, result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitComponentBean(ClassOrInterfaceDeclaration n, String sourceFile, ExtractionResult result) {
        Optional<SpringAnnotationSupport.Match> ann = SpringAnnotationSupport.firstMatch(
                n.getAnnotations(), COMPONENTS);
        if (ann.isEmpty()) return;
        ResolvedReferenceTypeDeclaration type;
        try { type = n.resolve(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        String typeId = ctx.idGenerator().forType(type);
        String beanName = beanName(ann.get().annotation(), type.getName());
        String beanId = beanId(beanName);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("className", type.getQualifiedName());
        metadata.put("source", "annotation");
        metadata.put("stereotype", ann.get().rootFqn());
        metadata.putAll(SpringAnnotationSupport.evidence(ann.get()));
        result.nodes.add(beanNode(beanId, beanName, sourceFile, lineOf(n), metadata));
        result.edges.add(edge(beanId, typeId, GraphConstants.Relation.DEFINED_BY,
                sourceFile, lineOf(n), GraphConstants.Confidence.CONFIGURED, null));
    }

    private void emitBeanMethod(MethodDeclaration n, String sourceFile, ExtractionResult result) {
        Optional<SpringAnnotationSupport.Match> ann = SpringAnnotationSupport.firstMatch(
                n.getAnnotations(), BEAN);
        if (ann.isEmpty()) return;
        ResolvedMethodDeclaration method;
        try { method = n.resolve(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        String methodId = ctx.idGenerator().forMethod(method);
        String explicit = SpringAnnotationSupport.stringAttribute(ann.get().annotation(), "value");
        if (explicit == null) explicit = SpringAnnotationSupport.stringAttribute(ann.get().annotation(), "name");
        String beanName = explicit != null && !explicit.isBlank() ? explicit : n.getNameAsString();
        String returnType;
        try { returnType = method.getReturnType().describe(); }
        catch (RuntimeException ignored) { returnType = n.getTypeAsString(); }
        String beanId = beanId(beanName);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("factoryMethod", methodId);
        metadata.put("returnType", returnType);
        metadata.put("source", "bean_method");
        metadata.putAll(SpringAnnotationSupport.evidence(ann.get()));
        result.nodes.add(beanNode(beanId, beanName, sourceFile, lineOf(n), metadata));
        result.edges.add(edge(beanId, methodId, GraphConstants.Relation.DEFINED_BY,
                sourceFile, lineOf(n), GraphConstants.Confidence.CONFIGURED, null));
    }

    private void emitFieldInjection(FieldDeclaration n, String sourceFile, ExtractionResult result) {
        Optional<SpringAnnotationSupport.Match> ann = SpringAnnotationSupport.firstMatch(
                n.getAnnotations(), INJECTION);
        if (ann.isEmpty()) return;
        SpringAnnotationSupport.Match qualifier = SpringAnnotationSupport.firstMatch(
                n.getAnnotations(), QUALIFIER).orElse(null);
        for (VariableDeclarator var : n.getVariables()) {
            ResolvedFieldDeclaration field;
            try {
                ResolvedValueDeclaration v = var.resolve();
                if (!(v instanceof ResolvedFieldDeclaration f)) continue;
                field = f;
            } catch (RuntimeException e) { ctx.incrementUnresolved(e); continue; }
            String ownerId;
            try { ownerId = ctx.idGenerator().forType(field.declaringType()); }
            catch (RuntimeException e) { ctx.incrementUnresolved(e); continue; }
            try {
                emitInjection(ownerId, var.getType().resolve(), sourceFile, lineOf(var),
                        ann.get(), qualifier, false, result);
            } catch (RuntimeException e) { ctx.incrementUnresolved(e); }
        }
    }

    private void emitConstructorInjection(ConstructorDeclaration n, String sourceFile, ExtractionResult result) {
        SpringAnnotationSupport.Match injection = SpringAnnotationSupport.firstMatch(
                n.getAnnotations(), INJECTION).orElse(null);
        ClassOrInterfaceDeclaration owner = n.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        boolean implicitSingleConstructor = injection == null && owner != null
                && owner.getConstructors().size() == 1
                && SpringAnnotationSupport.firstMatch(owner.getAnnotations(), COMPONENTS).isPresent();
        if (injection == null && !implicitSingleConstructor) return;
        ResolvedConstructorDeclaration ctor;
        try { ctor = n.resolve(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        String ownerId;
        try { ownerId = ctx.idGenerator().forType(ctor.declaringType()); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        for (var p : n.getParameters()) {
            try {
                SpringAnnotationSupport.Match qualifier = SpringAnnotationSupport.firstMatch(
                        p.getAnnotations(), QUALIFIER).orElse(null);
                emitInjection(ownerId, p.getType().resolve(), sourceFile, lineOf(p),
                        injection, qualifier, implicitSingleConstructor, result);
            } catch (RuntimeException e) { ctx.incrementUnresolved(e); }
        }
    }

    private void emitInjection(String ownerId, ResolvedType injectedType, String sourceFile, int line,
                               SpringAnnotationSupport.Match injection,
                               SpringAnnotationSupport.Match qualifier,
                               boolean implicitConstructor, ExtractionResult result) {
        if (injectedType == null || !injectedType.isReferenceType()) return;
        var td = injectedType.asReferenceType().getTypeDeclaration().orElse(null);
        if (td == null) return;
        Edge e = edge(ownerId, null, GraphConstants.Relation.INJECTS,
                sourceFile, line, GraphConstants.Confidence.CONFIGURED, null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("injectedType", injectedType.describe());
        if (injection != null) {
            meta.put("annotation", injection.rootFqn());
            meta.putAll(SpringAnnotationSupport.evidence(injection));
        }
        if (implicitConstructor) meta.put("implicitSingleConstructor", true);
        String qualifierValue = null;
        if (qualifier != null) {
            qualifierValue = SpringAnnotationSupport.stringAttribute(qualifier.annotation(), "value");
            if (qualifierValue == null) {
                qualifierValue = SpringAnnotationSupport.stringAttribute(qualifier.annotation(), "name");
            }
        } else if (injection != null
                && ("jakarta.annotation.Resource".equals(injection.rootFqn())
                || "javax.annotation.Resource".equals(injection.rootFqn()))) {
            qualifierValue = SpringAnnotationSupport.stringAttribute(injection.annotation(), "name");
            if (qualifierValue == null) {
                qualifierValue = SpringAnnotationSupport.stringAttribute(injection.annotation(), "value");
            }
        }
        if (qualifierValue != null) meta.put("qualifier", qualifierValue);
        e.metadata = Json.writeCompact(meta);
        if (ctx.isProjectInternal(td)) {
            e.targetId = ctx.idGenerator().forType(td);
            e.isExternal = false;
        } else {
            e.externalTargetFqn = td.getQualifiedName();
            e.isExternal = true;
            e.resolution = GraphConstants.Resolution.CLASSPATH;
        }
        result.edges.add(e);
    }

    private static String beanName(AnnotationExpr ann, String simpleClassName) {
        String explicit = SpringAnnotationSupport.stringAttribute(ann, "value");
        if (explicit == null) explicit = SpringAnnotationSupport.stringAttribute(ann, "name");
        return explicit != null && !explicit.isBlank()
                ? explicit
                : SpringAnnotationSupport.decapitalize(simpleClassName);
    }

    private static Node beanNode(String id, String label, String sourceFile, int line, Map<String, Object> meta) {
        Node n = new Node();
        n.id = id;
        n.label = label;
        n.kind = GraphConstants.Kind.BEAN;
        n.qualifiedName = label;
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + line;
        n.scope = GraphConstants.Scope.MAIN;
        n.metadata = Json.writeCompact(meta);
        return n;
    }

    private static Edge edge(String source, String target, String relation, String sourceFile,
                             int line, String confidence, String metadata) {
        Edge e = new Edge();
        e.sourceId = source;
        e.targetId = target;
        e.relation = relation;
        e.confidence = confidence;
        e.isExternal = false;
        e.sourceFile = sourceFile;
        e.sourceLocation = "L" + line;
        e.metadata = metadata;
        return e;
    }

    private static String beanId(String name) {
        return "bean:" + name;
    }

    private static int lineOf(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private static String sourceFileOf(CompilationUnit unit) {
        return unit.getData(com.anatomist.extract.TypeExtractor.SourceFileKey.KEY);
    }
}
