package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.AssertStmt;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.stmt.YieldStmt;
import com.github.javaparser.resolution.MethodUsage;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Extracts bounded, exact Java core-reflection targets.
 *
 * <p>The extractor deliberately recognizes only SymbolSolver-confirmed JDK
 * APIs. Unknown values remain unknown: it never guesses a class or member from
 * a dynamic expression.</p>
 */
public final class ReflectionExtractor implements Extractor {

    private static final int MAX_CONSTANT_LENGTH = 4_096;
    private static final String JAVA_CLASS = "java.lang.Class";
    private static final String JAVA_METHOD = "java.lang.reflect.Method";
    private static final String JAVA_CONSTRUCTOR = "java.lang.reflect.Constructor";
    private static final Set<String> REFLECTION_METHOD_NAMES = Set.of(
            "forName", "getMethod", "getDeclaredMethod",
            "getConstructor", "getDeclaredConstructor", "invoke", "newInstance");

    private static final Map<String, String> PRIMITIVE_TYPES = Map.ofEntries(
            Map.entry("java.lang.Boolean", "boolean"),
            Map.entry("java.lang.Byte", "byte"),
            Map.entry("java.lang.Character", "char"),
            Map.entry("java.lang.Double", "double"),
            Map.entry("java.lang.Float", "float"),
            Map.entry("java.lang.Integer", "int"),
            Map.entry("java.lang.Long", "long"),
            Map.entry("java.lang.Short", "short"),
            Map.entry("java.lang.Void", "void"));

    private final ExtractionContext ctx;
    private final AstEnclosing enclosing;
    private final Map<String, ResolvedReferenceTypeDeclaration> unitTypes = new LinkedHashMap<>();

    public ReflectionExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
        this.enclosing = new AstEnclosing(ctx.idGenerator());
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        indexUnitTypes(unit);

