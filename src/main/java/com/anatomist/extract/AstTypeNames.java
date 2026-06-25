package com.anatomist.extract;

import com.anatomist.core.NodeIdGenerator;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/** Best-effort AST-only type rendering used after SymbolSolver gives up. */
final class AstTypeNames {

    private static final Set<String> JAVA_LANG = Set.of(
            "String", "Object", "Boolean", "Byte", "Character", "Class", "Double",
            "Enum", "Float", "Integer", "Long", "Math", "Number", "Short",
            "StringBuilder", "StringBuffer", "System", "Throwable", "Exception",
            "RuntimeException", "IllegalArgumentException", "IllegalStateException",
            "Void", "Iterable", "Comparable", "Override", "Deprecated");

    private AstTypeNames() {}

    static String of(Type type, Node context) {
        if (type == null) return "<unresolved>";
        try {
            return NodeIdGenerator.erasedTypeDescribe(type.resolve());
        } catch (RuntimeException ignore) {
            return ofAst(type, context);
        }
    }

    static String ofAst(Type type, Node context) {
        if (type == null) return "<unresolved>";
        if (type.isPrimitiveType()) return primitive(type.asPrimitiveType());
        if (type.isVoidType()) return "void";
        if (type.isArrayType()) return ofArray(type.asArrayType(), context);
        if (type.isClassOrInterfaceType()) return ofClass(type.asClassOrInterfaceType(), context);
        if (type.isVarType()) return "<unresolved>";
        if (type.isUnionType()) {
            return type.asUnionType().getElements().stream()
                    .map(t -> of(t, context))
                    .filter(AstTypeNames::resolved)
                    .findFirst().orElse("<unresolved>");
        }
        if (type.isIntersectionType()) {
            return type.asIntersectionType().getElements().stream()
                    .map(t -> of(t, context))
                    .filter(AstTypeNames::resolved)
                    .findFirst().orElse("<unresolved>");
        }
        return stripGenericArgs(type.asString());
    }

    static String ofExpression(Expression expr) {
        if (expr == null) return "<unresolved>";
        try {
            ResolvedType type = expr.calculateResolvedType();
            return NodeIdGenerator.erasedTypeDescribe(type);
        } catch (RuntimeException ignore) {
            return ofExpressionAst(expr);
        }
    }

    private static String ofExpressionAst(Expression expr) {
        if (expr instanceof EnclosedExpr e) return ofExpression(e.getInner());
        if (expr instanceof CastExpr e) return of(e.getType(), e);
        if (expr instanceof ObjectCreationExpr e) return of(e.getType(), e);
        if (expr instanceof StringLiteralExpr) return "java.lang.String";
        if (expr instanceof BooleanLiteralExpr) return "boolean";
        if (expr instanceof CharLiteralExpr) return "char";
        if (expr instanceof IntegerLiteralExpr) return "int";
        if (expr instanceof LongLiteralExpr) return "long";
        if (expr instanceof DoubleLiteralExpr) return "double";
        if (expr instanceof NullLiteralExpr) return "<null>";
        if (expr instanceof ConditionalExpr e) {
            String thenType = ofExpression(e.getThenExpr());
            String elseType = ofExpression(e.getElseExpr());
            return thenType.equals(elseType) ? thenType : "<unresolved>";
        }
        if (expr instanceof NameExpr e) {
            String found = findVisibleNameType(e.getNameAsString(), e);
            if (found != null) return found;
        }
        return "<unresolved>";
    }

