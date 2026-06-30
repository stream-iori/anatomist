package com.anatomist.framework.spring;

import com.anatomist.core.ExtractionContext;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SpringMvcAnalyzer implements com.anatomist.framework.JavaAstAnalyzer {

    private static final Set<String> CONTROLLERS = Set.of("Controller", "RestController");
    private static final Set<String> MAPPINGS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping");

    private final ExtractionContext ctx;

    public SpringMvcAnalyzer(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override public String id() { return "spring-mvc"; }

    @Override
    public void analyze(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = sourceFileOf(unit);
        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                if (!SpringAnnotationSupport.first(n.getAnnotations(), CONTROLLERS).isPresent()) {
                    super.visit(n, arg);
                    return;
                }
                String basePath = SpringAnnotationSupport.first(n.getAnnotations(), Set.of("RequestMapping"))
                        .map(a -> firstPath(a, ""))
                        .orElse("");
                for (MethodDeclaration m : n.getMethods()) {
                    emitRoute(basePath, m, sourceFile, result);
                }
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitRoute(String basePath, MethodDeclaration method, String sourceFile, ExtractionResult result) {
        Optional<AnnotationExpr> mapping = SpringAnnotationSupport.first(method.getAnnotations(), MAPPINGS);
        if (mapping.isEmpty()) return;
        ResolvedMethodDeclaration resolved;
        try { resolved = method.resolve(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        String methodId = ctx.idGenerator().forMethod(resolved);
        String httpMethod = httpMethod(mapping.get());
        String methodPath = firstPath(mapping.get(), "");
        String fullPath = SpringAnnotationSupport.joinPaths(basePath, methodPath);
        String routeId = routeId(httpMethod, fullPath);

        Node route = new Node();
        route.id = routeId;
        route.label = httpMethod + " " + fullPath;
        route.kind = GraphConstants.Kind.ROUTE;
        route.qualifiedName = route.label;
        route.sourceFile = sourceFile;
        route.sourceLocation = "L" + lineOf(method);
        route.scope = GraphConstants.Scope.MAIN;
        route.metadata = Json.writeCompact(routeMetadata(mapping.get(), method));
        result.nodes.add(route);

        Edge handles = new Edge();
        handles.sourceId = routeId;
        handles.targetId = methodId;
        handles.relation = GraphConstants.Relation.HANDLES;
        handles.confidence = GraphConstants.Confidence.CONFIGURED;
        handles.isExternal = false;
        handles.sourceFile = sourceFile;
        handles.sourceLocation = "L" + lineOf(method);
        result.edges.add(handles);
    }

    private static Map<String, Object> routeMetadata(AnnotationExpr ann, MethodDeclaration method) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("mappingAnnotation", SpringAnnotationSupport.simpleName(ann));
        putIfNotEmpty(meta, "consumes", SpringAnnotationSupport.stringListAttribute(ann, "consumes"));
        putIfNotEmpty(meta, "produces", SpringAnnotationSupport.stringListAttribute(ann, "produces"));
        putIfNotEmpty(meta, "params", SpringAnnotationSupport.stringListAttribute(ann, "params"));
        List<Map<String, Object>> params = new ArrayList<>();
        method.getParameters().forEach(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.getNameAsString());
            row.put("type", p.getTypeAsString());
            for (AnnotationExpr a : p.getAnnotations()) {
                String simple = SpringAnnotationSupport.simpleName(a);
                if (Set.of("PathVariable", "RequestParam", "RequestBody").contains(simple)) {
                    row.put("binding", simple);
                    String value = SpringAnnotationSupport.stringAttribute(a, "value");
                    if (value != null) row.put("value", value);
                }
            }
            params.add(row);
        });
        meta.put("parameters", params);
        return meta;
    }

    private static void putIfNotEmpty(Map<String, Object> meta, String key, List<String> values) {
        if (values != null && !values.isEmpty()) meta.put(key, values);
    }

    private static String firstPath(AnnotationExpr ann, String defaultValue) {
        List<String> values = SpringAnnotationSupport.stringListAttribute(ann, "value");
        if (values.isEmpty()) values = SpringAnnotationSupport.stringListAttribute(ann, "path");
        return values.isEmpty() ? defaultValue : values.get(0);
    }

    private static String httpMethod(AnnotationExpr ann) {
        return switch (SpringAnnotationSupport.simpleName(ann)) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> {
                String method = SpringAnnotationSupport.stringAttribute(ann, "method");
                if (method == null) yield "ANY";
                int dot = method.lastIndexOf('.');
                yield dot >= 0 ? method.substring(dot + 1) : method;
            }
        };
    }

    private static String routeId(String method, String path) {
        return "route:" + method + " " + path;
    }

    private static int lineOf(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    private static String sourceFileOf(CompilationUnit unit) {
        return unit.getData(com.anatomist.extract.TypeExtractor.SourceFileKey.KEY);
    }
}
