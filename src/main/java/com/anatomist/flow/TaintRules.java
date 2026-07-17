package com.anatomist.flow;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free loader for .anatomist/taint-rules.json. */
public final class TaintRules {

    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_RULES_PER_KIND = 256;
    static final int MAX_METHOD_CHARS = 512;

    public record Rule(String method, String slot) {
        boolean matches(String candidate) {
            if (candidate == null || method == null) return false;
            GlobPattern pattern = GlobPattern.compile(method);
            if (pattern.matches(candidate)) return true;
            String normalized = normalizeCandidate(candidate);
            return !candidate.equals(normalized) && pattern.matches(normalized);
        }
    }

    record Match(Rule source, Rule sink, Rule sanitizer) {}

    private record CompiledRule(Rule rule, GlobPattern pattern) {}

    private final List<CompiledRule> sources;
    private final List<CompiledRule> sinks;
    private final List<CompiledRule> sanitizers;
    private final List<IndexDiagnostic> diagnostics;

    private TaintRules(List<Rule> sources,
                       List<Rule> sinks,
                       List<Rule> sanitizers,
                       List<IndexDiagnostic> diagnostics) {
        this.sources = compile(sources);
        this.sinks = compile(sinks);
        this.sanitizers = compile(sanitizers);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static TaintRules load(Path projectRoot) {
        Path file = projectRoot.resolve(".anatomist/taint-rules.json");
        if (!Files.isRegularFile(file)) {
            return new TaintRules(List.of(), List.of(), List.of(), List.of());
        }
        try {
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_FILE_BYTES) {
                return new TaintRules(List.of(), List.of(), List.of(),
                        List.of(diagnostic("TAINT_RULES_TOO_LARGE", null, 1,
                                "configuration exceeds " + MAX_FILE_BYTES + " bytes")));
            }
            Object tree = Json.parseTree(new String(bytes, StandardCharsets.UTF_8));
            if (!(tree instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("root must be a JSON object");
            }
            DiagnosticAccumulator diagnostics = new DiagnosticAccumulator();
            return new TaintRules(
                    rules(map.get("sources"), "return", "sources", diagnostics),
                    rules(map.get("sinks"), "arg:0", "sinks", diagnostics),
                    rules(map.get("sanitizers"), "return", "sanitizers", diagnostics),
                    diagnostics.toDiagnostics());
        } catch (IOException | RuntimeException e) {
            return new TaintRules(List.of(), List.of(), List.of(),
                    List.of(diagnostic("TAINT_RULES_INVALID", null, 1, e.getMessage())));
        }
    }

    public Rule source(String method) {
        return find(sources, method, normalizeCandidate(method));
    }

    public Rule sink(String method) {
        return find(sinks, method, normalizeCandidate(method));
    }

    public Rule sanitizer(String method) {
        return find(sanitizers, method, normalizeCandidate(method));
    }

    Match classify(String method) {
        String normalized = normalizeCandidate(method);
        return new Match(
                find(sources, method, normalized),
                find(sinks, method, normalized),
                find(sanitizers, method, normalized));
    }

    public List<IndexDiagnostic> diagnostics() {
        return diagnostics;
    }

    private static List<Rule> rules(Object value,
                                    String defaultSlot,
                                    String kind,
                                    DiagnosticAccumulator diagnostics) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) {
            diagnostics.add("TAINT_RULE_SKIPPED", kind, "category must be an array", -1);
            return List.of();
        }
        List<Rule> out = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
            Object item = list.get(index);
            String method = null;
            String slot = defaultSlot;
            if (item instanceof String text && !text.isBlank()) {
                // Assigned below after shared validation.
                method = text;
            } else if (item instanceof Map<?, ?> map) {
                Object suppliedMethod = map.get("method");
                Object suppliedSlot = map.get("slot");
                if (suppliedMethod instanceof String text && !text.isBlank()) {
                    method = text;
                    if (suppliedSlot instanceof String supplied && !supplied.isBlank()) {
                        slot = supplied;
                    }
                }
            }
            if (method == null) {
                diagnostics.add("TAINT_RULE_SKIPPED", kind, "invalid or blank method", index);
                continue;
            }
            if (method.length() > MAX_METHOD_CHARS) {
                diagnostics.add("TAINT_RULE_SKIPPED", kind,
                        "method exceeds " + MAX_METHOD_CHARS + " characters", index);
                continue;
            }
            if (!validSlot(kind, slot)) {
                diagnostics.add("TAINT_RULE_SLOT_INVALID", kind,
                        "unsupported slot " + slot, index);
                continue;
            }
            if (out.size() >= MAX_RULES_PER_KIND) {
                diagnostics.add("TAINT_RULE_LIMIT_EXCEEDED", kind,
                        "more than " + MAX_RULES_PER_KIND + " valid rules", index);
                continue;
            }
            out.add(new Rule(method, slot));
        }
        return out;
    }

    private static boolean validSlot(String kind, String slot) {
        if ("sources".equals(kind) || "sanitizers".equals(kind)) {
            return "return".equals(slot);
        }
        if (!"sinks".equals(kind)) return false;
        if ("this".equals(slot)) return true;
        if (slot == null || !slot.startsWith("arg:") || slot.length() == 4) return false;
        for (int i = 4; i < slot.length(); i++) {
            if (!Character.isDigit(slot.charAt(i))) return false;
        }
        return true;
    }

    private static List<CompiledRule> compile(List<Rule> rules) {
        return rules.stream()
                .map(rule -> new CompiledRule(rule, GlobPattern.compile(rule.method())))
                .toList();
    }

    private static Rule find(List<CompiledRule> rules, String candidate, String normalized) {
        if (candidate == null) return null;
        for (CompiledRule compiled : rules) {
            if (compiled.pattern().matches(candidate)
                    || (!candidate.equals(normalized) && compiled.pattern().matches(normalized))) {
                return compiled.rule();
            }
        }
        return null;
    }

    private static String normalizeCandidate(String candidate) {
        if (candidate == null || !candidate.contains("::MAIN::")) return candidate;
        return candidate.replace("::MAIN::", "");
    }

    private static IndexDiagnostic diagnostic(String code,
                                              String kind,
                                              long count,
                                              String sample) {
        return new IndexDiagnostic("warning", code, "FLOW",
                ".anatomist/taint-rules.json", null, null, kind, count, sample);
    }

    private static final class DiagnosticAccumulator {
        private final Map<DiagnosticKey, DiagnosticProblem> problems = new LinkedHashMap<>();

        void add(String code, String kind, String reason, int index) {
            DiagnosticKey key = new DiagnosticKey(code, kind, reason);
            problems.computeIfAbsent(key, ignored -> new DiagnosticProblem(index)).count++;
        }

        List<IndexDiagnostic> toDiagnostics() {
            return problems.entrySet().stream()
                    .map(entry -> diagnostic(
                            entry.getKey().code(),
                            entry.getKey().kind(),
                            entry.getValue().count,
                            entry.getKey().reason() + "; first_index="
                                    + entry.getValue().firstIndex))
                    .toList();
        }
    }

    private record DiagnosticKey(String code, String kind, String reason) {}

    private static final class DiagnosticProblem {
        private final int firstIndex;
        private long count;

        private DiagnosticProblem(int firstIndex) {
            this.firstIndex = firstIndex;
        }
    }

    /**
     * Full-string wildcard matcher with predictable O(pattern * candidate) work.
     * Consecutive stars are collapsed so hostile glob input cannot trigger
     * regular-expression backtracking.
     */
    private record GlobPattern(char[] pattern) {
        static GlobPattern compile(String source) {
            StringBuilder collapsed = new StringBuilder(source.length());
            boolean previousStar = false;
            for (int i = 0; i < source.length(); i++) {
                char character = source.charAt(i);
                if (character == '*' && previousStar) continue;
                collapsed.append(character);
                previousStar = character == '*';
            }
            return new GlobPattern(collapsed.toString().toCharArray());
        }

        boolean matches(String candidate) {
            boolean[] current = new boolean[pattern.length + 1];
            boolean[] next = new boolean[pattern.length + 1];
            current[0] = true;
            for (int p = 1; p <= pattern.length && pattern[p - 1] == '*'; p++) {
                current[p] = true;
            }
            for (int c = 0; c < candidate.length(); c++) {
                Arrays.fill(next, false);
                char value = candidate.charAt(c);
                for (int p = 1; p <= pattern.length; p++) {
                    char token = pattern[p - 1];
                    if (token == '*') {
                        next[p] = next[p - 1] || current[p];
                    } else if (token == '?' || token == value) {
                        next[p] = current[p - 1];
                    }
                }
                boolean[] swap = current;
                current = next;
                next = swap;
            }
            return current[pattern.length];
        }
    }
}
