package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.core.nativeimage.EmbeddedJdkClassDeclaration;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
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
import java.util.Set;
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
    private final Set<Expression> resolvingScopeTypes = java.util.Collections.newSetFromMap(
            new IdentityHashMap<>());
    private EmbeddedJdkClassDeclaration embeddedJdkAnchor;
    private List<ClassOrInterfaceDeclaration> lexicalTypes = List.of();

    private enum FallbackOutcome { NONE, APPROXIMATE, EXACT, AMBIGUOUS }

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
                    if (emitDeterministicLexicalScopedCall(n, result)) {
                        super.visit(n, arg);
                        return;
                    }
                    if (emitDeterministicLexicalOverload(n, result)) {
                        super.visit(n, arg);
                        return;
                    }
                    target = n.resolve();
                    if (unreliableSignature(target)) {
                        emitFallback(n, result);
                        super.visit(n, arg);
                        return;
                    }
                    if (emitDeterministicProjectOverload(n, target, result)) {
                        super.visit(n, arg);
                        return;
                    }
                    String callKind = CallKindClassifier.classify(target, n);
                    emit(n, target, callKind, result);
                } catch (RuntimeException e) {
                    FallbackOutcome outcome = emitFallback(n, result);
                    if (outcome != FallbackOutcome.EXACT) {
                        ctx.incrementUnresolved(e, n, n.getNameAsString());
                    }
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
                catch (RuntimeException e) {
                    ctx.incrementUnresolved(e, n, n.getTypeAsString());
                    super.visit(n, arg);
                    return;
                }
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
        resolvingScopeTypes.clear();
        embeddedJdkAnchor = null;
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
        catch (RuntimeException ex) { ctx.incrementUnresolved(ex, callNode, target.getName()); return; }

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

    private void emitTypeFallback(com.github.javaparser.ast.Node callNode,
                                  ResolvedMethodLikeDeclaration target, String callKind,
                                  ExtractionResult result, String metadata) {
        int before = result.edges.size();
        emitInferred(callNode, target, callKind, result, metadata);
        if (result.edges.size() > before) {
            Edge edge = result.edges.get(result.edges.size() - 1);
            if (edge.isExternal) edge.resolution = GraphConstants.Resolution.TYPE_FALLBACK;
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
            catch (RuntimeException ex) {
                ctx.incrementUnresolved(ex, call, target.getName());
                continue;
            }
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

    private FallbackOutcome emitFallback(MethodCallExpr call, ExtractionResult result) {
        String enclosingId = enclosingMethodId(call);
        if (enclosingId == null) return FallbackOutcome.NONE;

        FallbackOutcome local = emitLocalMethodFallback(call, enclosingId, result);
        if (local != FallbackOutcome.NONE) return local;
        FallbackOutcome scoped = emitScopeTypeFallback(call, enclosingId, result);
        if (scoped != FallbackOutcome.NONE) return scoped;
        FallbackOutcome staticDeclaration = emitStaticDeclarationFallback(call, result);
        if (staticDeclaration != FallbackOutcome.NONE) return staticDeclaration;
        return emitStaticNameFallback(call, enclosingId, result)
                ? FallbackOutcome.APPROXIMATE : FallbackOutcome.NONE;
    }

    private FallbackOutcome emitStaticDeclarationFallback(MethodCallExpr call,
                                                           ExtractionResult result) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty() || !looksLikeTypeName(scope.get().toString())) {
            return FallbackOutcome.NONE;
        }
        ResolvedMethodDeclaration target = fallbackDeclaration(call);
        if (target == null || !target.isStatic()) return FallbackOutcome.NONE;
        emitTypeFallback(call, target, GraphConstants.CallKind.STATIC, result, null);
        return FallbackOutcome.EXACT;
    }

    private FallbackOutcome emitLocalMethodFallback(MethodCallExpr call, String enclosingId,
                                                    ExtractionResult result) {
        if (call.getScope().isPresent()) return FallbackOutcome.NONE;

        Optional<TypeDeclaration> typeOpt = call.findAncestor(TypeDeclaration.class);
        if (typeOpt.isEmpty()) return FallbackOutcome.NONE;

        @SuppressWarnings("unchecked")
        List<MethodDeclaration> methods = (List<MethodDeclaration>) typeOpt.get().getMethods();
        List<MethodDeclaration> candidates = methods.stream()
                .filter(m -> m.getNameAsString().equals(call.getNameAsString()))
                .filter(m -> m.getParameters().size() == call.getArguments().size())
                .toList();
        if (candidates.isEmpty()) return FallbackOutcome.NONE;
        if (candidates.size() > 1) {
            List<MethodDeclaration> best = bestAstCandidates(candidates, call);
            if (best.isEmpty()) return FallbackOutcome.NONE;
            emitLocalAstCandidates(call, enclosingId, best, result);
            return best.size() == 1 ? FallbackOutcome.EXACT : FallbackOutcome.AMBIGUOUS;
        }

        String targetId = methodId(candidates.get(0));
        if (targetId == null || targetId.equals(enclosingId)) return FallbackOutcome.NONE;

        Edge e = baseEdge(call, enclosingId);
        e.callKind = GraphConstants.CallKind.INSTANCE;
        e.confidence = GraphConstants.Confidence.INFERRED;
        e.targetId = targetId;
        e.isExternal = false;
        result.edges.add(e);
        return FallbackOutcome.EXACT;
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
        String metadata = null;
        if (ambiguous) {
            meta.put("arguments", call.getArguments().stream()
                    .map(this::fallbackTypeOfArgument).collect(Collectors.toList()));
            meta.put("candidates", candidates);
            metadata = Json.writeCompact(meta);
        }
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
        catch (RuntimeException e) {
            ctx.incrementUnresolved(e, method, method.getNameAsString());
        }

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

    private FallbackOutcome emitScopeTypeFallback(MethodCallExpr call, String enclosingId,
                                                  ExtractionResult result) {
        Optional<Expression> scopeOpt = call.getScope();
        if (scopeOpt.isEmpty()) return FallbackOutcome.NONE;
        if (looksLikeTypeName(scopeOpt.get().toString())) return FallbackOutcome.NONE;

        ResolvedType scopeType = resolveScopeType(scopeOpt.get());
        if (scopeType == null) return FallbackOutcome.NONE;
        if (!scopeType.isReferenceType()) return FallbackOutcome.NONE;

        Optional<ResolvedReferenceTypeDeclaration> declOpt;
        try {
            declOpt = scopeType.asReferenceType().getTypeDeclaration();
        } catch (RuntimeException e) {
            return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result)
                    ? FallbackOutcome.APPROXIMATE : FallbackOutcome.NONE;
        }
        if (declOpt.isEmpty()) {
            return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result)
                    ? FallbackOutcome.APPROXIMATE : FallbackOutcome.NONE;
        }
        if (declOpt.get() instanceof EmbeddedJdkClassDeclaration embedded) {
            embeddedJdkAnchor = embedded;
        }
        if (!(declOpt.get() instanceof MethodResolutionCapability capability)) {
            return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result)
                    ? FallbackOutcome.APPROXIMATE : FallbackOutcome.NONE;
        }

        SymbolReference<ResolvedMethodDeclaration> solved;
        try {
            solved = capability.solveMethod(call.getNameAsString(), argumentTypes(call), false);
        } catch (RuntimeException e) {
            solved = SymbolReference.unsolved(ResolvedMethodDeclaration.class);
        }
        if (solved.isSolved() && !unreliableSignature(solved.getCorrespondingDeclaration())) {
            emitTypeFallback(call, solved.getCorrespondingDeclaration(),
                    CallKindClassifier.classify(solved.getCorrespondingDeclaration(), call), result, null);
            return FallbackOutcome.EXACT;
        }

        List<MethodDeclaration> astTargets = resolveByAstOverload(declOpt.get(), call);
        if (astTargets.isEmpty()) astTargets = resolveByLexicalAstOverload(declOpt.get(), call);
        if (astTargets.isEmpty()) {
            String lexicalScopeType = lexicalScopeType(scopeOpt.get());
            astTargets = resolveByLexicalAstOverload(lexicalScopeType, call);
        }
        if (!astTargets.isEmpty()) {
            emitAstCandidates(call, declOpt.get(), astTargets, result);
            return astTargets.size() == 1 ? FallbackOutcome.EXACT : FallbackOutcome.AMBIGUOUS;
        }

        List<ResolvedMethodDeclaration> targets = resolveByFallbackOverload(declOpt.get(), call);
        if (targets.isEmpty()) {
            // The embedded JDK catalog is authoritative for its target release.
            // Do not invent a newer JDK method merely because the receiver type
            // is known (for example Stream#toList() in the Java 8 catalog).
            if (declOpt.get() instanceof EmbeddedJdkClassDeclaration) {
                return FallbackOutcome.NONE;
            }
            return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result)
                    ? FallbackOutcome.APPROXIMATE : FallbackOutcome.NONE;
        }
        if (targets.size() == 1) {
            emitTypeFallback(call, targets.get(0),
                    CallKindClassifier.classify(targets.get(0), call), result, null);
        } else {
            emitAmbiguous(call, targets, result);
        }
        return targets.size() == 1 ? FallbackOutcome.EXACT : FallbackOutcome.AMBIGUOUS;
    }

    /**
     * JavaParser's resolved declaration can depend on iteration order when a
     * project-source type has same-arity overloads. Re-select from its AST so
     * the emitted graph follows source declaration order and the call argument
     * types, rather than an implementation-set traversal order.
     */
    private boolean emitDeterministicProjectOverload(MethodCallExpr call,
                                                     ResolvedMethodDeclaration resolved,
                                                     ExtractionResult result) {
        ResolvedReferenceTypeDeclaration owner;
        try {
            owner = resolved.declaringType();
        } catch (RuntimeException e) {
            return false;
        }
        if (!ctx.isProjectInternal(owner)) return false;

        List<MethodDeclaration> candidates = astOverloadCandidates(owner, call);
        if (candidates.size() < 2) return false;
        List<MethodDeclaration> targets = deterministicAstTargets(candidates, call);
        emitAstCandidates(call, owner, targets, result);
        return true;
    }

    private boolean emitDeterministicLexicalOverload(MethodCallExpr call, ExtractionResult result) {
        if (call.getScope().isPresent()) return false;
        Optional<TypeDeclaration> owner = call.findAncestor(TypeDeclaration.class);
        if (owner.isEmpty()) return false;
        List<MethodDeclaration> candidates = astOverloadCandidates(owner.get(), call);
        if (candidates.size() < 2) return false;
        List<MethodDeclaration> targets = deterministicAstTargets(candidates, call);
        String enclosingId = enclosingMethodId(call);
        if (enclosingId == null) return false;
        emitLocalAstCandidates(call, enclosingId, targets, result);
        return true;
    }

    private boolean emitDeterministicLexicalScopedCall(MethodCallExpr call,
                                                        ExtractionResult result) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) return false;
        String typeName = null;
        String callKind = GraphConstants.CallKind.INSTANCE;
        Expression expression = scope.get();
        if (looksLikeTypeName(expression.toString())) {
            typeName = resolveStaticScopeName(call, expression.toString());
            callKind = GraphConstants.CallKind.STATIC;
        } else if (expression.isNameExpr()) {
            typeName = AstTypeNames.findVisibleNameType(
                    expression.asNameExpr().getNameAsString(), expression);
        } else if (expression.isThisExpr()) {
            Optional<TypeDeclaration> owner = call.findAncestor(TypeDeclaration.class);
            if (owner.isPresent()) {
                typeName = AstTypeNames.qualifySimpleName(owner.get(), owner.get().getNameAsString());
            }
        } else if (expression.isSuperExpr()) {
            Optional<ClassOrInterfaceDeclaration> owner =
                    call.findAncestor(ClassOrInterfaceDeclaration.class);
            if (owner.isPresent() && !owner.get().getExtendedTypes().isEmpty()) {
                typeName = AstTypeNames.ofAst(owner.get().getExtendedTypes().get(0), owner.get());
                callKind = GraphConstants.CallKind.SUPER;
            }
        }
        if (!AstTypeNames.resolved(typeName)) return false;
        String parameters = call.getArguments().stream()
                .map(AstTypeNames::ofExpressionStable)
                .collect(Collectors.joining(","));
        if (parameters.contains("<unresolved>")) return false;

        String enclosingId = enclosingMethodId(call);
        if (enclosingId == null) return false;
        Edge edge = baseEdge(call, enclosingId);
        edge.callKind = callKind;
        edge.confidence = GraphConstants.Confidence.INFERRED;
        edge.externalTargetFqn = typeName + "#" + call.getNameAsString()
                + "(" + parameters + ")";
        edge.isExternal = true;
        edge.resolution = GraphConstants.Resolution.AST_FALLBACK;
        result.edges.add(edge);
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
        List<MethodDeclaration> candidates = astOverloadCandidates(decl, call);
        if (candidates.size() <= 1) return candidates;
        return deterministicAstTargets(candidates, call);
    }

    private List<MethodDeclaration> astOverloadCandidates(ResolvedReferenceTypeDeclaration decl,
                                                            MethodCallExpr call) {
        Optional<com.github.javaparser.ast.Node> ast;
        try { ast = decl.toAst(); }
        catch (RuntimeException e) { return List.of(); }
        if (ast.isEmpty() || !(ast.get() instanceof TypeDeclaration<?> type)) return List.of();
        return type.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(call.getNameAsString()))
                .filter(m -> CallOverloadResolver.matchesArity(m, call.getArguments().size()))
                .toList();
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
        List<MethodDeclaration> candidates = astOverloadCandidates(type, call);
        if (candidates.size() <= 1) return candidates;
        return deterministicAstTargets(candidates, call);
    }

    private List<MethodDeclaration> deterministicAstTargets(List<MethodDeclaration> candidates,
                                                              MethodCallExpr call) {
        List<MethodDeclaration> best = CallOverloadResolver.bestAst(
                candidates, call, this::overloadTypeOfArgument);
        // The AST-only matcher intentionally does not model full inheritance
        // conversion. Keep every applicable source overload rather than let a
        // hash-backed SymbolSolver traversal choose one arbitrarily.
        return best.isEmpty() ? candidates : best;
    }

    private List<MethodDeclaration> astOverloadCandidates(TypeDeclaration<?> type, MethodCallExpr call) {
        return type.getMethods().stream()
                .filter(m -> m.getNameAsString().equals(call.getNameAsString()))
                .filter(m -> CallOverloadResolver.matchesArity(m, call.getArguments().size()))
                .toList();
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
        String metadata = null;
        if (ambiguous) {
            meta.put("arguments", call.getArguments().stream()
                    .map(this::fallbackTypeOfArgument).collect(Collectors.toList()));
            meta.put("candidates", candidates);
            metadata = Json.writeCompact(meta);
        }

        for (MethodDeclaration target : targets.stream()
                .sorted(java.util.Comparator.comparing(method -> astMethodId(owner, method)))
                .toList()) {
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
            ctx.incrementUnresolved(e, call, call.getNameAsString());
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
        return resolveByFallbackOverload(decl, call, false);
    }

    private List<ResolvedMethodDeclaration> resolveByFallbackOverload(
            ResolvedReferenceTypeDeclaration decl, MethodCallExpr call, boolean staticCall) {
        List<ResolvedMethodDeclaration> candidates = new ArrayList<>();
        try {
            for (MethodUsage usage : decl.getAllMethods()) {
                ResolvedMethodDeclaration m = usage.getDeclaration();
                if (m.isStatic() != staticCall) continue;
                if (!m.getName().equals(call.getNameAsString())) continue;
                if (m.getNumberOfParams() != call.getArguments().size()) continue;
                candidates.add(m);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        if (candidates.size() <= 1) {
            if (candidates.size() == 1 && unreliableSignature(candidates.get(0))) return List.of();
            return candidates;
        }

        return CallOverloadResolver.bestResolved(candidates, call, this::overloadTypeOfArgument);
    }

    private boolean emitStaticNameFallback(MethodCallExpr call, String enclosingId,
                                           ExtractionResult result) {
        Optional<Expression> scopeOpt = call.getScope();
        if (scopeOpt.isEmpty()) return false;
        String scope = scopeOpt.get().toString();
        if (!looksLikeTypeName(scope)) return false;
        String typeFqn = resolveStaticScopeName(call, scope);
        if (typeFqn == null || typeFqn.isBlank()) return false;

        Edge e = baseEdge(call, enclosingId);
        e.callKind = GraphConstants.CallKind.STATIC;
        e.confidence = GraphConstants.Confidence.INFERRED;
        e.externalTargetFqn = typeFqn + "#" + call.getNameAsString()
                + "(" + fallbackParameterList(call) + ")";
        e.isExternal = true;
        e.resolution = GraphConstants.Resolution.STATIC_NAME_FALLBACK;
        result.edges.add(e);
        return true;
    }

    private ResolvedType resolveScopeType(Expression scope) {
        Optional<ResolvedType> cached = scopeTypes.get(scope);
        if (cached != null) return cached.orElse(null);
        if (!resolvingScopeTypes.add(scope)) return null;
        try {
            if (scope.isNameExpr()) {
                ResolvedType lambdaParameter = fallbackLambdaParameterType(scope.asNameExpr());
                if (usableValueType(lambdaParameter)) {
                    rememberEmbeddedJdkType(lambdaParameter);
                    scopeTypes.put(scope, Optional.of(lambdaParameter));
                    return lambdaParameter;
                }
            }
            ResolvedType calculated = calculatedType(scope);
            if (calculated != null) {
                rememberEmbeddedJdkType(calculated);
                scopeTypes.put(scope, Optional.of(calculated));
                return calculated;
            }

            ResolvedType result = null;
            if (scope.isMethodCallExpr()) {
                result = fallbackReturnType(scope.asMethodCallExpr());
            } else {
                ResolvedValueDeclaration value = null;
                if (scope.isNameExpr()) {
                    value = scope.asNameExpr().resolve();
                } else if (scope.isFieldAccessExpr()) {
                    value = scope.asFieldAccessExpr().resolve();
                }
                if (result == null) result = value == null ? null : value.getType();
            }
            rememberEmbeddedJdkType(result);
            scopeTypes.put(scope, Optional.ofNullable(result));
            return result;
        } catch (RuntimeException e) {
            scopeTypes.put(scope, Optional.empty());
            return null;
        } finally {
            resolvingScopeTypes.remove(scope);
        }
    }

    /**
     * Recover the result type of a call without requiring JavaParser to solve the
     * whole generic chain. This stays declaration-backed: no method is invented.
     */
    private ResolvedType fallbackReturnType(MethodCallExpr call) {
        ResolvedMethodDeclaration target = fallbackDeclaration(call);
        if (isOptionalOrElse(call, target)) {
            ResolvedType primary = optionalFactoryValueType(call);
            ResolvedType fallback = call.getArguments().isEmpty()
                    ? null : resolveScopeType(call.getArgument(0));
            if (primary != null && fallback != null && !sameErasure(primary, fallback)) return null;
            if (usableValueType(primary)) return primary;
            if (usableValueType(fallback)) return fallback;
        }
        if (target == null) return null;
        try {
            ResolvedType returned = target.getReturnType();
            return usableValueType(returned) ? returned : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * JavaParser can lose nested lambda parameter types after a generic chain
     * fails. Recover only directly declared Function targets and element-
     * preserving Stream calls; this is local target typing, not value flow.
     */
    private ResolvedType fallbackLambdaParameterType(NameExpr name) {
        Node current = name;
        while ((current = current.getParentNode().orElse(null)) != null) {
            if (!(current instanceof LambdaExpr lambda)) continue;
            for (int index = 0; index < lambda.getParameters().size(); index++) {
                Parameter parameter = lambda.getParameter(index);
                if (!name.getNameAsString().equals(parameter.getNameAsString())) continue;
                if (!parameter.getType().isUnknownType()) {
                    try { return parameter.getType().resolve(); }
                    catch (RuntimeException ignored) { }
                }
                ResolvedType target = directLambdaTargetParameter(lambda, index);
                if (usableValueType(target)) return target;
            }
        }
        return null;
    }

    private ResolvedType directLambdaTargetParameter(LambdaExpr lambda, int parameterIndex) {
        Node parent = lambda.getParentNode().orElse(null);
        if (parent instanceof VariableDeclarator variable
                && variable.getInitializer().orElse(null) == lambda) {
            try {
                ResolvedType functionalType = variable.getType().resolve();
                return javaFunctionParameter(functionalType, parameterIndex);
            } catch (RuntimeException ignored) { }
        }
        if (parent instanceof MethodCallExpr call && call.getArguments().stream()
                .anyMatch(argument -> argument == lambda)) {
            return streamElementType(call.getScope().orElse(null));
        }
        return null;
    }

    private ResolvedType javaFunctionParameter(ResolvedType type, int parameterIndex) {
        if (type == null || !type.isReferenceType()) return null;
        var reference = type.asReferenceType();
        String qualified;
        try { qualified = reference.getQualifiedName(); }
        catch (RuntimeException e) { return null; }
        List<ResolvedType> values = reference.typeParametersValues();
        if ("java.util.function.Function".equals(qualified)
                && parameterIndex == 0 && values.size() == 2) {
            return values.get(0);
        }
        return null;
    }

    private ResolvedType streamElementType(Expression expression) {
        if (expression == null) return null;
        if (!expression.isMethodCallExpr()) return firstTypeArgument(resolveScopeType(expression));

        MethodCallExpr call = expression.asMethodCallExpr();
        String name = call.getNameAsString();
        if ("stream".equals(name) || "parallelStream".equals(name)) {
            return firstTypeArgument(resolveScopeType(call.getScope().orElse(null)));
        }
        if (Set.of("filter", "distinct", "sorted", "peek", "limit", "skip",
                "takeWhile", "dropWhile", "sequential", "parallel", "unordered",
                "onClose").contains(name)) {
            return streamElementType(call.getScope().orElse(null));
        }
        return null;
    }

    private ResolvedType firstTypeArgument(ResolvedType type) {
        if (type == null || !type.isReferenceType()) return null;
        try {
            List<ResolvedType> values = type.asReferenceType().typeParametersValues();
            return values.isEmpty() ? null : values.get(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void rememberEmbeddedJdkType(ResolvedType type) {
        if (type == null || !type.isReferenceType()) return;
        try {
            type.asReferenceType().getTypeDeclaration()
                    .filter(EmbeddedJdkClassDeclaration.class::isInstance)
                    .map(EmbeddedJdkClassDeclaration.class::cast)
                    .ifPresent(declaration -> embeddedJdkAnchor = declaration);
        } catch (RuntimeException ignored) { }
    }

    private ResolvedMethodDeclaration fallbackDeclaration(MethodCallExpr call) {
        try {
            return call.resolve();
        } catch (RuntimeException ignored) {
            // Fall through to a receiver-backed, unique name/arity lookup.
        }
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) return null;
        if (looksLikeTypeName(scope.get().toString())) {
            if (embeddedJdkAnchor == null) return null;
            String ownerFqn = resolveStaticScopeName(call, scope.get().toString());
            if (ownerFqn == null) return null;
            Optional<ResolvedReferenceTypeDeclaration> owner =
                    embeddedJdkAnchor.solveCatalogType(ownerFqn);
            if (owner.isEmpty()) return null;
            List<ResolvedMethodDeclaration> candidates =
                    resolveByFallbackOverload(owner.get(), call, true);
            return candidates.size() == 1 ? candidates.get(0) : null;
        }
        ResolvedType scopeType = resolveScopeType(scope.get());
        if (scopeType == null || !scopeType.isReferenceType()) return null;
        Optional<ResolvedReferenceTypeDeclaration> declaration;
        try {
            declaration = scopeType.asReferenceType().getTypeDeclaration();
        } catch (RuntimeException e) {
            return null;
        }
        if (declaration.isEmpty()) return null;
        List<ResolvedMethodDeclaration> candidates = resolveByFallbackOverload(declaration.get(), call);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private boolean isOptionalOrElse(MethodCallExpr call, ResolvedMethodDeclaration target) {
        if (!"orElse".equals(call.getNameAsString()) || call.getArguments().size() != 1) return false;
        if (target != null) {
            try {
                if ("java.util.Optional".equals(target.declaringType().getQualifiedName())) return true;
            } catch (RuntimeException ignored) { }
        }
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) return false;
        ResolvedType scopeType = resolveScopeType(scope.get());
        return "java.util.Optional".equals(erasedType(scopeType));
    }

    private ResolvedType optionalFactoryValueType(MethodCallExpr orElse) {
        Optional<Expression> scope = orElse.getScope();
        if (scope.isEmpty() || !scope.get().isMethodCallExpr()) return null;
        MethodCallExpr factory = scope.get().asMethodCallExpr();
        if (!("of".equals(factory.getNameAsString())
                || "ofNullable".equals(factory.getNameAsString()))
                || factory.getArguments().size() != 1) return null;
        ResolvedMethodDeclaration target = fallbackDeclaration(factory);
        if (target == null) return null;
        try {
            if (!"java.util.Optional".equals(target.declaringType().getQualifiedName())) return null;
        } catch (RuntimeException e) {
            return null;
        }
        return resolveScopeType(factory.getArgument(0));
    }

    private boolean usableValueType(ResolvedType type) {
        return type != null && !type.isVoid() && !type.isNull()
                && !type.isTypeVariable() && !type.isWildcard();
    }

    private boolean sameErasure(ResolvedType left, ResolvedType right) {
        String leftName = erasedType(left);
        String rightName = erasedType(right);
        return leftName != null && leftName.equals(rightName);
    }

    private String erasedType(ResolvedType type) {
        if (type == null) return null;
        try {
            String rendered = NodeIdGenerator.erasedTypeDescribe(type);
            return rendered == null || rendered.isBlank() || rendered.startsWith("<")
                    ? null : rendered;
        } catch (RuntimeException e) {
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
        String rendered = AstTypeNames.ofExpressionStable(argument);
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
