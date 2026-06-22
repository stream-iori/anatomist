package com.anatomist.framework.spring;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class SpringAnnotationSupport {

    private SpringAnnotationSupport() {}

    static Optional<AnnotationExpr> first(NodeList<AnnotationExpr> annotations, Set<String> names) {
        for (AnnotationExpr ann : annotations) {
            if (names.contains(simpleName(ann))) return Optional.of(ann);
        }
        return Optional.empty();
    }

    static boolean has(NodeList<AnnotationExpr> annotations, String name) {
        return first(annotations, Set.of(name)).isPresent();
    }

    static String simpleName(AnnotationExpr ann) {
        String n = ann.getNameAsString();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : n;
    }

    static String stringAttribute(AnnotationExpr ann, String name) {
        if (ann instanceof SingleMemberAnnotationExpr sm && ("value".equals(name) || name == null)) {
            return stringValue(sm.getMemberValue());
        }
        if (ann instanceof NormalAnnotationExpr norm) {
            for (MemberValuePair p : norm.getPairs()) {
                if (p.getNameAsString().equals(name)) return stringValue(p.getValue());
            }
        }
        return null;
    }

    static List<String> stringListAttribute(AnnotationExpr ann, String name) {
        Expression value = null;
        if (ann instanceof SingleMemberAnnotationExpr sm && ("value".equals(name) || name == null)) {
            value = sm.getMemberValue();
        } else if (ann instanceof NormalAnnotationExpr norm) {
            for (MemberValuePair p : norm.getPairs()) {
                if (p.getNameAsString().equals(name)) {
                    value = p.getValue();
                    break;
                }
            }
        }
        if (value == null) return List.of();
        if (value instanceof ArrayInitializerExpr arr) {
            List<String> out = new ArrayList<>();
            for (Expression e : arr.getValues()) {
                String s = stringValue(e);
                if (s != null) out.add(s);
            }
            return out;
        }
        String s = stringValue(value);
        return s == null ? List.of() : List.of(s);
    }

    static String stringValue(Expression e) {
        if (e instanceof StringLiteralExpr s) return s.asString();
        String raw = e.toString();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw.isBlank() ? null : raw;
    }

    static String decapitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String normalizePath(String p) {
        if (p == null || p.isBlank()) return "";
        String out = p.trim();
        if (!out.startsWith("/")) out = "/" + out;
        while (out.length() > 1 && out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    static String joinPaths(String a, String b) {
        String left = normalizePath(a);
        String right = normalizePath(b);
        if (left.isEmpty()) return right.isEmpty() ? "/" : right;
        if (right.isEmpty() || "/".equals(right)) return left;
        if ("/".equals(left)) return right;
        return left + right;
    }
}
