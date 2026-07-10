package com.anatomist.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexApplicationServiceTest {

    private final IndexApplicationService service = new IndexApplicationService();

    @Test
    void rejectsConflictingSourceModelsBeforeRunningWork(@TempDir Path project) {
        boolean[] ran = {false};
        IndexOutcome outcome = service.execute(
                new IndexRequest(project, "src", List.of("app@MAIN=src")), root -> {
                    ran[0] = true;
                    return 0;
                });

        assertEquals(2, outcome.exitCode());
        assertFalse(ran[0]);
        assertTrue(outcome.error().contains("mutually exclusive"));
    }

    @Test
    void propagatesWorkerFailureAsOutcome(@TempDir Path project) {
        IndexOutcome outcome = service.execute(
                new IndexRequest(project, null, List.of()),
                root -> { throw new IllegalStateException("boom"); });

        assertEquals(1, outcome.exitCode());
        assertEquals("index failed: boom", outcome.error());
        assertInstanceOf(IllegalStateException.class, outcome.cause());
    }
}