        for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
            method.getBody().ifPresent(body -> analyze(body, new State(), result));
        }
        for (ConstructorDeclaration constructor : unit.findAll(ConstructorDeclaration.class)) {
            analyze(constructor.getBody(), new State(), result);
        }
        for (LambdaExpr lambda : unit.findAll(LambdaExpr.class)) {
            State state = new State();
            lambda.getParameters().forEach(parameter -> state.invalidate(parameter.getNameAsString()));
            if (lambda.getBody().isExpressionStmt()) {
                eval(lambda.getExpressionBody().orElseThrow(), state, result);
            } else {
                analyze(lambda.getBody(), state, result);
            }
        }
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            State state = new State();
            for (VariableDeclarator variable : field.getVariables()) {
                variable.getInitializer().ifPresent(initializer -> eval(initializer, state, result));
            }
        }
        unitTypes.clear();
    }

    private void indexUnitTypes(CompilationUnit unit) {
        unitTypes.clear();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            try {
                ResolvedTypeDeclaration declaration = type.resolve();
                if (!(declaration instanceof ResolvedReferenceTypeDeclaration reference)) continue;
                unitTypes.put(reference.getQualifiedName(), reference);
                unitTypes.put(binaryName(type, unit), reference);
            } catch (RuntimeException ignored) {
                // A string target can still be emitted and late-bound by the staged graph.
            }
        }
    }

    private static String binaryName(TypeDeclaration<?> type, CompilationUnit unit) {
        List<String> names = new ArrayList<>();
        Node cursor = type;
        while (cursor instanceof TypeDeclaration<?> declaration) {
            names.add(0, declaration.getNameAsString());
            cursor = declaration.getParentNode().orElse(null);
        }
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        return pkg + String.join("$", names);
    }

    private void analyze(Statement statement, State state, ExtractionResult result) {
        if (statement == null) return;
        if (statement instanceof BlockStmt block) {
            for (Statement child : block.getStatements()) analyze(child, state, result);
            return;
        }
        if (statement instanceof ExpressionStmt expression) {
            eval(expression.getExpression(), state, result);
            return;
        }
        if (statement instanceof ReturnStmt returned) {
            returned.getExpression().ifPresent(expression -> eval(expression, state, result));
            return;
        }
        if (statement instanceof ThrowStmt thrown) {
            eval(thrown.getExpression(), state, result);
            return;
        }
        if (statement instanceof YieldStmt yielded) {
            eval(yielded.getExpression(), state, result);
            return;
        }
        if (statement instanceof AssertStmt asserted) {
            eval(asserted.getCheck(), state, result);
            asserted.getMessage().ifPresent(message -> eval(message, state, result));
            return;
        }
        if (statement instanceof ExplicitConstructorInvocationStmt invocation) {
            invocation.getExpression().ifPresent(expression -> eval(expression, state, result));
            invocation.getArguments().forEach(argument -> eval(argument, state, result));
            return;
        }
        if (statement instanceof IfStmt conditional) {
            eval(conditional.getCondition(), state, result);
            State thenState = state.copy();
            State elseState = state.copy();
            analyze(conditional.getThenStmt(), thenState, result);
            conditional.getElseStmt().ifPresent(branch -> analyze(branch, elseState, result));
            state.retainEqual(thenState, elseState);
            return;
        }
        if (statement instanceof ForStmt loop) {
            loop.getInitialization().forEach(expression -> eval(expression, state, result));
            loop.getCompare().ifPresent(expression -> eval(expression, state, result));
            State body = state.copy();
            analyze(loop.getBody(), body, result);
            loop.getUpdate().forEach(expression -> eval(expression, body, result));
            invalidateWrites(state, loop.getBody(), loop.getUpdate());
            return;
        }
        if (statement instanceof ForEachStmt loop) {
            eval(loop.getIterable(), state, result);
            State body = state.copy();
            loop.getVariable().getVariables()
                    .forEach(variable -> body.invalidate(variable.getNameAsString()));
            analyze(loop.getBody(), body, result);
            invalidateWrites(state, loop.getBody(), List.of());
            loop.getVariable().getVariables()
                    .forEach(variable -> state.invalidate(variable.getNameAsString()));
            return;
        }
        if (statement instanceof WhileStmt loop) {
            eval(loop.getCondition(), state, result);
            analyze(loop.getBody(), state.copy(), result);
            invalidateWrites(state, loop.getBody(), List.of());
            return;
        }
        if (statement instanceof DoStmt loop) {
            State body = state.copy();
            analyze(loop.getBody(), body, result);
            eval(loop.getCondition(), body, result);
            invalidateWrites(state, loop.getBody(), List.of());
            return;
        }
        if (statement instanceof SwitchStmt switched) {
            eval(switched.getSelector(), state, result);
            mergeSwitchEntries(switched.getEntries(), state, result);
            return;
        }
        if (statement instanceof TryStmt tried) {
            tried.getResources().forEach(resource -> eval(resource, state, result));
            List<State> outcomes = new ArrayList<>();
            State body = state.copy();
            analyze(tried.getTryBlock(), body, result);
            outcomes.add(body);
            tried.getCatchClauses().forEach(caught -> {
                State branch = state.copy();
                branch.invalidate(caught.getParameter().getNameAsString());
                analyze(caught.getBody(), branch, result);
                outcomes.add(branch);
            });
            state.retainEqual(outcomes);
            tried.getFinallyBlock().ifPresent(block -> analyze(block, state, result));
            return;
        }
        if (statement instanceof SynchronizedStmt sync) {
            eval(sync.getExpression(), state, result);
            analyze(sync.getBody(), state, result);
            return;
        }
        if (statement instanceof LabeledStmt labeled) {
            analyze(labeled.getStatement(), state, result);
        }
    }

    private void mergeSwitchEntries(List<SwitchEntry> entries, State state, ExtractionResult result) {
        if (entries.isEmpty()) return;
        List<State> outcomes = new ArrayList<>();
        boolean hasDefault = false;
        for (SwitchEntry entry : entries) {
            if (entry.getLabels().isEmpty()) hasDefault = true;
            State branch = state.copy();
            entry.getLabels().forEach(label -> eval(label, branch, result));
            entry.getStatements().forEach(statement -> analyze(statement, branch, result));
            outcomes.add(branch);
        }
        if (!hasDefault) outcomes.add(state.copy());
        state.retainEqual(outcomes);
    }

    private static void invalidateWrites(State state, Node body, List<Expression> updates) {
        Set<String> writes = new HashSet<>();
        for (AssignExpr assignment : body.findAll(AssignExpr.class)) {
            if (assignment.getTarget() instanceof NameExpr name) writes.add(name.getNameAsString());
        }
        for (UnaryExpr unary : body.findAll(UnaryExpr.class)) {
            if (isUpdate(unary) && unary.getExpression() instanceof NameExpr name) {
                writes.add(name.getNameAsString());
            }
        }
        for (Expression update : updates) {
            for (AssignExpr assignment : update.findAll(AssignExpr.class)) {
                if (assignment.getTarget() instanceof NameExpr name) writes.add(name.getNameAsString());
            }
            for (UnaryExpr unary : update.findAll(UnaryExpr.class)) {
                if (isUpdate(unary) && unary.getExpression() instanceof NameExpr name) {
                    writes.add(name.getNameAsString());
                }
            }
        }
        writes.forEach(state::invalidate);
    }

    private Value eval(Expression expression, State state, ExtractionResult result) {
        if (expression == null) return UnknownValue.INSTANCE;
        if (expression.isStringLiteralExpr()) {
            return stringValue(expression.asStringLiteralExpr().asString(), "STRING_LITERAL");
        }
        if (expression instanceof TextBlockLiteralExpr text) {
            return stringValue(text.getValue(), "STRING_LITERAL");
        }
        if (expression instanceof NameExpr name) return state.get(name.getNameAsString());
        if (expression instanceof EnclosedExpr enclosed) {
            return eval(enclosed.getInner(), state, result);
        }
        if (expression instanceof CastExpr cast) return eval(cast.getExpression(), state, result);
        if (expression instanceof ClassExpr classExpr) return classValue(classExpr);
        if (expression instanceof FieldAccessExpr field) {
            eval(field.getScope(), state, result);
            ClassValue primitive = primitiveClassValue(field);
            return primitive == null ? UnknownValue.INSTANCE : primitive;
        }
        if (expression instanceof VariableDeclarationExpr declaration) {
            Value last = UnknownValue.INSTANCE;
            for (VariableDeclarator variable : declaration.getVariables()) {
                last = variable.getInitializer()
                        .map(initializer -> eval(initializer, state, result))
                        .orElse(UnknownValue.INSTANCE);
                state.put(variable.getNameAsString(), local(last));
            }
            return last;
        }
        if (expression instanceof AssignExpr assignment) {
            Value value = eval(assignment.getValue(), state, result);
            if (assignment.getTarget() instanceof NameExpr name
                    && assignment.getOperator() == AssignExpr.Operator.ASSIGN) {
                state.put(name.getNameAsString(), local(value));
            } else if (assignment.getTarget() instanceof NameExpr name) {
                state.invalidate(name.getNameAsString());
            } else {
                eval(assignment.getTarget(), state, result);
            }
            return value;
        }
        if (expression instanceof BinaryExpr binary) {
            Value left = eval(binary.getLeft(), state, result);
            Value right = eval(binary.getRight(), state, result);
            if (binary.getOperator() == BinaryExpr.Operator.PLUS
                    && left instanceof StringValue a && right instanceof StringValue b) {
                return stringValue(a.value() + b.value(), "STRING_EXPRESSION");
            }
            return UnknownValue.INSTANCE;
        }
        if (expression instanceof ConditionalExpr conditional) {
            eval(conditional.getCondition(), state, result);
            State thenState = state.copy();
            State elseState = state.copy();
            Value thenValue = eval(conditional.getThenExpr(), thenState, result);
            Value elseValue = eval(conditional.getElseExpr(), elseState, result);
            state.retainEqual(thenState, elseState);
            return sameValue(thenValue, elseValue) ? thenValue : UnknownValue.INSTANCE;
        }
        if (expression instanceof UnaryExpr unary) {
            Value value = eval(unary.getExpression(), state, result);
            if (isUpdate(unary) && unary.getExpression() instanceof NameExpr name) {
                state.invalidate(name.getNameAsString());
            }
            return value;
        }
        if (expression instanceof MethodCallExpr call) {
            Value scope = call.getScope()
                    .map(value -> eval(value, state, result))
                    .orElse(UnknownValue.INSTANCE);
            List<Value> arguments = call.getArguments().stream()
                    .map(argument -> eval(argument, state, result))
                    .toList();
            return evaluateReflectionCall(call, scope, arguments, result);
        }
        if (expression instanceof ArrayInitializerExpr array) {
            List<ClassValue> values = new ArrayList<>();
            for (Expression item : array.getValues()) {
                Value value = eval(item, state, result);
                if (!(value instanceof ClassValue target)) return UnknownValue.INSTANCE;
                values.add(target);
            }
            return new ClassArrayValue(List.copyOf(values), "INLINE_ARRAY");
        }
        if (expression instanceof ArrayCreationExpr array) {
            array.getLevels().forEach(level ->
                    level.getDimension().ifPresent(dimension -> eval(dimension, state, result)));
            if (array.getInitializer().isPresent()) {
                return eval(array.getInitializer().orElseThrow(), state, result);
            }
            return UnknownValue.INSTANCE;
        }
        if (expression instanceof ObjectCreationExpr creation) {
            creation.getArguments().forEach(argument -> eval(argument, state, result));
            return UnknownValue.INSTANCE;
        }
        if (expression instanceof ArrayAccessExpr access) {
            eval(access.getName(), state, result);
            eval(access.getIndex(), state, result);
            return UnknownValue.INSTANCE;
        }
        if (expression instanceof InstanceOfExpr check) {
            eval(check.getExpression(), state, result);
        }
        return UnknownValue.INSTANCE;
    }

    private Value evaluateReflectionCall(MethodCallExpr call, Value scope,
                                         List<Value> arguments, ExtractionResult result) {
        Api api = apiOf(call);
        if (api == null) return UnknownValue.INSTANCE;
        return switch (api.operation()) {
            case "CLASS_FOR_NAME" -> classForName(call, arguments, result);
            case "METHOD_LOOKUP" -> methodLookup(call, scope, arguments, api, result);
            case "CONSTRUCTOR_LOOKUP" -> constructorLookup(call, scope, arguments, api, result);
            case "METHOD_INVOKE" -> invokeMethod(call, scope, result);
            case "CONSTRUCTOR_NEW_INSTANCE" -> invokeConstructor(call, scope, result);
            default -> UnknownValue.INSTANCE;
        };
    }

    private Value classForName(MethodCallExpr call, List<Value> arguments,
                               ExtractionResult result) {
        if (arguments.isEmpty() || !(arguments.get(0) instanceof StringValue name)) {
            return UnknownValue.INSTANCE;
        }
        ResolvedReferenceTypeDeclaration declaration = unitTypes.get(name.value());
        String canonicalName = declaration == null ? name.value() : declaration.getQualifiedName();
        ClassValue value = new ClassValue(canonicalName, declaration, name.source());
        emitTarget(call, value, GraphConstants.Relation.REFERENCES,
                "CLASS_FOR_NAME", name.source(), result);
        return value;
    }

    private Value methodLookup(MethodCallExpr call, Value scope, List<Value> arguments,
                               Api api, ExtractionResult result) {
        if (!(scope instanceof ClassValue owner)
                || arguments.isEmpty()
                || !(arguments.get(0) instanceof StringValue methodName)) {
            return UnknownValue.INSTANCE;
        }
        List<ClassValue> parameters = parameterTypes(arguments.subList(1, arguments.size()));
        if (parameters == null) return UnknownValue.INSTANCE;
        boolean declared = "getDeclaredMethod".equals(api.methodName());
        ResolvedMethodDeclaration resolved = resolveMethod(
                owner.declaration(), methodName.value(), parameters, declared);
        if (owner.declaration() != null && resolved == null) return UnknownValue.INSTANCE;
        String target = resolved == null
                ? methodSymbol(owner.fqn(), methodName.value(), parameters)
                : CallableIdFactory.forMethod(ctx.idGenerator(), resolved);
        MethodValue value = new MethodValue(target, resolved, owner.fqn(),
                methodName.value(), parameters.stream().map(ClassValue::fqn).toList(),
                methodName.source(), declared);
        emitTarget(call, value, GraphConstants.Relation.REFERENCES,
                "METHOD_LOOKUP", methodName.source(), result);
        return value;
    }

    private Value constructorLookup(MethodCallExpr call, Value scope, List<Value> arguments,
                                    Api api, ExtractionResult result) {
        if (!(scope instanceof ClassValue owner)) return UnknownValue.INSTANCE;
        List<ClassValue> parameters = parameterTypes(arguments);
        if (parameters == null) return UnknownValue.INSTANCE;
        boolean declared = "getDeclaredConstructor".equals(api.methodName());
        ResolvedConstructorDeclaration resolved =
                resolveConstructor(owner.declaration(), parameters, declared);
        if (owner.declaration() != null && resolved == null) return UnknownValue.INSTANCE;
        String target = resolved == null
                ? constructorSymbol(owner.fqn(), parameters)
                : CallableIdFactory.forConstructor(ctx.idGenerator(), resolved);
        ConstructorValue value = new ConstructorValue(target, resolved, owner.fqn(),
                parameters.stream().map(ClassValue::fqn).toList(),
                owner.source(), declared);
        emitTarget(call, value, GraphConstants.Relation.REFERENCES,
                "CONSTRUCTOR_LOOKUP", owner.source(), result);
        return value;
    }

    private Value invokeMethod(MethodCallExpr call, Value scope, ExtractionResult result) {
        if (!(scope instanceof MethodValue method)) return UnknownValue.INSTANCE;
        emitTarget(call, method, GraphConstants.Relation.CALLS,
                "METHOD_INVOKE", method.source(), result);
        return UnknownValue.INSTANCE;
    }

    private Value invokeConstructor(MethodCallExpr call, Value scope, ExtractionResult result) {
        if (!(scope instanceof ConstructorValue constructor)) return UnknownValue.INSTANCE;
        emitTarget(call, constructor, GraphConstants.Relation.CALLS,
                "CONSTRUCTOR_NEW_INSTANCE", constructor.source(), result);
        return UnknownValue.INSTANCE;
    }

    private ResolvedMethodDeclaration resolveMethod(ResolvedReferenceTypeDeclaration owner,
                                                    String name,
                                                    List<ClassValue> parameters,
                                                    boolean declared) {
        if (owner == null) return null;
        try {
            List<ResolvedMethodDeclaration> matches = new ArrayList<>();
            if (declared) {
                for (ResolvedMethodDeclaration method : owner.getDeclaredMethods()) {
                    if (matches(method, name, parameters, false)) matches.add(method);
                }
            } else {
                for (MethodUsage usage : owner.getAllMethods()) {
                    ResolvedMethodDeclaration method = usage.getDeclaration();
                    if (method.accessSpecifier() != AccessSpecifier.PUBLIC) continue;
                    if (matches(usage, name, parameters)) matches.add(method);
                }
            }
            return unique(matches);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private ResolvedConstructorDeclaration resolveConstructor(
            ResolvedReferenceTypeDeclaration owner,
            List<ClassValue> parameters,
            boolean declared) {
        if (owner == null) return null;
        try {
            List<ResolvedConstructorDeclaration> matches = owner.getConstructors().stream()
                    .filter(constructor -> declared
                            || constructor.accessSpecifier() == AccessSpecifier.PUBLIC)
                    .filter(constructor -> matches(constructor, parameters))
                    .toList();
            return unique(matches);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean matches(ResolvedMethodDeclaration method, String name,
                                   List<ClassValue> parameters, boolean requirePublic) {
        if (!method.getName().equals(name)
                || method.getNumberOfParams() != parameters.size()) return false;
        if (requirePublic && method.accessSpecifier() != AccessSpecifier.PUBLIC) return false;
        for (int i = 0; i < parameters.size(); i++) {
            if (!sameType(method.getParam(i).getType(), parameters.get(i).fqn())) return false;
        }
        return true;
    }

    private static boolean matches(MethodUsage method, String name,
                                   List<ClassValue> parameters) {
        if (!method.getName().equals(name) || method.getNoParams() != parameters.size()) return false;
        for (int i = 0; i < parameters.size(); i++) {
            if (!sameType(method.getParamType(i), parameters.get(i).fqn())) return false;
        }
        return true;
    }

    private static boolean matches(ResolvedConstructorDeclaration constructor,
                                   List<ClassValue> parameters) {
        if (constructor.getNumberOfParams() != parameters.size()) return false;
        for (int i = 0; i < parameters.size(); i++) {
            if (!sameType(constructor.getParam(i).getType(), parameters.get(i).fqn())) return false;
        }
        return true;
    }

    private static boolean sameType(ResolvedType type, String expected) {
        return expected.equals(NodeIdGenerator.erasedTypeDescribe(type));
    }

    private static <T> T unique(List<T> values) {
        return values.size() == 1 ? values.get(0) : null;
    }

    private static List<ClassValue> parameterTypes(List<Value> values) {
        if (values.size() == 1 && values.get(0) instanceof ClassArrayValue array) {
            return array.values();
        }
        List<ClassValue> parameters = new ArrayList<>();
        for (Value value : values) {
            if (!(value instanceof ClassValue parameter)) return null;
            parameters.add(parameter);
        }
        return parameters;
    }

    private void emitTarget(Node at, TargetValue target, String relation,
                            String operation, String source,
                            ExtractionResult result) {
        String sourceId = enclosing.ownerIdOf(at);
        if (sourceId == null || target.symbol() == null || target.symbol().isBlank()) return;

        Edge edge = new Edge();
        edge.sourceId = sourceId;
        edge.relation = relation;
        edge.callKind = GraphConstants.Relation.CALLS.equals(relation)
                ? GraphConstants.CallKind.REFLECTION : null;
        edge.confidence = GraphConstants.Confidence.INFERRED;
        edge.context = ControlContext.of(at);
        edge.sourceLocation = "L" + at.getBegin().map(position -> position.line).orElse(0);

        ResolvedMethodLikeDeclaration declaration = target.declaration();
        if (declaration != null && ctx.isProjectInternal(declaration.declaringType())) {
            edge.targetId = target.symbol();
            edge.isExternal = false;
        } else {
            edge.externalTargetFqn = target.symbol();
            edge.isExternal = true;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("via", GraphConstants.MetadataVia.REFLECTION);
        metadata.put("operation", operation);
        metadata.put("resolution", "EXACT");
        metadata.put("class_name", target.owner());
        if (target.member() != null) metadata.put("member_name", target.member());
        if (target.parameterTypes() != null) {
            metadata.put("parameter_types", target.parameterTypes());
        }
        metadata.put("lookup", target.declaredLookup() ? "DECLARED" : "PUBLIC_INHERITED");
        metadata.put("value_source", source);
        edge.metadata = Json.writeCompact(metadata);
        result.edges.add(edge);
    }

    private void emitTarget(Node at, ClassValue target, String relation,
                            String operation, String source,
                            ExtractionResult result) {
        String sourceId = enclosing.ownerIdOf(at);
        if (sourceId == null || target.fqn().isBlank()) return;
        Edge edge = new Edge();
        edge.sourceId = sourceId;
        edge.relation = relation;
        edge.confidence = GraphConstants.Confidence.INFERRED;
        edge.context = ControlContext.of(at);
        edge.sourceLocation = "L" + at.getBegin().map(position -> position.line).orElse(0);
        if (target.declaration() != null && ctx.isProjectInternal(target.declaration())) {
            edge.targetId = ctx.idGenerator().forType(target.declaration());
            edge.isExternal = false;
        } else {
            edge.externalTargetFqn = target.fqn();
            edge.isExternal = true;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("via", GraphConstants.MetadataVia.REFLECTION);
        metadata.put("operation", operation);
        metadata.put("resolution", "EXACT");
        metadata.put("class_name", target.fqn());
        metadata.put("value_source", source);
        edge.metadata = Json.writeCompact(metadata);
        result.edges.add(edge);
    }

    private ClassValue classValue(ClassExpr expression) {
        try {
            ResolvedType type = expression.getType().resolve();
            String fqn = NodeIdGenerator.erasedTypeDescribe(type);
            ResolvedReferenceTypeDeclaration declaration = type.isReferenceType()
                    ? type.asReferenceType().getTypeDeclaration().orElse(null) : null;
            return new ClassValue(fqn, declaration, "CLASS_LITERAL");
        } catch (RuntimeException ignored) {
            String lexical = AstTypeNames.of(expression.getType(), expression);
            return new ClassValue(lexical, unitTypes.get(lexical), "CLASS_LITERAL");
        }
    }

    private ClassValue primitiveClassValue(FieldAccessExpr field) {
        if (!"TYPE".equals(field.getNameAsString())) return null;
        try {
            if (!(field.resolve() instanceof ResolvedFieldDeclaration resolved)) return null;
            String owner = resolved.declaringType().getQualifiedName();
            String primitive = PRIMITIVE_TYPES.get(owner);
            return primitive == null ? null : new ClassValue(primitive, null, "CLASS_LITERAL");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Api apiOf(MethodCallExpr call) {
        if (!REFLECTION_METHOD_NAMES.contains(call.getNameAsString())) return null;
        try {
            ResolvedMethodDeclaration method = call.resolve();
            Api resolved = classifyApi(
                    method.declaringType().getQualifiedName(), method.getName());
            if (resolved != null) return resolved;
        } catch (RuntimeException ignored) {
            // Native-image's embedded JDK solver can know a scope type while
            // lacking the full declaration needed by MethodCallExpr.resolve().
        }
        String owner = call.getScope().map(this::resolvedScopeType).orElse(null);
        return classifyApi(owner, call.getNameAsString());
    }

    private static Api classifyApi(String owner, String name) {
        if (JAVA_CLASS.equals(owner) && "forName".equals(name)) {
            return new Api("CLASS_FOR_NAME", name);
        }
        if (JAVA_CLASS.equals(owner)
                && ("getMethod".equals(name) || "getDeclaredMethod".equals(name))) {
            return new Api("METHOD_LOOKUP", name);
        }
        if (JAVA_CLASS.equals(owner)
                && ("getConstructor".equals(name) || "getDeclaredConstructor".equals(name))) {
            return new Api("CONSTRUCTOR_LOOKUP", name);
        }
        if (JAVA_METHOD.equals(owner) && "invoke".equals(name)) {
            return new Api("METHOD_INVOKE", name);
        }
        if (JAVA_CONSTRUCTOR.equals(owner) && "newInstance".equals(name)) {
            return new Api("CONSTRUCTOR_NEW_INSTANCE", name);
        }
        return null;
    }

    private String resolvedScopeType(Expression scope) {
        try {
            return NodeIdGenerator.erasedTypeDescribe(scope.calculateResolvedType());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String methodSymbol(String owner, String name, List<ClassValue> parameters) {
        return owner + "#" + name + "(" + parameters.stream()
                .map(ClassValue::fqn).collect(Collectors.joining(",")) + ")";
    }

    private static String constructorSymbol(String owner, List<ClassValue> parameters) {
        String simple = owner.substring(Math.max(owner.lastIndexOf('.'), owner.lastIndexOf('$')) + 1);
        return owner + "#" + simple + "(" + parameters.stream()
                .map(ClassValue::fqn).collect(Collectors.joining(",")) + ")";
    }

    private static StringValue stringValue(String value, String source) {
        if (value == null || value.length() > MAX_CONSTANT_LENGTH) return null;
        return new StringValue(value, source);
    }

    private static Value local(Value value) {
        if (value == null || value == UnknownValue.INSTANCE) return UnknownValue.INSTANCE;
        if (value instanceof StringValue string) {
            return new StringValue(string.value(), "LOCAL_CONSTANT");
        }
        if (value instanceof ClassValue target) {
            return new ClassValue(target.fqn(), target.declaration(), "LOCAL_HANDLE");
        }
        if (value instanceof ClassArrayValue array) {
            return new ClassArrayValue(array.values(), "LOCAL_CONSTANT");
        }
        if (value instanceof MethodValue method) return method.withSource("LOCAL_HANDLE");
        if (value instanceof ConstructorValue constructor) {
            return constructor.withSource("LOCAL_HANDLE");
        }
        return UnknownValue.INSTANCE;
    }

    private static boolean isUpdate(UnaryExpr expression) {
        return expression.getOperator() == UnaryExpr.Operator.POSTFIX_DECREMENT
                || expression.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
                || expression.getOperator() == UnaryExpr.Operator.PREFIX_DECREMENT
                || expression.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT;
    }

    private static boolean sameValue(Value left, Value right) {
        return left != null && right != null && left.key().equals(right.key());
    }

    private sealed interface Value permits UnknownValue, StringValue, ClassValue,
            ClassArrayValue, TargetValue {
        String key();
    }

    private enum UnknownValue implements Value {
        INSTANCE;
        @Override public String key() { return "unknown"; }
    }

    private record StringValue(String value, String source) implements Value {
        @Override public String key() { return "string:" + value; }
    }

    private record ClassValue(String fqn,
                              ResolvedReferenceTypeDeclaration declaration,
                              String source) implements Value {
        @Override public String key() { return "class:" + fqn; }
    }

    private record ClassArrayValue(List<ClassValue> values, String source) implements Value {
        @Override public String key() {
            return "classes:" + values.stream().map(ClassValue::fqn)
                    .collect(Collectors.joining(","));
        }
    }

    private sealed interface TargetValue extends Value permits MethodValue, ConstructorValue {
        String symbol();
        ResolvedMethodLikeDeclaration declaration();
        String owner();
        String member();
        List<String> parameterTypes();
        boolean declaredLookup();
    }

    private record MethodValue(String symbol,
                               ResolvedMethodDeclaration declaration,
                               String owner,
                               String member,
                               List<String> parameterTypes,
                               String source,
                               boolean declaredLookup) implements TargetValue {
        @Override public String key() { return "method:" + symbol; }
        MethodValue withSource(String replacement) {
            return new MethodValue(symbol, declaration, owner, member,
                    parameterTypes, replacement, declaredLookup);
        }
    }

    private record ConstructorValue(String symbol,
                                    ResolvedConstructorDeclaration declaration,
                                    String owner,
                                    List<String> parameterTypes,
                                    String source,
                                    boolean declaredLookup) implements TargetValue {
        @Override public String key() { return "constructor:" + symbol; }
        @Override public String member() { return null; }
        ConstructorValue withSource(String replacement) {
            return new ConstructorValue(symbol, declaration, owner,
                    parameterTypes, replacement, declaredLookup);
        }
    }

    private record Api(String operation, String methodName) {}

    private static final class State {
        private final Map<String, Value> values = new LinkedHashMap<>();

        Value get(String name) {
            return values.getOrDefault(name, UnknownValue.INSTANCE);
        }

        void put(String name, Value value) {
            if (value == null || value == UnknownValue.INSTANCE) values.remove(name);
            else values.put(name, value);
        }

        void invalidate(String name) {
            values.remove(name);
        }

        State copy() {
            State copy = new State();
            copy.values.putAll(values);
            return copy;
        }

        void retainEqual(State left, State right) {
            values.clear();
            for (Map.Entry<String, Value> entry : left.values.entrySet()) {
                Value other = right.values.get(entry.getKey());
                if (sameValue(entry.getValue(), other)) values.put(entry.getKey(), entry.getValue());
            }
        }

        void retainEqual(List<State> states) {
            if (states.isEmpty()) return;
            values.clear();
            values.putAll(states.get(0).values);
            for (int i = 1; i < states.size(); i++) {
                State candidate = states.get(i);
                values.entrySet().removeIf(entry ->
                        !sameValue(entry.getValue(), candidate.values.get(entry.getKey())));
            }
        }
    }
}
