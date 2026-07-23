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
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");
    private static final Set<String> INJECTION = Set.of("Autowired", "Resource", "Inject");

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
        Optional<AnnotationExpr> ann = SpringAnnotationSupport.first(n.getAnnotations(), COMPONENTS);
        if (ann.isEmpty()) return;
        ResolvedReferenceTypeDeclaration type;
        try { type = n.resolve(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        String typeId = ctx.idGenerator().forType(type);
        String beanName = beanName(ann.get(), type.getName());
        String beanId = beanId(beanName);
        result.nodes.add(beanNode(beanId, beanName, sourceFile, lineOf(n),
                Map.of("className", type.getQualifiedName(), "source", "annotation",
                        "stereotype", SpringAnnotationSupport.simpleName(ann.get()))));
        result.edges.add(edge(beanId, typeId, GraphConstants.Relation.DEFINED_BY,
                sourceFile, lineOf(n), GraphConstants.Confidence.CONFIGURED, null));
    }

    private void emitBeanMethod(MethodDeclaration n, String sourceFile, ExtractionResult result) {
        Optional<AnnotationExpr> ann = SpringAnnotationSupport.first(n.getAnnotations(), Set.of("Bean"));
        if (ann.isEmpty()) return;
        ResolvedMethodDeclaration method;
        try { method = n.resolve(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        String methodId = ctx.idGenerator().forMethod(method);
        String explicit = SpringAnnotationSupport.stringAttribute(ann.get(), "value");
        if (explicit == null) explicit = SpringAnnotationSupport.stringAttribute(ann.get(), "name");
        String beanName = explicit != null && !explicit.isBlank() ? explicit : n.getNameAsString();
        String returnType = n.getTypeAsString();
        String beanId = beanId(beanName);
        result.nodes.add(beanNode(beanId, beanName, sourceFile, lineOf(n),
                Map.of("factoryMethod", methodId, "returnType", returnType, "source", "bean_method")));
        result.edges.add(edge(beanId, methodId, GraphConstants.Relation.DEFINED_BY,
                sourceFile, lineOf(n), GraphConstants.Confidence.CONFIGURED, null));
    }

    private void emitFieldInjection(FieldDeclaration n, String sourceFile, ExtractionResult result) {
        Optional<AnnotationExpr> ann = SpringAnnotationSupport.first(n.getAnnotations(), INJECTION);
        if (ann.isEmpty()) return;
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
                emitInjection(ownerId, var.getType().resolve(), sourceFile, lineOf(var), ann.get(), result);
            } catch (RuntimeException e) { ctx.incrementUnresolved(e); }
        }
    }

    private void emitConstructorInjection(ConstructorDeclaration n, String sourceFile, ExtractionResult result) {
        if (!SpringAnnotationSupport.first(n.getAnnotations(), INJECTION).isPresent()) return;
        ResolvedConstructorDeclaration ctor;
        try { ctor = n.resolve(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        String ownerId;
        try { ownerId = ctx.idGenerator().forType(ctor.declaringType()); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        for (var p : n.getParameters()) {
            try {
                emitInjection(ownerId, p.getType().resolve(), sourceFile, lineOf(p), null, result);
            } catch (RuntimeException e) { ctx.incrementUnresolved(e); }
        }
    }

    private void emitInjection(String ownerId, ResolvedType injectedType, String sourceFile, int line,
                               AnnotationExpr ann, ExtractionResult result) {
        if (injectedType == null || !injectedType.isReferenceType()) return;
        var td = injectedType.asReferenceType().getTypeDeclaration().orElse(null);
        if (td == null) return;
        Edge e = edge(ownerId, null, GraphConstants.Relation.INJECTS,
                sourceFile, line, GraphConstants.Confidence.CONFIGURED, null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("injectedType", injectedType.describe());
        if (ann != null) {
            meta.put("annotation", SpringAnnotationSupport.simpleName(ann));
            String qualifier = SpringAnnotationSupport.stringAttribute(ann, "value");
            if (qualifier == null) qualifier = SpringAnnotationSupport.stringAttribute(ann, "name");
            if (qualifier != null) meta.put("qualifier", qualifier);
        }
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
