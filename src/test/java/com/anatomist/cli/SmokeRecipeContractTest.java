package com.anatomist.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeRecipeContractTest {

    @Test
    void smokeRecipe_usesCurrentEnrichCommandAndFailsFast() throws Exception {
        String justfile = Files.readString(repoRoot().resolve("justfile"));
        String smoke = recipeBody(justfile, "smoke:");

        assertTrue(smoke.contains("set -euo pipefail"),
                "smoke recipe must fail fast and propagate command failures");
        assertFalse(smoke.contains(" enrich --node "),
                "standalone enrich command was removed; use context --enrich");
        assertTrue(smoke.contains("context --enrich OrderService"),
                "smoke recipe should exercise the current enrichment CLI");
    }

    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static String recipeBody(String justfile, String recipeHeader) {
        int start = justfile.indexOf(recipeHeader);
        assertTrue(start >= 0, "missing recipe: " + recipeHeader);
        int next = justfile.indexOf("\n# ", start + recipeHeader.length());
        return next >= 0 ? justfile.substring(start, next) : justfile.substring(start);
    }
}
