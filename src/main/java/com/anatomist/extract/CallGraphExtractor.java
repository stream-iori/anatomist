package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
                    String callKind = classify(target, n);
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
                emit(n, target, "CONSTRUCTOR", result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private static String classify(ResolvedMethodDeclaration target, MethodCallExpr call) {
        if (call.getScope().isPresent() && call.getScope().get() instanceof SuperExpr) {
            return "SUPER";
        }
        if (target.isStatic()) return "STATIC";
        try {
            ResolvedReferenceTypeDeclaration t = target.declaringType().asReferenceType();
            if (t.isInterface()) return "INTERFACE";
        } catch (RuntimeException ignore) { }
        return "INSTANCE";
    }

    private void emit(com.github.javaparser.ast.Node callNode,
                      ResolvedMethodLikeDeclaration target, String callKind,
                      ExtractionResult result) {
        String enclosingId = enclosingMethodId(callNode);
        if (enclosingId == null) return;

        Edge e = new Edge();
        e.sourceId = enclosingId;
        e.relation = "CALLS";
        e.callKind = callKind;
        e.confidence = "EXTRACTED";
        e.sourceLocation = "L" + callNode.getBegin().map(p -> p.line).orElse(0);
        e.context = ControlContext.of(callNode);

        ResolvedTypeDeclaration decl;
        try { decl = target.declaringType(); }
        catch (RuntimeException ex) { ctx.incrementUnresolved(ex); return; }

        if (ctx.isProjectInternal(decl)) {
            if (target instanceof ResolvedMethodDeclaration m) {
                e.targetId = ctx.idGenerator().forMethod(m);
            } else if (target instanceof ResolvedConstructorDeclaration c) {
                e.targetId = ctx.idGenerator().forConstructor(c);
            } else {
                return;
            }
            e.isExternal = false;
        } else {
            e.externalTargetFqn = NodeIdGenerator.externalMethodFqn(target);
            e.isExternal = true;
        }
        result.edges.add(e);
    }

    private void emitFallback(MethodCallExpr call, ExtractionResult result) {
        String enclosingId = enclosingMethodId(call);
        if (enclosingId == null) return;

        if (emitScopeTypeFallback(call, enclosingId, result)) return;
        emitStaticNameFallback(call, enclosingId, result);
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
        ResolvedMethodDeclaration target = solved.isSolved()
                ? solved.getCorrespondingDeclaration()
                : uniqueMethodByNameAndArity(declOpt.get(), call);
        if (target == null) return emitTypedScopeExternalFallback(call, enclosingId, scopeType, result);

        emit(call, target, classify(target, call), result);
        return true;
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
        e.callKind = "INSTANCE";
        e.confidence = "INFERRED";
        e.externalTargetFqn = typeFqn + "#" + call.getNameAsString()
                + "(" + fallbackParameterList(call) + ")";
        e.isExternal = true;
        result.edges.add(e);
        return true;
    }

    private ResolvedMethodDeclaration uniqueMethodByNameAndArity(
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
            return null;
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
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
        e.callKind = "STATIC";
        e.confidence = "INFERRED";
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
        e.relation = "CALLS";
        e.confidence = "EXTRACTED";
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
        try { return NodeIdGenerator.erasedTypeDescribe(arg.calculateResolvedType()); }
        catch (RuntimeException e) { return "<unresolved>"; }
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
            return cu.getPackageDeclaration()
                    .map(pkg -> pkg.getNameAsString() + "." + scope)
                    .orElse(scope);
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
