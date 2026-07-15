package com.anatomist.incremental;

import com.anatomist.core.IndexEnvironmentFingerprint;
import com.anatomist.store.SqliteStore;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small project_meta-backed EWMA used to choose incremental work over a full index. */
public final class PerformanceHistory {

    private static final double ALPHA = 0.25d;
    private static final String ENV = "perf_environment_hash";
    private static final String FULL_MS = "perf_full_index_ms_ewma";
    private static final String FULL_FILES = "perf_full_file_count";
    private static final String INC_VARIABLE = "perf_incremental_variable_ms_per_file_ewma";
    private static final String INC_FIXED = "perf_incremental_fixed_ms_ewma";
    private static final String INC_SAMPLES = "perf_incremental_samples";

    private PerformanceHistory() {}

    public static Model read(SqliteStore store, int hardCap, int javaFiles) {
        Map<String, String> meta = store.readProjectMeta();
        String currentEnvironment = meta.get(IndexEnvironmentFingerprint.META_KEY);
        if (currentEnvironment == null || !currentEnvironment.equals(meta.get(ENV))) {
            return new Model(hardCap, javaFiles, 0d, 0d, 0d, 0);
        }
        return new Model(hardCap, javaFiles,
                number(meta.get(FULL_MS)), number(meta.get(INC_VARIABLE)),
                number(meta.get(INC_FIXED)), integer(meta.get(INC_SAMPLES)));
    }

    public static void recordFull(SqliteStore store, long fullIndexMs, int sourceFiles) {
        if (fullIndexMs <= 0 || sourceFiles <= 0) return;
        Map<String, String> meta = store.readProjectMeta();
        String environment = meta.get(IndexEnvironmentFingerprint.META_KEY);
        if (environment == null || environment.isBlank()) return;
        double prior = environment.equals(meta.get(ENV)) ? number(meta.get(FULL_MS)) : 0d;
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ENV, environment);
        values.put(FULL_MS, decimal(ewma(prior, fullIndexMs)));
        values.put(FULL_FILES, String.valueOf(sourceFiles));
        if (!environment.equals(meta.get(ENV))) {
            values.put(INC_VARIABLE, "0");
            values.put(INC_FIXED, "0");
            values.put(INC_SAMPLES, "0");
        }
        store.upsertProjectMeta(values);
    }

    public static void recordIncremental(SqliteStore store,
                                         int reparsedFiles,
                                         long variableMs,
                                         long totalMs) {
        if (reparsedFiles <= 0 || totalMs <= 0) return;
        Map<String, String> meta = store.readProjectMeta();
        String environment = meta.get(IndexEnvironmentFingerprint.META_KEY);
        if (environment == null || !environment.equals(meta.get(ENV))) return;
        double variablePerFile = Math.max(0d, variableMs) / reparsedFiles;
        double fixed = Math.max(0d, totalMs - variableMs);
        Map<String, String> values = new LinkedHashMap<>();
        values.put(INC_VARIABLE, decimal(ewma(number(meta.get(INC_VARIABLE)), variablePerFile)));
        values.put(INC_FIXED, decimal(ewma(number(meta.get(INC_FIXED)), fixed)));
        values.put(INC_SAMPLES, String.valueOf(integer(meta.get(INC_SAMPLES)) + 1));
        store.upsertProjectMeta(values);
    }

    private static double ewma(double prior, double sample) {
        return prior <= 0d ? sample : prior * (1d - ALPHA) + sample * ALPHA;
    }

    private static double number(String value) {
        try {
            return value == null ? 0d : Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return 0d;
        }
    }

    private static int integer(String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    public record Model(int hardCap,
                        int javaFiles,
                        double fullIndexMs,
                        double variableMsPerFile,
                        double fixedMs,
                        int incrementalSamples) {

        public Decision decide(int totalImpact, int processedFiles, long incurredMs) {
            if (totalImpact > hardCap) {
                return new Decision(false, "symbol impact " + totalImpact
                        + ">hard cap " + hardCap, -1L);
            }
            if (fullIndexMs > 0d && incrementalSamples > 0 && variableMsPerFile > 0d) {
                int remaining = Math.max(0, totalImpact - processedFiles);
                long estimated = Math.round(incurredMs + fixedMs + remaining * variableMsPerFile);
                long budget = Math.round(fullIndexMs * 0.70d);
                if (estimated > budget) {
                    return new Decision(false, "estimated incremental " + estimated
                            + "ms>70% full baseline " + Math.round(fullIndexMs)
                            + "ms; symbol impact " + totalImpact, estimated);
                }
                return new Decision(true, "estimated incremental " + estimated
                        + "ms<=70% full baseline " + Math.round(fullIndexMs)
                        + "ms; symbol impact " + totalImpact, estimated);
            }
            int fallback = Math.min(hardCap,
                    Math.max(200, (int) Math.floor(javaFiles * 0.20d)));
            boolean incremental = totalImpact <= fallback;
            return new Decision(incremental,
                    "symbol impact " + totalImpact + (incremental ? "<=" : ">")
                            + "fallback " + fallback, -1L);
        }
    }

    public record Decision(boolean incremental, String reason, long estimatedMs) {}
}
