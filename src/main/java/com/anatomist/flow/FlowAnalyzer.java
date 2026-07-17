package com.anatomist.flow;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.core.NodeKeyFactory;
import com.anatomist.core.SourceIdentity;
import com.anatomist.core.SourceIdentityResolver;
import com.anatomist.json.Json;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight reaching-definitions/CFG extractor.
 *
 * <p>It is deliberately conservative: branches merge definition sets, loops
 * join entry/body states once, and unknown heap aliases are represented as
 * possible flows rather than discarded.</p>
 */
public final class FlowAnalyzer {

    private static final int MAX_METHOD_NODES = 20_000;
    private static final int MAX_SUMMARY_VISITS = 50_000;

    private final Path projectRoot;
    private final SourceIdentityResolver identities;
    private final NodeIdGenerator ids = new NodeIdGenerator();
    private final TaintRules taintRules;
    private final boolean implicitTaint;

    public FlowAnalyzer(Path projectRoot,
                        List<Path> sourcePaths,
                        List<com.anatomist.core.SourceRoot> sourceRoots,
                        TaintRules taintRules,
                        boolean implicitTaint) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.identities = sourceRoots == null || sourceRoots.isEmpty()
                ? new SourceIdentityResolver(projectRoot, sourcePaths)
                : SourceIdentityResolver.fromRoots(projectRoot, sourceRoots);
        this.taintRules = taintRules;
        this.implicitTaint = implicitTaint;
    }

    public void analyze(CompilationUnit unit, FlowResult output) {
        String sourceFile = sourceFile(unit);
        SourceIdentity identity = identities.resolve(sourceFile);
        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class,
                declaration -> declaration.findAncestor(MethodDeclaration.class).isEmpty()
                        && declaration.findAncestor(ConstructorDeclaration.class).isEmpty())) {
            analyzeCallable(method, method.getParameters(),
                    method.getBody().orElse(null), sourceFile, identity, output);
        }
        for (ConstructorDeclaration constructor : unit.findAll(ConstructorDeclaration.class,
                declaration -> declaration.findAncestor(MethodDeclaration.class).isEmpty()
                        && declaration.findAncestor(ConstructorDeclaration.class).isEmpty())) {
            analyzeCallable(constructor, constructor.getParameters(), constructor.getBody(),
                    sourceFile, identity, output);
        }
    }

    private void analyzeCallable(CallableDeclaration<?> callable,
                                 List<Parameter> parameters,
                                 BlockStmt body,
                                 String sourceFile,
                                 SourceIdentity identity,
                                 FlowResult output) {
        if (body == null) return;
        String methodId = methodId(callable, identity);
        MethodContext context = new MethodContext(methodId, sourceFile, identity, output);
        State state = new State();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            FlowNode node = context.node("PARAMETER", parameter.getNameAsString(), parameter,
                    Map.of("slot", "arg:" + i, "type", parameter.getTypeAsString()), state);
            state.define(parameter.getNameAsString(), node.id());
            context.parameters.put("arg:" + i, node.id());
        }
        analyzeBlock(body, state, context);
        buildSummaries(context);
    }

    private void analyzeBlock(BlockStmt block, State state, MethodContext context) {
        for (Statement statement : block.getStatements()) {
            if (context.limited()) return;
            analyzeStatement(statement, state, context);
        }
    }

    private void analyzeStatement(Statement statement, State state, MethodContext context) {
        if (statement instanceof BlockStmt block) {
            analyzeBlock(block, state, context);
        } else if (statement instanceof ExpressionStmt expression) {
            processExpression(expression.getExpression(), state, context);
        } else if (statement instanceof ReturnStmt returned) {
            FlowNode node = context.node("RETURN", "return", returned, Map.of(), state);
            returned.getExpression().ifPresent(value ->
                    connectExpression(value, node.id(), "RETURN_FLOW", state, context, null));
            context.returns.add(node.id());
        } else if (statement instanceof ThrowStmt thrown) {
            String type = exceptionType(thrown.getExpression());
            FlowNode node = context.node("THROW", type, thrown,
                    Map.of("exception_type", type), state);
            connectExpression(thrown.getExpression(), node.id(), "EXCEPTION_FLOW",
                    state, context, null);
            context.throwsNodes.add(node.id());
        } else if (statement instanceof IfStmt conditional) {
            FlowNode condition = context.node("CONDITION",
                    conditional.getCondition().toString(), conditional.getCondition(),
                    Map.of("condition", conditional.getCondition().toString()), state);
            connectExpression(conditional.getCondition(), condition.id(), "CONDITION_FLOW",
                    state, context, null);
            State thenState = state.copy();
            context.guards.push(new Guard(condition.id(), true));
            analyzeStatement(conditional.getThenStmt(), thenState, context);
            context.guards.pop();
            State elseState = state.copy();
            if (conditional.getElseStmt().isPresent()) {
                context.guards.push(new Guard(condition.id(), false));
                analyzeStatement(conditional.getElseStmt().get(), elseState, context);
                context.guards.pop();
            }
            state.merge(thenState, elseState);
        } else if (statement instanceof WhileStmt loop) {
            analyzeLoop(loop.getCondition(), loop.getBody(), state, context);
        } else if (statement instanceof DoStmt loop) {
            State body = state.copy();
            analyzeStatement(loop.getBody(), body, context);
            state.merge(state.copy(), body);
            FlowNode condition = context.node("CONDITION", loop.getCondition().toString(),
                    loop.getCondition(), Map.of("loop", "do"), state);
            connectExpression(loop.getCondition(), condition.id(), "CONDITION_FLOW",
                    state, context, null);
        } else if (statement instanceof ForStmt loop) {
            loop.getInitialization().forEach(expression ->
                    processExpression(expression, state, context));
            Expression comparison = loop.getCompare().orElse(null);
            State body = state.copy();
            if (comparison != null) {
                FlowNode condition = context.node("CONDITION", comparison.toString(), comparison,
                        Map.of("loop", "for"), body);
                connectExpression(comparison, condition.id(), "CONDITION_FLOW",
                        body, context, null);
                context.guards.push(new Guard(condition.id(), true));
            }
            analyzeStatement(loop.getBody(), body, context);
            loop.getUpdate().forEach(expression -> processExpression(expression, body, context));
            if (comparison != null) context.guards.pop();
            state.merge(state.copy(), body);
        } else if (statement instanceof ForEachStmt loop) {
            FlowNode iterable = context.node("EXPRESSION", loop.getIterable().toString(),
                    loop.getIterable(), Map.of("loop", "foreach"), state);
            connectExpression(loop.getIterable(), iterable.id(), "DEF_USE", state, context, null);
            State body = state.copy();
            VariableDeclarator variable = loop.getVariable().getVariable(0);
            FlowNode definition = context.node("LOCAL_DEF", variable.getNameAsString(),
                    variable, Map.of("loop_variable", true), body);
            context.edge(iterable.id(), definition.id(), "DEF_USE", "foreach", "INFERRED");
            body.define(variable.getNameAsString(), definition.id());
            analyzeStatement(loop.getBody(), body, context);
            state.merge(state.copy(), body);
        } else if (statement instanceof TryStmt tried) {
            analyzeTry(tried, state, context);
        } else if (statement instanceof SwitchStmt switched) {
            analyzeSwitch(switched, state, context);
        } else if (statement instanceof SynchronizedStmt synchronizedStmt) {
            connectExpression(synchronizedStmt.getExpression(),
                    context.node("EXPRESSION", "synchronized", synchronizedStmt.getExpression(),
                            Map.of(), state).id(),
                    "DEF_USE", state, context, null);
            analyzeBlock(synchronizedStmt.getBody(), state, context);
        } else if (statement instanceof BreakStmt || statement instanceof ContinueStmt) {
            context.node("CONTROL", statement.toString(), statement, Map.of(), state);
        } else {
            for (Expression expression : statement.findAll(Expression.class,
                    candidate -> candidate.findAncestor(Statement.class)
                            .map(statement::equals).orElse(false))) {
                processExpression(expression, state, context);
            }
        }
    }

    private void analyzeLoop(Expression conditionExpression,
                             Statement bodyStatement,
                             State state,
                             MethodContext context) {
        FlowNode condition = context.node("CONDITION", conditionExpression.toString(),
                conditionExpression, Map.of("loop", "while"), state);
        connectExpression(conditionExpression, condition.id(), "CONDITION_FLOW",
                state, context, null);
        State body = state.copy();
        context.guards.push(new Guard(condition.id(), true));
        analyzeStatement(bodyStatement, body, context);
        context.guards.pop();
        state.merge(state.copy(), body);
    }

    private void analyzeTry(TryStmt tried, State state, MethodContext context) {
        tried.getResources().forEach(resource -> processExpression(resource, state, context));
        int throwsBefore = context.throwsNodes.size();
        State tryState = state.copy();
        analyzeBlock(tried.getTryBlock(), tryState, context);
        List<String> localThrows = new ArrayList<>(
                context.throwsNodes.subList(throwsBefore, context.throwsNodes.size()));
        List<State> branches = new ArrayList<>();
        branches.add(tryState);
        for (CatchClause caught : tried.getCatchClauses()) {
            State catchState = state.copy();
            FlowNode parameter = context.node("CATCH_PARAMETER",
                    caught.getParameter().getNameAsString(), caught.getParameter(),
                    Map.of("exception_type", caught.getParameter().getTypeAsString()), catchState);
            catchState.define(caught.getParameter().getNameAsString(), parameter.id());
            for (String thrown : localThrows) {
                context.edge(thrown, parameter.id(), "EXCEPTION_FLOW", "catch", "POSSIBLE");
            }
            analyzeBlock(caught.getBody(), catchState, context);
            branches.add(catchState);
        }
        state.merge(branches.toArray(State[]::new));
        tried.getFinallyBlock().ifPresent(block -> analyzeBlock(block, state, context));
    }

    private void analyzeSwitch(SwitchStmt switched, State state, MethodContext context) {
        FlowNode selector = context.node("CONDITION", switched.getSelector().toString(),
                switched.getSelector(), Map.of("switch", true), state);
        connectExpression(switched.getSelector(), selector.id(), "CONDITION_FLOW",
                state, context, null);
        List<State> branches = new ArrayList<>();
        for (SwitchEntry entry : switched.getEntries()) {
            State branch = state.copy();
            context.guards.push(new Guard(selector.id(), true));
            for (Statement statement : entry.getStatements()) {
                analyzeStatement(statement, branch, context);
            }
            context.guards.pop();
            branches.add(branch);
        }
        if (branches.isEmpty()) branches.add(state.copy());
        state.merge(branches.toArray(State[]::new));
    }

    private String processExpression(Expression expression, State state, MethodContext context) {
        if (expression instanceof VariableDeclarationExpr declaration) {
            String last = null;
            for (VariableDeclarator variable : declaration.getVariables()) {
                FlowNode definition = context.node("LOCAL_DEF", variable.getNameAsString(),
                        variable, Map.of("type", variable.getTypeAsString()), state);
                variable.getInitializer().ifPresent(initializer ->
                        connectExpression(initializer, definition.id(), "DEF_USE",
                                state, context, null));
                state.define(variable.getNameAsString(), definition.id());
                last = definition.id();
            }
            return last;
        }
        if (expression instanceof AssignExpr assignment) {
            String key = variableKey(assignment.getTarget());
            FlowNode definition = context.node(
                    key.startsWith("field:") ? "FIELD_DEF" : "LOCAL_DEF",
                    key, assignment, Map.of("operator", assignment.getOperator().asString()), state);
            connectExpression(assignment.getValue(), definition.id(), "DEF_USE",
                    state, context, null);
            if (assignment.getOperator() != AssignExpr.Operator.ASSIGN) {
                for (String prior : state.definitions(key)) {
                    context.edge(prior, definition.id(), "DEF_USE", "compound-assignment",
                            "EXTRACTED");
                }
            }
            state.define(key, definition.id());
            return definition.id();
        }
        if (expression instanceof UnaryExpr unary
                && (unary.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT
                || unary.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                || unary.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
                || unary.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT)) {
            String key = variableKey(unary.getExpression());
            FlowNode definition = context.node("LOCAL_DEF", key, unary,
                    Map.of("operator", unary.getOperator().asString()), state);
            for (String prior : state.definitions(key)) {
                context.edge(prior, definition.id(), "DEF_USE", "unary-update", "EXTRACTED");
            }
            state.define(key, definition.id());
            return definition.id();
        }
        if (expression instanceof MethodCallExpr call) return callNode(call, state, context);
        if (expression instanceof ObjectCreationExpr creation) {
            return constructorCallNode(creation, state, context);
        }
        String last = null;
        for (MethodCallExpr call : expression.findAll(MethodCallExpr.class)) {
            last = callNode(call, state, context);
        }
        for (ObjectCreationExpr creation : expression.findAll(ObjectCreationExpr.class)) {
            last = constructorCallNode(creation, state, context);
        }
        return last;
    }

    private void connectExpression(Expression expression,
                                   String target,
                                   String relation,
                                   State state,
                                   MethodContext context,
                                   String suppliedContext) {
        Set<String> sources = expressionSources(expression, state, context);
        for (String source : sources) {
            context.edge(source, target, relation, suppliedContext, "EXTRACTED");
        }
    }

    private Set<String> expressionSources(Expression expression,
                                          State state,
                                          MethodContext context) {
        Set<String> out = new LinkedHashSet<>();
        if (expression instanceof MethodCallExpr call) {
            out.add(callNode(call, state, context));
            return out;
        }
        if (expression instanceof ObjectCreationExpr creation) {
            out.add(constructorCallNode(creation, state, context));
            return out;
        }
        for (NameExpr name : expression.findAll(NameExpr.class)) {
            if (name.findAncestor(VariableDeclarator.class)
                    .filter(variable -> variable.getName().equals(name)).isPresent()) continue;
            out.addAll(state.definitions(name.getNameAsString()));
        }
        for (FieldAccessExpr field : expression.findAll(FieldAccessExpr.class)) {
            out.addAll(state.definitions("field:" + field.getNameAsString()));
        }
        for (ArrayAccessExpr array : expression.findAll(ArrayAccessExpr.class)) {
            if (array.getName() instanceof NameExpr name) {
                out.addAll(state.definitions(name.getNameAsString()));
            }
        }
        for (MethodCallExpr call : expression.findAll(MethodCallExpr.class)) {
            out.add(callNode(call, state, context));
        }
        for (ObjectCreationExpr creation : expression.findAll(ObjectCreationExpr.class)) {
            out.add(constructorCallNode(creation, state, context));
        }
        return out;
    }

    private String callNode(MethodCallExpr call, State state, MethodContext context) {
        String existing = context.expressionNodes.get(call);
        if (existing != null) return existing;
        ResolvedCall resolved = resolve(call, context);
        TaintRules.Match taintMatch = taintRules.classify(resolved.matchName());
        TaintRules.Rule sourceRule = taintMatch.source();
        TaintRules.Rule sinkRule = taintMatch.sink();
        TaintRules.Rule sanitizerRule = taintMatch.sanitizer();
        String kind = sourceRule != null ? "TAINT_SOURCE"
                : sinkRule != null ? "TAINT_SINK"
                : sanitizerRule != null ? "SANITIZER" : "CALL_RESULT";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("callee_method", resolved.methodId());
        metadata.put("callee", resolved.matchName());
        if (sourceRule != null) metadata.put("taint_source_slot", sourceRule.slot());
        if (sinkRule != null) metadata.put("taint_sink_slot", sinkRule.slot());
        if (sanitizerRule != null) metadata.put("sanitizer_slot", sanitizerRule.slot());
        FlowNode node = context.node(kind, call.getNameAsString(), call, metadata, state);
        context.expressionNodes.put(call, node.id());
        call.getScope().ifPresent(scope -> connectExpression(scope, node.id(),
                "ARGUMENT_FLOW", state, context, "this"));
        for (int i = 0; i < call.getArguments().size(); i++) {
            connectExpression(call.getArgument(i), node.id(), "ARGUMENT_FLOW",
                    state, context, "arg:" + i);
        }
        for (String exception : resolved.exceptions()) {
            FlowNode exceptionNode = context.node("EXCEPTION", exception, call,
                    Map.of("exception_type", exception, "callee_method", resolved.methodId()), state);
            context.edge(node.id(), exceptionNode.id(), "EXCEPTION_FLOW",
                    "declared-throws", "INFERRED");
            context.throwsNodes.add(exceptionNode.id());
        }
        return node.id();
    }

    private String constructorCallNode(ObjectCreationExpr creation,
                                       State state,
                                       MethodContext context) {
        String existing = context.expressionNodes.get(creation);
        if (existing != null) return existing;
        String callee = creation.getTypeAsString() + "#<init>";
        String methodId = callee;
        try {
            ResolvedConstructorDeclaration resolved = creation.resolve();
            String symbol = ids.forConstructor(resolved);
            methodId = storageMethodId(symbol, resolved.toAst().flatMap(Node::findCompilationUnit)
                    .orElse(null), context.identity);
            callee = symbol;
        } catch (RuntimeException e) {
            context.partialResolution(e);
        }
        FlowNode node = context.node("CALL_RESULT", creation.getTypeAsString(),
                creation, Map.of("callee_method", methodId, "callee", callee), state);
        context.expressionNodes.put(creation, node.id());
        for (int i = 0; i < creation.getArguments().size(); i++) {
            connectExpression(creation.getArgument(i), node.id(), "ARGUMENT_FLOW",
                    state, context, "arg:" + i);
        }
        return node.id();
    }

    private ResolvedCall resolve(MethodCallExpr call, MethodContext context) {
        try {
            ResolvedMethodDeclaration resolved = call.resolve();
            String symbol = ids.forMethod(resolved);
            CompilationUnit targetUnit = resolved.toAst(MethodDeclaration.class)
                    .flatMap(Node::findCompilationUnit).orElse(null);
            String storage = storageMethodId(symbol, targetUnit, context.identity);
            List<String> exceptions = new ArrayList<>();
            for (int i = 0; i < resolved.getNumberOfSpecifiedExceptions(); i++) {
                try {
                    exceptions.add(resolved.getSpecifiedException(i).describe());
                } catch (RuntimeException ignored) {
                    // Keep other declared exceptions.
                }
            }
            return new ResolvedCall(storage, symbol, exceptions);
        } catch (RuntimeException e) {
            context.partialResolution(e);
            String lexical = call.getScope().map(scope -> scope + ".").orElse("")
                    + call.getNameAsString();
            return new ResolvedCall(lexical, lexical, List.of());
        }
    }

    private String storageMethodId(String symbol,
                                   CompilationUnit targetUnit,
                                   SourceIdentity fallback) {
        SourceIdentity identity = fallback;
        if (targetUnit != null) identity = identities.resolve(sourceFile(targetUnit));
        return NodeKeyFactory.key(identity, symbol);
    }

    private void buildSummaries(MethodContext context) {
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        for (FlowEdge edge : context.output.edges) {
            if (!context.methodId.equals(edge.methodId())) continue;
            outgoing.computeIfAbsent(edge.sourceNode(), ignored -> new LinkedHashSet<>())
                    .add(edge.targetNode());
        }
        for (Map.Entry<String, String> parameter : context.parameters.entrySet()) {
            Set<String> reachable = reachable(parameter.getValue(), outgoing, context);
            if (reachable.stream().anyMatch(context.returns::contains)) {
                context.output.summaries.add(new MethodFlowSummary(
                        context.methodId, parameter.getKey(), "return", "DATA_FLOW",
                        context.sourceFile, "INFERRED", null));
            }
            for (String thrown : context.throwsNodes) {
                if (reachable.contains(thrown)) {
                    FlowNode node = context.nodesById.get(thrown);
                    context.output.summaries.add(new MethodFlowSummary(
                            context.methodId, parameter.getKey(),
                            "throw:" + (node == null ? "unknown" : node.label()),
                            "EXCEPTION_FLOW", context.sourceFile, "POSSIBLE", null));
                }
            }
        }
        for (String thrown : context.throwsNodes) {
            FlowNode node = context.nodesById.get(thrown);
            context.output.summaries.add(new MethodFlowSummary(
                    context.methodId, "method", "throw:"
                            + (node == null ? "unknown" : node.label()),
                    "EXCEPTION_FLOW", context.sourceFile, "INFERRED", null));
        }
    }

    private Set<String> reachable(String start,
                                  Map<String, Set<String>> outgoing,
                                  MethodContext context) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < MAX_SUMMARY_VISITS) {
            String current = queue.removeFirst();
            if (!seen.add(current)) continue;
            queue.addAll(outgoing.getOrDefault(current, Set.of()));
        }
        if (!queue.isEmpty()) context.limitDiagnostic("summary reachability");
        return seen;
    }

    private String methodId(CallableDeclaration<?> callable, SourceIdentity identity) {
        String symbol;
        try {
            if (callable instanceof MethodDeclaration method) {
                symbol = ids.forMethod(method.resolve());
            } else if (callable instanceof ConstructorDeclaration constructor) {
                symbol = ids.forConstructor(constructor.resolve());
            } else {
                symbol = lexicalMethodId(callable);
            }
        } catch (RuntimeException e) {
            symbol = lexicalMethodId(callable);
        }
        return NodeKeyFactory.key(identity, symbol);
    }

    private static String lexicalMethodId(CallableDeclaration<?> callable) {
        String owner = "<unknown>";
        Node parent = callable.getParentNode().orElse(null);
        while (parent != null) {
            if (parent instanceof com.github.javaparser.ast.body.TypeDeclaration<?> type) {
                owner = type.getFullyQualifiedName().orElse(type.getNameAsString());
                break;
            }
            parent = parent.getParentNode().orElse(null);
        }
        String parameters = callable.getParameters().stream()
                .map(parameter -> removeAsciiRegexWhitespace(parameter.getTypeAsString()))
                .collect(java.util.stream.Collectors.joining(","));
        return owner + "#" + callable.getNameAsString() + "(" + parameters + ")";
    }

    private static String removeAsciiRegexWhitespace(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character != ' ' && character != '\t' && character != '\n'
                    && character != '\u000B' && character != '\f' && character != '\r') {
                out.append(character);
            }
        }
        return out.length() == value.length() ? value : out.toString();
    }

    private String sourceFile(CompilationUnit unit) {
        Path file = unit.getStorage().map(storage -> storage.getPath().toAbsolutePath().normalize())
                .orElse(null);
        if (file == null) return "<unknown>";
        try {
            return projectRoot.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static String variableKey(Expression expression) {
        if (expression instanceof NameExpr name) return name.getNameAsString();
        if (expression instanceof FieldAccessExpr field) return "field:" + field.getNameAsString();
        if (expression instanceof ArrayAccessExpr array) return variableKey(array.getName());
        return expression.toString();
    }

    private static String exceptionType(Expression expression) {
        try {
            return expression.calculateResolvedType().describe();
        } catch (RuntimeException e) {
            return expression.toString();
        }
    }

    private final class MethodContext {
        final String methodId;
        final String sourceFile;
        final SourceIdentity identity;
        final FlowResult output;
        final Map<String, String> parameters = new LinkedHashMap<>();
        final List<String> returns = new ArrayList<>();
        final List<String> throwsNodes = new ArrayList<>();
        final Deque<Guard> guards = new ArrayDeque<>();
        final IdentityHashMap<Expression, String> expressionNodes = new IdentityHashMap<>();
        final Map<String, FlowNode> nodesById = new LinkedHashMap<>();
        final Set<String> diagnosticKeys = new HashSet<>();
        int sequence;

        MethodContext(String methodId, String sourceFile,
                      SourceIdentity identity, FlowResult output) {
            this.methodId = methodId;
            this.sourceFile = sourceFile;
            this.identity = identity;
            this.output = output;
        }

        FlowNode node(String kind, String label, Node source,
                      Map<String, ?> metadata, State state) {
            int line = source.getBegin().map(position -> position.line).orElse(0);
            int column = source.getBegin().map(position -> position.column).orElse(0);
            String id = methodId + "@flow:" + line + ":" + column + ":"
                    + kind + ":" + sequence++;
            String json = metadata == null || metadata.isEmpty()
                    ? null : Json.writeCompact(metadata);
            FlowNode node = new FlowNode(id, methodId, kind, label, sourceFile,
                    identity.module(), identity.scope().name(), line, column, json);
            output.nodes.add(node);
            nodesById.put(id, node);
            for (Guard guard : guards) {
                edge(guard.node(), id, guard.positive() ? "GUARD_TRUE" : "GUARD_FALSE",
                        implicitTaint ? "implicit-taint" : "control-only", "INFERRED");
                if (implicitTaint) {
                    edge(guard.node(), id, "TAINT_FLOW", "implicit", "POSSIBLE");
                }
            }
            for (String previous : state.control) {
                edge(previous, id, "CONTROL_FLOW", null, "INFERRED");
            }
            state.control.clear();
            state.control.add(id);
            if (limited()) limitDiagnostic("method node cap");
            return node;
        }

        void edge(String source, String target, String relation,
                  String edgeContext, String confidence) {
            if (source == null || target == null || source.equals(target)) return;
            output.edges.add(new FlowEdge(source, target, relation, methodId,
                    sourceFile, confidence, edgeContext, null));
        }

        boolean limited() {
            return sequence >= MAX_METHOD_NODES;
        }

        void partialResolution(RuntimeException error) {
            String key = "resolution";
            if (!diagnosticKeys.add(key)) return;
            output.diagnostics.add(new IndexDiagnostic(
                    "info", "FLOW_RESOLUTION_PARTIAL", "FLOW",
                    sourceFile, identity.module(), identity.scope().name(), methodId, 1,
                    error.getMessage()));
        }

        void limitDiagnostic(String reason) {
            if (!diagnosticKeys.add("limit:" + reason)) return;
            output.diagnostics.add(new IndexDiagnostic(
                    "warning", "FLOW_ANALYSIS_LIMIT", "FLOW",
                    sourceFile, identity.module(), identity.scope().name(), methodId, 1, reason));
        }
    }

    private static final class State {
        final Map<String, LinkedHashSet<String>> definitions = new LinkedHashMap<>();
        final LinkedHashSet<String> control = new LinkedHashSet<>();

        Set<String> definitions(String name) {
            return definitions.getOrDefault(name, new LinkedHashSet<>());
        }

        void define(String name, String node) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            values.add(node);
            definitions.put(name, values);
        }

        State copy() {
            State copy = new State();
            definitions.forEach((name, values) ->
                    copy.definitions.put(name, new LinkedHashSet<>(values)));
            copy.control.addAll(control);
            return copy;
        }

        void merge(State... states) {
            definitions.clear();
            control.clear();
            for (State state : states) {
                state.definitions.forEach((name, values) ->
                        definitions.computeIfAbsent(name, ignored -> new LinkedHashSet<>())
                                .addAll(values));
                control.addAll(state.control);
            }
        }
    }

    private record Guard(String node, boolean positive) {}
    private record ResolvedCall(String methodId, String matchName, List<String> exceptions) {}
}
