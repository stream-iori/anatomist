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
        if (method.getParameters().size() != arguments.size()) return Integer.MAX_VALUE;
        int score = 0;
        for (int i = 0; i < arguments.size(); i++) {
            String parameter = AstTypeNames.of(method.getParameter(i).getType(), method.getParameter(i));
            int match = typeMatchScore(arguments.get(i), parameter);
            if (match == Integer.MAX_VALUE) return match;
            score += match;
        }
        return score;
    }

    private static int score(ResolvedMethodDeclaration method, List<String> arguments) {
        if (method.getNumberOfParams() != arguments.size()) return Integer.MAX_VALUE;
        int score = 0;
        for (int i = 0; i < arguments.size(); i++) {
            String parameter;
            try {
                parameter = NodeIdGenerator.erasedTypeDescribe(method.getParam(i).getType());
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
        if (isPrimitive(argument) != isPrimitive(parameter)) return Integer.MAX_VALUE;
        if ("java.lang.Object".equals(parameter)) return 6;
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
        return PRIMITIVES.contains(type);
    }
}
