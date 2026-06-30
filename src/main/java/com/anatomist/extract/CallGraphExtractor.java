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

    public CallGraphExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
        this.enclosing = new AstEnclosing(ctx.idGenerator());
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
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
                    // Construction of the anonymous class itself; the call
                    // links to the supertype constructor.
                }
                ResolvedConstructorDeclaration target;
                try { target = n.resolve(); }
                catch (RuntimeException e) { ctx.incrementUnresolved(e); super.visit(n, arg); return; }
                emit(n, target, GraphConstants.CallKind.CONSTRUCTOR, result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
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
                e.targetId = ctx.idGenerator().forConstructor(c);
            } else {
                return;
            }
            e.isExternal = false;
        } else {
            e.externalTargetFqn = methodTargetFqn(target);
            e.isExternal = true;
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
                .map(AstTypeNames::ofExpression)
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
        String id = ctx.idGenerator().forMethod(method);
        if (!id.contains("<unresolved>") && !id.contains("(null") && !id.contains(",null")) return id;

        Optional<com.github.javaparser.ast.Node> ast;
        try { ast = method.toAst(); }
        catch (RuntimeException e) { return id; }
        if (ast.isEmpty() || !(ast.get() instanceof MethodDeclaration decl)) return id;
        Optional<TypeDeclaration> typeOpt = decl.findAncestor(TypeDeclaration.class);
        if (typeOpt.isEmpty()) return id;
        String typeId;
        try { typeId = ctx.idGenerator().forType(method.declaringType()); }
        catch (RuntimeException e) {
            Optional<CompilationUnit> cuOpt = decl.findCompilationUnit();
            String pkg = cuOpt.flatMap(CompilationUnit::getPackageDeclaration)
                    .map(p -> p.getNameAsString() + ".")
                    .orElse("");
            typeId = pkg + typeOpt.get().getNameAsString();
        }
        String params = decl.getParameters().stream()
                .map(p -> AstTypeNames.of(p.getType(), p))
                .collect(Collectors.joining(","));
        return typeId + "#" + decl.getNameAsString() + "(" + params + ")";
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
        List<String> argTypes = call.getArguments().stream()
                .map(AstTypeNames::ofExpression)
                .collect(Collectors.toList());
        int best = Integer.MAX_VALUE;
        List<MethodDeclaration> bestCandidates = new ArrayList<>();
        for (MethodDeclaration candidate : candidates) {
            int score = overloadScore(candidate, argTypes);
            if (score < best) {
                best = score;
                bestCandidates.clear();
                bestCandidates.add(candidate);
            } else if (score == best) {
                bestCandidates.add(candidate);
            }
        }
        return best == Integer.MAX_VALUE ? List.of() : bestCandidates;
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
        meta.put("arguments", call.getArguments().stream().map(AstTypeNames::ofExpression).collect(Collectors.toList()));
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
        try { return ctx.idGenerator().forMethod(method.resolve()); }
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
        if (scope.isNameExpr()) {
            return AstTypeNames.findVisibleNameType(scope.asNameExpr().getNameAsString(), scope);
        }
        return null;
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
        List<String> argTypes = call.getArguments().stream()
                .map(AstTypeNames::ofExpression)
                .collect(Collectors.toList());
        int best = Integer.MAX_VALUE;
        List<MethodDeclaration> bestCandidates = new ArrayList<>();
        for (MethodDeclaration candidate : candidates) {
            int score = overloadScore(candidate, argTypes);
            if (score < best) {
                best = score;
                bestCandidates.clear();
                bestCandidates.add(candidate);
            } else if (score == best) {
                bestCandidates.add(candidate);
            }
        }
        return best == Integer.MAX_VALUE ? List.of() : bestCandidates;
    }

    private List<MethodDeclaration> resolveByLexicalAstOverload(ResolvedReferenceTypeDeclaration decl, MethodCallExpr call) {
        Optional<CompilationUnit> cuOpt = call.findCompilationUnit();
        if (cuOpt.isEmpty()) return List.of();
        String qualified;
        try { qualified = decl.getQualifiedName(); }
        catch (RuntimeException e) { return List.of(); }
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        for (ClassOrInterfaceDeclaration type : cuOpt.get().findAll(ClassOrInterfaceDeclaration.class)) {
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
        for (ClassOrInterfaceDeclaration type : cuOpt.get().findAll(ClassOrInterfaceDeclaration.class)) {
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
        List<String> argTypes = call.getArguments().stream()
                .map(AstTypeNames::ofExpression)
                .collect(Collectors.toList());
        int best = Integer.MAX_VALUE;
        List<MethodDeclaration> bestCandidates = new ArrayList<>();
        for (MethodDeclaration candidate : candidates) {
            int score = overloadScore(candidate, argTypes);
            if (score < best) {
                best = score;
                bestCandidates.clear();
                bestCandidates.add(candidate);
            } else if (score == best) {
                bestCandidates.add(candidate);
            }
        }
        return best == Integer.MAX_VALUE ? List.of() : bestCandidates;
    }

    private int overloadScore(MethodDeclaration method, List<String> argTypes) {
        if (method.getParameters().size() != argTypes.size()) return Integer.MAX_VALUE;
        int score = 0;
        for (int i = 0; i < argTypes.size(); i++) {
            String param = AstTypeNames.of(method.getParameter(i).getType(), method.getParameter(i));
            int s = typeMatchScore(argTypes.get(i), param);
            if (s == Integer.MAX_VALUE) return s;
            score += s;
        }
        return score;
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
        meta.put("arguments", call.getArguments().stream().map(AstTypeNames::ofExpression).collect(Collectors.toList()));
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

        List<String> argTypes = call.getArguments().stream()
                .map(AstTypeNames::ofExpression)
                .collect(Collectors.toList());
        int best = Integer.MAX_VALUE;
        List<ResolvedMethodDeclaration> bestCandidates = new ArrayList<>();
        for (ResolvedMethodDeclaration candidate : candidates) {
            int score = overloadScore(candidate, argTypes);
            if (score < best) {
                best = score;
                bestCandidates.clear();
                bestCandidates.add(candidate);
            } else if (score == best) {
                bestCandidates.add(candidate);
            }
        }
        return best == Integer.MAX_VALUE ? List.of() : bestCandidates;
    }

    private int overloadScore(ResolvedMethodDeclaration method, List<String> argTypes) {
        if (method.getNumberOfParams() != argTypes.size()) return Integer.MAX_VALUE;
        int score = 0;
        for (int i = 0; i < argTypes.size(); i++) {
            String arg = argTypes.get(i);
            String param;
            try { param = NodeIdGenerator.erasedTypeDescribe(method.getParam(i).getType()); }
            catch (RuntimeException e) { param = "<unresolved>"; }
            int s = typeMatchScore(arg, param);
            if (s == Integer.MAX_VALUE) return s;
            score += s;
        }
        return score;
    }

    private static int typeMatchScore(String arg, String param) {
        if (!AstTypeNames.resolved(arg) || !AstTypeNames.resolved(param)) return 8;
        if (arg.equals(param)) return 0;
        if ("<null>".equals(arg)) return isPrimitive(param) ? Integer.MAX_VALUE : 4;
        if (boxed(arg).equals(boxed(param))) return 1;
        if (isPrimitive(arg) != isPrimitive(param)) return Integer.MAX_VALUE;
        if ("java.lang.Object".equals(param)) return 6;
        return Integer.MAX_VALUE;
    }

    private static String boxed(String type) {
        return switch (type) {
            case "boolean" -> "java.lang.Boolean";
            case "byte" -> "java.lang.Byte";
            case "char" -> "java.lang.Character";
            case "double" -> "java.lang.Double";
            case "float" -> "java.lang.Float";
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "short" -> "java.lang.Short";
            default -> type;
        };
    }

    private static boolean isPrimitive(String type) {
        return Set.of("boolean", "byte", "char", "double", "float", "int", "long", "short", "void").contains(type);
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
        result.edges.add(e);
    }

    private ResolvedType resolveScopeType(Expression scope) {
        try { return scope.calculateResolvedType(); }
        catch (RuntimeException ignore) { }

        try {
            ResolvedValueDeclaration value = null;
            if (scope.isNameExpr()) {
                value = scope.asNameExpr().resolve();
            } else if (scope.isFieldAccessExpr()) {
                value = scope.asFieldAccessExpr().resolve();
            }
            return value == null ? null : value.getType();
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
            try { out.add(arg.calculateResolvedType()); }
            catch (RuntimeException e) { return List.of(); }
        }
        return out;
    }

    private String fallbackParameterList(MethodCallExpr call) {
        return call.getArguments().stream()
                .map(this::fallbackTypeOfArgument)
                .collect(Collectors.joining(","));
    }

    private String fallbackTypeOfArgument(Expression arg) {
        if (arg instanceof ObjectCreationExpr oce && oce.getAnonymousClassBody().isPresent()) {
            try { return NodeIdGenerator.erasedTypeDescribe(oce.getType().resolve()); }
            catch (RuntimeException e) { return "<unresolved>"; }
        }
        return AstTypeNames.ofExpression(arg);
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
