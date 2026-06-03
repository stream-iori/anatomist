package com.anatomist.core;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Opt-in diagnostic (enabled via {@code -Danatomist.sampleUnresolved=true}) that
 * buckets captured unresolved-symbol names into PROJECT-INTERNAL / THIRD-PARTY /
 * JDK / inference-noise categories and lists the heaviest package prefixes.
 *
 * <p>Tells us whether the remaining Unresolved count is worth chasing (e.g. by
 * adding a module's {@code target/classes} to the classpath) or is just generics
 * / method-inference noise. Extracted out of {@code IndexCommand} so the
 * orchestration command stays focused on flow.</p>
 */
public final class UnresolvedReporter {

    private UnresolvedReporter() {}

    /**
     * Render the sampling report to {@code out}.
     *
     * @param samples         captured symbol-name → hit-count
     * @param projectPackages package names of project-internal types
     * @param totalUnresolved total unresolved count (named + unnamed)
     */
    public static void print(PrintStream out,
                             Map<String, Long> samples,
                             Set<String> projectPackages,
                             long totalUnresolved) {
        out.println("  --- Unresolved sampling ---");
        if (samples.isEmpty()) {
            out.println("    (no samples captured; most unresolved sites carry no symbol name)");
            return;
        }

        long captured = samples.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Long> byCategory = new TreeMap<>();
        Map<String, Long> byPrefix = new HashMap<>();
        for (Map.Entry<String, Long> e : samples.entrySet()) {
            String name = e.getKey();
            long cnt = e.getValue();
            String category = categorize(name, projectPackages);
            byCategory.merge(category, cnt, Long::sum);
            // Only the genuine missing-type categories are classpath-actionable;
            // bucket those by package prefix to see which libs/modules are absent.
            if (category.equals("MISSING-TYPE-INTERNAL") || category.equals("MISSING-TYPE-THIRDPARTY")) {
                byPrefix.merge(prefixOf(name), cnt, Long::sum);
            }
        }

        out.println("    Captured " + captured + " named hits across "
                + samples.size() + " distinct symbols"
                + " (of " + totalUnresolved + " total unresolved).");
        out.println("    By category:");
        byCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(en -> out.printf("      %-26s %8d  (%4.1f%%)%n",
                        en.getKey(), en.getValue(),
                        100.0 * en.getValue() / captured));
        if (byPrefix.isEmpty()) {
            out.println("    No classpath-actionable missing types — remainder is "
                    + "method-inference / generics / snippets.");
            return;
        }
        out.println("    Top 30 missing-type prefixes (classpath-actionable):");
        byPrefix.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(30)
                .forEach(en -> out.printf("      %8d  %s%n", en.getValue(), en.getKey()));
    }

    /**
     * Bucket a captured failure key. Most non-{@code UnsolvedSymbolException}
     * failures surface as a sentence or code snippet, not a type FQN — those are
     * method-overload / generics inference limits, NOT something a wider
     * classpath fixes. Only clean dotted/simple type names count as missing types.
     */
    public static String categorize(String name, Set<String> projectPackages) {
        if (name.startsWith("[")) return "OTHER-INFERENCE";
        if (name.contains("unable to find the method declaration")) return "METHOD-NOT-FOUND";
        if (name.endsWith("is not a class")) return "NOT-A-CLASS";
        // Whitespace / call syntax / braces ⇒ a code snippet or message, not a symbol.
        if (name.indexOf(' ') >= 0 || name.indexOf('(') >= 0
                || name.indexOf('{') >= 0 || name.indexOf('\n') >= 0) {
            return "OTHER-INFERENCE";
        }
        if (name.indexOf('.') < 0) return "UNQUALIFIED-OR-GENERIC";
        if (name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jakarta.") || name.startsWith("sun.")
                || name.startsWith("jdk.") || name.startsWith("com.sun.")) {
            return "JDK";
        }
        for (String pkg : projectPackages) {
            if (name.equals(pkg) || name.startsWith(pkg + ".")) return "MISSING-TYPE-INTERNAL";
        }
        return "MISSING-TYPE-THIRDPARTY";
    }

    /** First up to 3 dot-segments of a symbol name, for prefix bucketing. */
    public static String prefixOf(String name) {
        int idx = 0;
        for (int seg = 0; seg < 3; seg++) {
            int next = name.indexOf('.', idx);
            if (next < 0) return name;
            idx = next + 1;
        }
        return name.substring(0, idx - 1);
    }
}
