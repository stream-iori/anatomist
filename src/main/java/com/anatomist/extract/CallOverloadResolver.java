package com.anatomist.extract;

import com.anatomist.core.NodeIdGenerator;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Deterministic overload ranking shared by AST and SymbolSolver fallback paths. */
final class CallOverloadResolver {

    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "double", "float", "int", "long", "short", "void");

    private CallOverloadResolver() {}

    static List<MethodDeclaration> bestAst(List<MethodDeclaration> candidates, MethodCallExpr call) {
        return bestAst(candidates, call, AstTypeNames::ofExpression);
    }

    static List<MethodDeclaration> bestAst(List<MethodDeclaration> candidates, MethodCallExpr call,
                                           java.util.function.Function<Expression, String> typeName) {
        List<String> arguments = call.getArguments().stream()
                .map(typeName)
                .toList();
        return best(candidates, candidate -> score(candidate, arguments));
    }

    static List<ResolvedMethodDeclaration> bestResolved(
            List<ResolvedMethodDeclaration> candidates, MethodCallExpr call) {
        return bestResolved(candidates, call, AstTypeNames::ofExpression);
    }

    static List<ResolvedMethodDeclaration> bestResolved(
            List<ResolvedMethodDeclaration> candidates, MethodCallExpr call,
            java.util.function.Function<Expression, String> typeName) {
        List<String> arguments = call.getArguments().stream()
                .map(typeName)
                .toList();
        return best(candidates, candidate -> score(candidate, arguments));
    }

    private static int score(MethodDeclaration method, List<String> arguments) {
        if (!matchesArity(method, arguments.size())) return Integer.MAX_VALUE;
        int score = method.getParameters().isNonEmpty()
                && method.getParameter(method.getParameters().size() - 1).isVarArgs() ? 16 : 0;
        for (int i = 0; i < arguments.size(); i++) {
            int parameterIndex = Math.min(i, method.getParameters().size() - 1);
            String parameter = AstTypeNames.of(method.getParameter(parameterIndex).getType(),
                    method.getParameter(parameterIndex));
            int match = typeMatchScore(arguments.get(i), parameter);
            if (match == Integer.MAX_VALUE) return match;
            score += match;
        }
        return score;
    }

    static boolean matchesArity(MethodDeclaration method, int argumentCount) {
        int parameterCount = method.getParameters().size();
        if (parameterCount == argumentCount) return true;
        return parameterCount > 0 && method.getParameter(parameterCount - 1).isVarArgs()
                && argumentCount >= parameterCount - 1;
    }

    private static int score(ResolvedMethodDeclaration method, List<String> arguments) {
        int parameterCount = method.getNumberOfParams();
        boolean variadic;
        try {
            variadic = parameterCount > 0 && method.getParam(parameterCount - 1).isVariadic();
        } catch (RuntimeException ignored) {
            variadic = false;
        }
        if (parameterCount != arguments.size()
                && !(variadic && arguments.size() >= parameterCount - 1)) {
            return Integer.MAX_VALUE;
        }
        int score = variadic ? 16 : 0;
        for (int i = 0; i < arguments.size(); i++) {
            String parameter;
            try {
                int parameterIndex = Math.min(i, parameterCount - 1);
                parameter = NodeIdGenerator.erasedTypeDescribe(
                        method.getParam(parameterIndex).getType());
                if (variadic && parameterIndex == parameterCount - 1
                        && parameter.endsWith("[]") && !arguments.get(i).endsWith("[]")) {
                    parameter = parameter.substring(0, parameter.length() - 2);
                }
            } catch (RuntimeException e) {
                parameter = "<unresolved>";
            }
            int match = typeMatchScore(arguments.get(i), parameter);
            if (match == Integer.MAX_VALUE) return match;
            score += match;
        }
        return score;
    }

    private static <T> List<T> best(List<T> candidates, java.util.function.ToIntFunction<T> scorer) {
        int bestScore = Integer.MAX_VALUE;
        List<T> matches = new ArrayList<>();
        for (T candidate : candidates) {
            int score = scorer.applyAsInt(candidate);
            if (score < bestScore) {
                bestScore = score;
                matches.clear();
                matches.add(candidate);
            } else if (score == bestScore) {
                matches.add(candidate);
            }
        }
        return bestScore == Integer.MAX_VALUE ? List.of() : matches;
    }

    private static int typeMatchScore(String argument, String parameter) {
        if (!AstTypeNames.resolved(argument) || !AstTypeNames.resolved(parameter)) return 8;
        if (argument.equals(parameter)) return 0;
        if ("<null>".equals(argument)) return isPrimitive(parameter) ? Integer.MAX_VALUE : 4;
        if (boxed(argument).equals(boxed(parameter))) return 1;
        String argumentPrimitive = primitive(argument);
        String parameterPrimitive = primitive(parameter);
        if (argumentPrimitive != null && parameterPrimitive != null
                && widens(argumentPrimitive, parameterPrimitive)) {
            return 2 + wideningDistance(argumentPrimitive, parameterPrimitive);
        }
        if (argumentPrimitive != null && "java.lang.Number".equals(parameter)
                && !"boolean".equals(argumentPrimitive) && !"char".equals(argumentPrimitive)) {
            return 6;
        }
        if ("java.lang.Object".equals(parameter)) return 6;
        return Integer.MAX_VALUE;
    }

    private static boolean widens(String from, String to) {
        if (from.equals(to)) return true;
        return switch (from) {
            case "byte" -> Set.of("short", "int", "long", "float", "double").contains(to);
            case "short", "char" -> Set.of("int", "long", "float", "double").contains(to);
            case "int" -> Set.of("long", "float", "double").contains(to);
            case "long" -> Set.of("float", "double").contains(to);
            case "float" -> "double".equals(to);
            default -> false;
        };
    }

    private static int wideningDistance(String from, String to) {
        List<String> path = switch (from) {
            case "byte" -> List.of("short", "int", "long", "float", "double");
            case "short", "char" -> List.of("int", "long", "float", "double");
            case "int" -> List.of("long", "float", "double");
            case "long" -> List.of("float", "double");
            case "float" -> List.of("double");
            default -> List.of();
        };
        int index = path.indexOf(to);
        return index < 0 ? 0 : index;
    }

    private static String primitive(String type) {
        if (isPrimitive(type)) return type;
        return switch (type) {
            case "java.lang.Boolean" -> "boolean";
            case "java.lang.Byte" -> "byte";
            case "java.lang.Character" -> "char";
            case "java.lang.Double" -> "double";
            case "java.lang.Float" -> "float";
            case "java.lang.Integer" -> "int";
            case "java.lang.Long" -> "long";
            case "java.lang.Short" -> "short";
            default -> null;
        };
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
        return PRIMITIVES.contains(type);
    }
}
