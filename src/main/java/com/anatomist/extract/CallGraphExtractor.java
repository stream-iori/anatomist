package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.logic.MethodResolutionCapability;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class CallGraphExtractor implements Extractor {

    private final ExtractionContext ctx;
    private final AstEnclosing enclosing;
    private final Map<Expression, Optional<ResolvedType>> calculatedTypes = new IdentityHashMap<>();
    private final Map<Expression, Optional<ResolvedType>> scopeTypes = new IdentityHashMap<>();
    private final Map<Expression, String> renderedTypes = new IdentityHashMap<>();
    private final Map<Expression, String> overloadTypes = new IdentityHashMap<>();
    private final Map<Expression, String> lexicalScopeTypes = new IdentityHashMap<>();
    private final Map<ResolvedMethodLikeDeclaration, String> methodIds = new IdentityHashMap<>();
    private List<ClassOrInterfaceDeclaration> lexicalTypes = List.of();

    public CallGraphExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
        this.enclosing = new AstEnclosing(ctx.idGenerator());
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        clearUnitCaches();
        lexicalTypes = unit.findAll(ClassOrInterfaceDeclaration.class);
        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodCallExpr n, Void arg) {
                ResolvedMethodDeclaration target;
                try {
                    target = n.resolve();
                    if (unreliableSignature(target)) {
                        emitFallback(n, result);
                        super.visit(n, arg);
                        return;
                    }
                    String callKind = CallKindClassifier.classify(target, n);
                    emit(n, target, callKind, result);
                } catch (RuntimeException e) {
                    ctx.incrementUnresolved(e);
                    emitFallback(n, result);
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(ObjectCreationExpr n, Void arg) {
                if (n.getAnonymousClassBody().isPresent()) {
                    // JavaParser resolves this to a synthetic anonymous-class
                    // constructor that has no METHOD node. The anonymous body
                    // is represented by ANONYMOUS_CLASS + CONTAINS facts; do
                    // not emit a guaranteed-dangling constructor call.
                    super.visit(n, arg);
                    return;
                }
                ResolvedConstructorDeclaration target;
                try { target = n.resolve(); }
                catch (RuntimeException e) { ctx.incrementUnresolved(e); super.visit(n, arg); return; }
                emit(n, target, GraphConstants.CallKind.CONSTRUCTOR, result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
        clearUnitCaches();
    }

    private void clearUnitCaches() {
        calculatedTypes.clear();
        scopeTypes.clear();
        renderedTypes.clear();
        overloadTypes.clear();
        lexicalScopeTypes.clear();
        methodIds.clear();
        lexicalTypes = List.of();
    }

    private void emit(com.github.javaparser.ast.Node callNode,
                      ResolvedMethodLikeDeclaration target, String callKind,
                      ExtractionResult result) {
        String enclosingId = enclosingMethodId(callNode);
        if (enclosingId == null) return;

        Edge e = new Edge();
        e.sourceId = enclosingId;
        e.relation = GraphConstants.Relation.CALLS;
        e.callKind = callKind;
        e.confidence = GraphConstants.Confidence.EXTRACTED;
        e.sourceLocation = "L" + callNode.getBegin().map(p -> p.line).orElse(0);
        e.context = ControlContext.of(callNode);

        ResolvedTypeDeclaration decl;
        try { decl = target.declaringType(); }
        catch (RuntimeException ex) { ctx.incrementUnresolved(ex); return; }

        if (ctx.isProjectInternal(decl)) {
            if (target instanceof ResolvedMethodDeclaration m) {
                e.targetId = methodTargetId(m);
            } else if (target instanceof ResolvedConstructorDeclaration c) {
                e.targetId = CallableIdFactory.forConstructor(ctx.idGenerator(), c);
            } else {
                return;
            }
            e.isExternal = false;
        } else {
            e.externalTargetFqn = methodTargetFqn(target);
            e.isExternal = true;
            e.resolution = GraphConstants.Resolution.CLASSPATH;
        }
        result.edges.add(e);
    }

    private void emitInferred(com.github.javaparser.ast.Node callNode,
                              ResolvedMethodLikeDeclaration target, String callKind,
                              ExtractionResult result, String metadata) {
        int before = result.edges.size();
        emit(callNode, target, callKind, result);
        if (result.edges.size() > before) {
            Edge e = result.edges.get(result.edges.size() - 1);
            e.confidence = GraphConstants.Confidence.INFERRED;
            e.metadata = metadata;
        }
    }

    private void emitAmbiguous(MethodCallExpr call, List<ResolvedMethodDeclaration> targets,
                               ExtractionResult result) {
        String enclosingId = enclosingMethodId(call);
        if (enclosingId == null) return;
        List<String> candidates = targets.stream()
                .map(this::methodTargetFqn)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("reason", "overload");
        meta.put("arguments", call.getArguments().stream()
                .map(this::fallbackTypeOfArgument)
                .collect(Collectors.toList()));
        meta.put("candidates", candidates);
        String metadata = Json.writeCompact(meta);

        for (ResolvedMethodDeclaration target : targets) {
            Edge e = baseEdge(call, enclosingId);
            e.callKind = CallKindClassifier.classify(target, call);
            e.confidence = GraphConstants.Confidence.AMBIGUOUS;
            e.metadata = metadata;
            ResolvedTypeDeclaration decl;
            try { decl = target.declaringType(); }
            catch (RuntimeException ex) { ctx.incrementUnresolved(ex); continue; }
            if (ctx.isProjectInternal(decl)) {
                e.targetId = methodTargetId(target);
                e.isExternal = false;
            } else {
                e.externalTargetFqn = methodTargetFqn(target);
                e.isExternal = true;
                e.resolution = GraphConstants.Resolution.CLASSPATH;
            }
            result.edges.add(e);
        }
    }

    private boolean unreliableSignature(ResolvedMethodLikeDeclaration target) {
        String rendered = methodTargetFqn(target);
        return rendered.contains("(<unresolved>") || rendered.contains(",<unresolved>")
                || rendered.contains("(null") || rendered.contains(",null");
    }

    private String methodTargetFqn(ResolvedMethodLikeDeclaration target) {
        if (target instanceof ResolvedMethodDeclaration m) {
            String id = methodTargetId(m);
            if (id != null) return id;
        }
        return NodeIdGenerator.externalMethodFqn(target);
    }

    private String methodTargetId(ResolvedMethodDeclaration method) {
        if (methodIds.containsKey(method)) return methodIds.get(method);
        String id = CallableIdFactory.forMethod(ctx.idGenerator(), method);
        methodIds.put(method, id);
        return id;
    }

    private void emitFallback(MethodCallExpr call, ExtractionResult result) {
        String enclosingId = enclosingMethodId(call);
        if (enclosingId == null) return;

        if (emitLocalMethodFallback(call, enclosingId, result)) return;
        if (emitScopeTypeFallback(call, enclosingId, result)) return;
        emitStaticNameFallback(call, enclosingId, result);
    }

    private boolean emitLocalMethodFallback(MethodCallExpr call, String enclosingId,
                                            ExtractionResult result) {
        if (call.getScope().isPresent()) return false;

        Optional<TypeDeclaration> typeOpt = call.findAncestor(TypeDeclaration.class);
        if (typeOpt.isEmpty()) return false;

        @SuppressWarnings("unchecked")
        List<MethodDeclaration> methods = (List<MethodDeclaration>) typeOpt.get().getMethods();
        List<MethodDeclaration> candidates = methods.stream()
                .filter(m -> m.getNameAsString().equals(call.getNameAsString()))
                .filter(m -> m.getParameters().size() == call.getArguments().size())
                .toList();
        if (candidates.isEmpty()) return false;
        if (candidates.size() > 1) {
            List<MethodDeclaration> best = bestAstCandidates(candidates, call);
            if (best.isEmpty()) return false;
            emitLocalAstCandidates(call, enclosingId, best, result);
            return true;
        }

        String targetId = methodId(candidates.get(0));
        if (targetId == null || targetId.equals(enclosingId)) return false;

        Edge e = baseEdge(call, enclosingId);
        e.callKind = GraphConstants.CallKind.INSTANCE;
        e.confidence = GraphConstants.Confidence.INFERRED;
        e.targetId = targetId;
        e.isExternal = false;
        result.edges.add(e);
        return true;
    }

    private List<MethodDeclaration> bestAstCandidates(List<MethodDeclaration> candidates, MethodCallExpr call) {
        return CallOverloadResolver.bestAst(candidates, call, this::overloadTypeOfArgument);
    }

    private void emitLocalAstCandidates(MethodCallExpr call, String enclosingId,
                                        List<MethodDeclaration> targets, ExtractionResult result) {
        boolean ambiguous = targets.size() > 1;
        List<String> candidates = targets.stream()
                .map(this::methodId)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        Map<String, Object> meta = new LinkedHashMap<>();
        if (ambiguous) meta.put("reason", "overload");
        meta.put("arguments", call.getArguments().stream()
                .map(this::fallbackTypeOfArgument).collect(Collectors.toList()));
        meta.put("candidates", candidates);
        String metadata = Json.writeCompact(meta);
        for (MethodDeclaration target : targets) {
            String targetId = methodId(target);
            if (targetId == null || targetId.equals(enclosingId)) continue;
            Edge e = baseEdge(call, enclosingId);
            e.callKind = target.isStatic() ? GraphConstants.CallKind.STATIC : GraphConstants.CallKind.INSTANCE;
            e.confidence = ambiguous
                    ? GraphConstants.Confidence.AMBIGUOUS
                    : GraphConstants.Confidence.INFERRED;
            e.targetId = targetId;
            e.isExternal = false;
            e.metadata = metadata;
            result.edges.add(e);
        }
    }

    private String methodId(MethodDeclaration method) {
        try { return CallableIdFactory.forMethod(ctx.idGenerator(), method); }
        catch (RuntimeException e) { ctx.incrementUnresolved(e); }

        Optional<TypeDeclaration> typeOpt = method.findAncestor(TypeDeclaration.class);
        if (typeOpt.isEmpty()) return null;

        Optional<CompilationUnit> cuOpt = method.findCompilationUnit();
        String pkg = cuOpt.flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString() + ".")
                .orElse("");
        return pkg + typeOpt.get().getNameAsString()
                + "#" + method.getNameAsString()
                + "(" + method.getParameters().stream()
                        .map(p -> {
                            try { return NodeIdGenerator.erasedTypeDescribe(p.getType().resolve()); }
                            catch (RuntimeException e) { return AstTypeNames.of(p.getType(), p); }
                        })
                        .collect(Collectors.joining(","))
                + ")";
    }

    private boolean emitScopeTypeFallback(MethodCallExpr call, String enclosingId, ExtractionResult result) {
        Optional<Expression> scopeOpt = call.getScope();
        if (scopeOpt.isEmpty()) return false;
        if (looksLikeTypeName(scopeOpt.get().toString())) return false;

        ResolvedType scopeType = resolveScopeType(scopeOpt.get());
        if (scopeType == null) return false;
        if (!scopeType.isReferenceType()) return false;

        Optional<ResolvedReferenceTypeDeclaration> declOpt;
        try {
            declOpt = scopeType.asReferenceType().getTypeDeclaration();
        } catch (RuntimeException e) {
            ctx.incrementUnresolved(e);
            return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result);
        }
        if (declOpt.isEmpty()) return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result);
        if (!(declOpt.get() instanceof MethodResolutionCapability capability)) {
            return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result);
        }

        SymbolReference<ResolvedMethodDeclaration> solved;
        try {
            solved = capability.solveMethod(call.getNameAsString(), argumentTypes(call), false);
        } catch (RuntimeException e) {
            ctx.incrementUnresolved(e);
            solved = SymbolReference.unsolved(ResolvedMethodDeclaration.class);
        }
        if (solved.isSolved() && !unreliableSignature(solved.getCorrespondingDeclaration())) {
            emit(call, solved.getCorrespondingDeclaration(),
                    CallKindClassifier.classify(solved.getCorrespondingDeclaration(), call), result);
            return true;
        }

        List<MethodDeclaration> astTargets = resolveByAstOverload(declOpt.get(), call);
        if (astTargets.isEmpty()) astTargets = resolveByLexicalAstOverload(declOpt.get(), call);
        if (astTargets.isEmpty()) {
            String lexicalScopeType = lexicalScopeType(scopeOpt.get());
            astTargets = resolveByLexicalAstOverload(lexicalScopeType, call);
        }
        if (!astTargets.isEmpty()) {
            emitAstCandidates(call, declOpt.get(), astTargets, result);
            return true;
        }

        List<ResolvedMethodDeclaration> targets = resolveByFallbackOverload(declOpt.get(), call);
        if (targets.isEmpty()) return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result);
        if (targets.size() == 1) {
            emitInferred(call, targets.get(0),
                    CallKindClassifier.classify(targets.get(0), call), result, null);
        } else {
            emitAmbiguous(call, targets, result);
        }
        return true;
    }

    private String lexicalScopeType(Expression scope) {
        if (scope == null) return null;
        if (lexicalScopeTypes.containsKey(scope)) return lexicalScopeTypes.get(scope);
        String type = null;
        if (scope.isNameExpr()) {
            type = AstTypeNames.findVisibleNameType(scope.asNameExpr().getNameAsString(), scope);
        }
        lexicalScopeTypes.put(scope, type);
        return type;
    }

    private List<MethodDeclaration> resolveByAstOverload(ResolvedReferenceTypeDeclaration decl, MethodCallExpr call) {
        Optional<com.github.javaparser.ast.Node> ast;
        try { ast = decl.toAst(); }
        catch (RuntimeException e) { return List.of(); }
        if (ast.isEmpty() || !(ast.get() instanceof TypeDeclaration<?> type)) return List.of();
        List<MethodDeclaration> candidates = type.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(call.getNameAsString()))
                .filter(m -> m.getParameters().size() == call.getArguments().size())
                .toList();
        if (candidates.size() <= 1) return candidates;
        return CallOverloadResolver.bestAst(candidates, call, this::overloadTypeOfArgument);
    }

    private List<MethodDeclaration> resolveByLexicalAstOverload(ResolvedReferenceTypeDeclaration decl, MethodCallExpr call) {
        Optional<CompilationUnit> cuOpt = call.findCompilationUnit();
        if (cuOpt.isEmpty()) return List.of();
        String qualified;
        try { qualified = decl.getQualifiedName(); }
        catch (RuntimeException e) { return List.of(); }
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (ClassOrInterfaceDeclaration type : lexicalTypes) {
            if (!simple.equals(type.getNameAsString())) continue;
            String typeFqn = AstTypeNames.qualifySimpleName(type, type.getNameAsString());
            if (!qualified.equals(typeFqn) && !simple.equals(type.getNameAsString())) continue;
            return resolveByAstOverload(type, call);
        }
        return List.of();
    }

    private List<MethodDeclaration> resolveByLexicalAstOverload(String typeName, MethodCallExpr call) {
        if (typeName == null || typeName.isBlank()) return List.of();
        Optional<CompilationUnit> cuOpt = call.findCompilationUnit();
        if (cuOpt.isEmpty()) return List.of();
        String simple = typeName.substring(typeName.lastIndexOf('.') + 1);
        for (ClassOrInterfaceDeclaration type : lexicalTypes) {
            if (simple.equals(type.getNameAsString())) return resolveByAstOverload(type, call);
        }
        return List.of();
    }

    private List<MethodDeclaration> resolveByAstOverload(TypeDeclaration<?> type, MethodCallExpr call) {
        List<MethodDeclaration> candidates = type.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(call.getNameAsString()))
                .filter(m -> m.getParameters().size() == call.getArguments().size())
                .toList();
        if (candidates.size() <= 1) return candidates;
        return CallOverloadResolver.bestAst(candidates, call, this::overloadTypeOfArgument);
    }

    private void emitAstCandidates(MethodCallExpr call, ResolvedReferenceTypeDeclaration owner,
                                   List<MethodDeclaration> targets, ExtractionResult result) {
        String enclosingId = enclosingMethodId(call);
        if (enclosingId == null) return;
        boolean ambiguous = targets.size() > 1;
        List<String> candidates = targets.stream()
                .map(m -> astMethodId(owner, m))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        Map<String, Object> meta = new LinkedHashMap<>();
        if (ambiguous) meta.put("reason", "overload");
        meta.put("arguments", call.getArguments().stream()
                .map(this::fallbackTypeOfArgument).collect(Collectors.toList()));
        meta.put("candidates", candidates);
        String metadata = Json.writeCompact(meta);

        for (MethodDeclaration target : targets) {
            Edge e = baseEdge(call, enclosingId);
            e.callKind = target.isStatic() ? GraphConstants.CallKind.STATIC
                    : (owner.isInterface() ? GraphConstants.CallKind.INTERFACE : GraphConstants.CallKind.INSTANCE);
            e.confidence = ambiguous
                    ? GraphConstants.Confidence.AMBIGUOUS
                    : GraphConstants.Confidence.INFERRED;
            e.metadata = metadata;
            if (ctx.isProjectInternal(owner)) {
                e.targetId = astMethodId(owner, target);
                e.isExternal = false;
            } else {
                e.externalTargetFqn = astMethodId(owner, target);
                e.isExternal = true;
                e.resolution = GraphConstants.Resolution.AST_FALLBACK;
            }
            result.edges.add(e);
        }
    }

    private String astMethodId(ResolvedReferenceTypeDeclaration owner, MethodDeclaration method) {
        String params = method.getParameters().stream()
                .map(p -> AstTypeNames.of(p.getType(), p))
                .collect(Collectors.joining(","));
        return owner.getQualifiedName() + "#" + method.getNameAsString() + "(" + params + ")";
    }

    private boolean emitTypedScopeExternalFallback(MethodCallExpr call, String enclosingId,
                                                  ResolvedType scopeType, ExtractionResult result) {
        String typeFqn;
        try {
            typeFqn = NodeIdGenerator.erasedTypeDescribe(scopeType);
        } catch (RuntimeException e) {
            ctx.incrementUnresolved(e);
            return false;
        }
        if (typeFqn == null || typeFqn.isBlank() || "<unresolved>".equals(typeFqn)) return false;

        Edge e = baseEdge(call, enclosingId);
        e.callKind = GraphConstants.CallKind.INSTANCE;
        e.confidence = GraphConstants.Confidence.INFERRED;
        e.externalTargetFqn = typeFqn + "#" + call.getNameAsString()
                + "(" + fallbackParameterList(call) + ")";
        e.isExternal = true;
        e.resolution = GraphConstants.Resolution.TYPE_FALLBACK;
        result.edges.add(e);
        return true;
    }

    private List<ResolvedMethodDeclaration> resolveByFallbackOverload(
            ResolvedReferenceTypeDeclaration decl, MethodCallExpr call) {
        List<ResolvedMethodDeclaration> candidates = new ArrayList<>();
        try {
            for (MethodUsage usage : decl.getAllMethods()) {
                ResolvedMethodDeclaration m = usage.getDeclaration();
                if (m.isStatic()) continue;
                if (!m.getName().equals(call.getNameAsString())) continue;
                if (m.getNumberOfParams() != call.getArguments().size()) continue;
                candidates.add(m);
            }
        } catch (RuntimeException e) {
            ctx.incrementUnresolved(e);
            return List.of();
        }
        if (candidates.size() <= 1) {
            if (candidates.size() == 1 && unreliableSignature(candidates.get(0))) return List.of();
            return candidates;
        }

        return CallOverloadResolver.bestResolved(candidates, call, this::overloadTypeOfArgument);
    }

    private void emitStaticNameFallback(MethodCallExpr call, String enclosingId,
                                        ExtractionResult result) {
        Optional<Expression> scopeOpt = call.getScope();
        if (scopeOpt.isEmpty()) return;
        String scope = scopeOpt.get().toString();
        if (!looksLikeTypeName(scope)) return;
        String typeFqn = resolveStaticScopeName(call, scope);
        if (typeFqn == null || typeFqn.isBlank()) return;

        Edge e = baseEdge(call, enclosingId);
        e.callKind = GraphConstants.CallKind.STATIC;
        e.confidence = GraphConstants.Confidence.INFERRED;
        e.externalTargetFqn = typeFqn + "#" + call.getNameAsString()
                + "(" + fallbackParameterList(call) + ")";
        e.isExternal = true;
        e.resolution = GraphConstants.Resolution.STATIC_NAME_FALLBACK;
        result.edges.add(e);
    }

    private ResolvedType resolveScopeType(Expression scope) {
        Optional<ResolvedType> cached = scopeTypes.get(scope);
        if (cached != null) return cached.orElse(null);
        ResolvedType calculated = calculatedType(scope);
        if (calculated != null) {
            scopeTypes.put(scope, Optional.of(calculated));
            return calculated;
        }

        try {
            ResolvedValueDeclaration value = null;
            if (scope.isNameExpr()) {
                value = scope.asNameExpr().resolve();
            } else if (scope.isFieldAccessExpr()) {
                value = scope.asFieldAccessExpr().resolve();
            }
            ResolvedType result = value == null ? null : value.getType();
            scopeTypes.put(scope, Optional.ofNullable(result));
            return result;
        } catch (RuntimeException e) {
            scopeTypes.put(scope, Optional.empty());
            return null;
        }
    }

    private Edge baseEdge(com.github.javaparser.ast.Node callNode, String sourceId) {
        Edge e = new Edge();
        e.sourceId = sourceId;
        e.relation = GraphConstants.Relation.CALLS;
        e.confidence = GraphConstants.Confidence.EXTRACTED;
        e.sourceLocation = "L" + callNode.getBegin().map(p -> p.line).orElse(0);
        e.context = ControlContext.of(callNode);
        return e;
    }

    private List<ResolvedType> argumentTypes(MethodCallExpr call) {
        List<ResolvedType> out = new ArrayList<>();
        for (Expression arg : call.getArguments()) {
            ResolvedType type = calculatedType(arg);
            if (type == null) return List.of();
            out.add(type);
        }
        return out;
    }

    private String fallbackParameterList(MethodCallExpr call) {
        return call.getArguments().stream()
                .map(this::fallbackTypeOfArgument)
                .collect(Collectors.joining(","));
    }

    private String fallbackTypeOfArgument(Expression arg) {
        if (renderedTypes.containsKey(arg)) return renderedTypes.get(arg);
        String rendered;
        if (arg instanceof ObjectCreationExpr oce && oce.getAnonymousClassBody().isPresent()) {
            try { rendered = NodeIdGenerator.erasedTypeDescribe(oce.getType().resolve()); }
            catch (RuntimeException e) { rendered = "<unresolved>"; }
        } else {
            rendered = AstTypeNames.ofExpression(arg);
        }
        renderedTypes.put(arg, rendered);
        return rendered;
    }

    private String overloadTypeOfArgument(Expression argument) {
        if (overloadTypes.containsKey(argument)) return overloadTypes.get(argument);
        String rendered = AstTypeNames.ofExpression(argument);
        overloadTypes.put(argument, rendered);
        return rendered;
    }

    private ResolvedType calculatedType(Expression expression) {
        Optional<ResolvedType> cached = calculatedTypes.get(expression);
        if (cached != null) return cached.orElse(null);
        try {
            ResolvedType type = expression.calculateResolvedType();
            calculatedTypes.put(expression, Optional.of(type));
            return type;
        } catch (RuntimeException e) {
            calculatedTypes.put(expression, Optional.empty());
            return null;
        }
    }

    private String resolveStaticScopeName(MethodCallExpr call, String scope) {
        if (scope == null || scope.isBlank()) return null;
        if (scope.contains(".")) return scope;

        Optional<CompilationUnit> cuOpt = call.findCompilationUnit();
        if (cuOpt.isPresent()) {
            CompilationUnit cu = cuOpt.get();
            for (var imp : cu.getImports()) {
                if (imp.isAsterisk()) continue;
                String imported = imp.getNameAsString();
                int dot = imported.lastIndexOf('.');
                if (dot >= 0 && imported.substring(dot + 1).equals(scope)) {
                    return imported;
                }
            }
            return AstTypeNames.qualifySimpleName(call, scope);
        }
        return scope;
    }

    private static boolean looksLikeTypeName(String scope) {
        if (scope == null || scope.isBlank()) return false;
        if (looksLikeEnumConstantScope(scope)) return false;
        String last = scope;
        int dot = last.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < last.length()) last = last.substring(dot + 1);
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0));
    }

    private static boolean looksLikeEnumConstantScope(String scope) {
        int dot = scope.lastIndexOf('.');
        if (dot <= 0 || dot + 1 >= scope.length()) return false;
        String first = scope.substring(0, scope.indexOf('.'));
        String last = scope.substring(dot + 1);
        if (first.isEmpty() || !Character.isUpperCase(first.charAt(0))) return false;
        return last.contains("_") || last.equals(last.toUpperCase(Locale.ROOT));
    }

    private String enclosingMethodId(com.github.javaparser.ast.Node node) {
        return enclosing.ownerIdOf(node);
    }
}
