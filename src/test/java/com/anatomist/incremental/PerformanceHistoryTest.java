package com.anatomist.incremental;

import com.anatomist.core.IndexEnvironmentFingerprint;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceHistoryTest {

    @Test
    void fallbackAllowsModerateImpactButKeepsLargeClosureFull(@TempDir Path tmp) {
        try (SqliteStore store = preparedStore(tmp)) {
            PerformanceHistory.Model model = PerformanceHistory.read(store, 1000, 4304);

            assertTrue(model.decide(250, 0, 0).incremental());
            assertFalse(model.decide(900, 0, 0).incremental());
            assertFalse(model.decide(2323, 0, 0).incremental());
        }
    }

    @Test
    void historyUsesSeventyPercentFullBudgetAndHonorsHardCap(@TempDir Path tmp) {
        try (SqliteStore store = preparedStore(tmp)) {
            PerformanceHistory.recordFull(store, 100_000, 4304);
            PerformanceHistory.recordIncremental(store, 100, 5_000, 7_000);
            PerformanceHistory.Model model = PerformanceHistory.read(store, 1000, 4304);

            assertTrue(model.decide(500, 0, 0).incremental());
            assertFalse(model.decide(1001, 0, 0).incremental());
            assertFalse(model.decide(1000, 0, 30_000).incremental());
        }
    }

    @Test
    void environmentChangeInvalidatesOldSamples(@TempDir Path tmp) {
        try (SqliteStore store = preparedStore(tmp)) {
            PerformanceHistory.recordFull(store, 100_000, 4304);
            store.upsertProjectMeta(IndexEnvironmentFingerprint.META_KEY, "new-environment");

            PerformanceHistory.Model model = PerformanceHistory.read(store, 1000, 4304);
            assertTrue(model.decide(250, 0, 0).incremental());
            assertFalse(model.decide(900, 0, 0).incremental());
        }
    }

    private static SqliteStore preparedStore(Path tmp) {
        SqliteStore store = new SqliteStore(tmp.resolve("index.db"));
        store.initSchema();
        store.upsertProjectMeta(IndexEnvironmentFingerprint.META_KEY, "environment");
        return store;
    }
}
