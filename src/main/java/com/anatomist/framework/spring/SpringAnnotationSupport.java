package com.anatomist.framework.spring;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.resolution.declarations.ResolvedAnnotationDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

final class SpringAnnotationSupport {

    static final int MAX_META_DEPTH = 16;

    record Match(AnnotationExpr annotation, String directFqn, String rootFqn,
                 List<String> metaPath, String resolutionStatus) {}

    private SpringAnnotationSupport() {}

    /** Match only resolved/qualified framework FQNs, including bounded composed annotations. */
    static Optional<Match> firstMatch(NodeList<AnnotationExpr> annotations, Set<String> rootFqns) {
        for (AnnotationExpr annotation : annotations) {
            Resolved resolved = resolve(annotation);
            if (resolved.fqn() == null) continue;
            if (rootFqns.contains(resolved.fqn())) {
                return Optional.of(new Match(annotation, resolved.fqn(), resolved.fqn(),
                        List.of(resolved.fqn()), resolved.status()));
            }
            if (resolved.declaration() == null) continue;
            List<String> path = findMetaPath(resolved.declaration(), rootFqns,
                    new HashSet<>(), 0);
            if (!path.isEmpty()) {
                List<String> full = new ArrayList<>();
                full.add(resolved.fqn());
                full.addAll(path);
                return Optional.of(new Match(annotation, resolved.fqn(),
                        full.get(full.size() - 1), List.copyOf(full), resolved.status()));
            }
        }
        return Optional.empty();
    }

    static Map<String, Object> evidence(Match match) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("annotationFqn", match.directFqn());
        out.put("frameworkAnnotationRoot", match.rootFqn());
        out.put("metaPath", match.metaPath());
        out.put("annotationResolutionStatus", match.resolutionStatus());
        return out;
    }

    private static List<String> findMetaPath(ResolvedAnnotationDeclaration declaration,
                                             Set<String> roots,
                                             Set<String> seen,
                                             int depth) {
        String current = declaration.getQualifiedName();
        if (depth >= MAX_META_DEPTH || !seen.add(current)) return List.of();
        try {
            List<ResolvedAnnotationDeclaration> annotations = declaration.getDeclaredAnnotations().stream()
                    .sorted(java.util.Comparator.comparing(ResolvedAnnotationDeclaration::getQualifiedName))
                    .toList();
            for (ResolvedAnnotationDeclaration meta : annotations) {
                String fqn = meta.getQualifiedName();
                if (roots.contains(fqn)) return List.of(fqn);
                List<String> suffix = findMetaPath(meta, roots, new HashSet<>(seen), depth + 1);
                if (!suffix.isEmpty()) {
                    List<String> path = new ArrayList<>();
                    path.add(fqn);
                    path.addAll(suffix);
                    return List.copyOf(path);
                }
            }
        } catch (RuntimeException ignored) {
            // An unavailable classpath declaration is not a Spring match.
        }
        return List.of();
    }

    private static Resolved resolve(AnnotationExpr annotation) {
        try {
            ResolvedAnnotationDeclaration declaration = annotation.resolve();
            return new Resolved(declaration.getQualifiedName(), "exact", declaration);
        } catch (RuntimeException ignored) {
            String raw = annotation.getNameAsString();
            if (raw.contains(".")) return new Resolved(raw, "heuristic", null);
            String imported = annotation.findCompilationUnit().flatMap(unit -> unit.getImports().stream()
                    .filter(value -> !value.isAsterisk() && !value.isStatic())
                    .map(value -> value.getNameAsString())
                    .filter(value -> value.endsWith("." + raw))
                    .findFirst()).orElse(null);
            return new Resolved(imported, imported == null ? "unresolved" : "heuristic", null);
        }
    }

    private record Resolved(String fqn, String status,
                            ResolvedAnnotationDeclaration declaration) {}

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