    static String findVisibleNameType(String name, Node context) {
        if (name == null || name.isBlank() || context == null) return null;

        Optional<MethodDeclaration> method = context.findAncestor(MethodDeclaration.class);
        if (method.isPresent()) {
            for (Parameter p : method.get().getParameters()) {
                if (name.equals(p.getNameAsString())) return of(p.getType(), p);
            }
            int line = lineOf(context);
            for (VariableDeclarator v : method.get().findAll(VariableDeclarator.class)) {
                if (!name.equals(v.getNameAsString())) continue;
                if (lineOf(v) > line) continue;
                return of(v.getType(), v);
            }
        }

        Optional<CatchClause> catchClause = context.findAncestor(CatchClause.class);
        if (catchClause.isPresent() && name.equals(catchClause.get().getParameter().getNameAsString())) {
            return of(catchClause.get().getParameter().getType(), catchClause.get().getParameter());
        }

        Optional<TypeDeclaration> type = context.findAncestor(TypeDeclaration.class);
        if (type.isPresent()) {
            @SuppressWarnings("unchecked")
            java.util.List<FieldDeclaration> fields = (java.util.List<FieldDeclaration>) type.get().getFields();
            for (FieldDeclaration f : fields) {
                for (VariableDeclarator v : f.getVariables()) {
                    if (name.equals(v.getNameAsString())) return of(v.getType(), v);
                }
            }
        }
        return null;
    }

    static String qualifySimpleName(Node context, String name) {
        if (name == null || name.isBlank()) return name;
        if (name.contains(".")) return qualifyDotted(context, name);
        if (JAVA_LANG.contains(name)) return "java.lang." + name;
        Optional<CompilationUnit> cuOpt = context == null ? Optional.empty() : context.findCompilationUnit();
        if (cuOpt.isEmpty()) return name;
        CompilationUnit cu = cuOpt.get();
        for (ImportDeclaration imp : cu.getImports()) {
            if (imp.isAsterisk() || imp.isStatic()) continue;
            String imported = imp.getNameAsString();
            if (simpleName(imported).equals(name)) return imported;
        }
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk() || imp.isStatic()) continue;
            String prefix = imp.getNameAsString();
            if ("java.lang".equals(prefix) && JAVA_LANG.contains(name)) return "java.lang." + name;
        }
        return cu.getPackageDeclaration()
                .map(pkg -> pkg.getNameAsString() + "." + name)
                .orElse(name);
    }

    private static String ofClass(ClassOrInterfaceType type, Node context) {
        String raw = type.getNameWithScope();
        if (type.getScope().isPresent()) {
            String scope = ofClass(type.getScope().get(), context);
            if (resolved(scope)) return scope + "." + type.getNameAsString();
        }
        return qualifyDotted(context, raw);
    }

    private static String qualifyDotted(Node context, String raw) {
        String noGenerics = stripGenericArgs(raw);
        int dot = noGenerics.indexOf('.');
        if (dot < 0) return qualifySimpleName(context, noGenerics);
        String first = noGenerics.substring(0, dot);
        String rest = noGenerics.substring(dot + 1);
        String qualifiedFirst = qualifySimpleName(context, first);
        return qualifiedFirst + "." + rest;
    }

    private static String ofArray(ArrayType type, Node context) {
        int dims = 0;
        Type t = type;
        while (t.isArrayType()) {
            dims++;
            t = t.asArrayType().getComponentType();
        }
        StringBuilder out = new StringBuilder(of(t, context));
        for (int i = 0; i < dims; i++) out.append("[]");
        return out.toString();
    }

    private static String primitive(PrimitiveType type) {
        return type.asString();
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String stripGenericArgs(String s) {
        if (s == null) return "<unresolved>";
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') { depth++; continue; }
            if (c == '>') { if (depth > 0) depth--; continue; }
            if (depth == 0 && !Character.isWhitespace(c)) out.append(c);
        }
        return out.toString();
    }

    private static int lineOf(Node node) {
        return node.getBegin().map(p -> p.line).orElse(Integer.MAX_VALUE);
    }

    static boolean resolved(String typeName) {
        return typeName != null && !typeName.isBlank() && !"<unresolved>".equals(typeName);
    }

    static Set<String> typeNamesOf(Expression expr) {
        Set<String> out = new HashSet<>();
        String type = ofExpression(expr);
        if (resolved(type)) out.add(type);
        return out;
    }
}
