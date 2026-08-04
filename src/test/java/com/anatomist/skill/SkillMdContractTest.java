package com.anatomist.skill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillMdContractTest {

    @Test
    void skillFrontmatterAndIdeaCategoriesStayDiscoverable() throws Exception {
        Path skill = Path.of(System.getProperty("user.dir")).resolve("SKILL.md");
        String text = Files.readString(skill, StandardCharsets.UTF_8);
        List<String> lines = Files.readAllLines(skill, StandardCharsets.UTF_8);

        assertEquals("---", lines.get(0));
        int end = lines.subList(1, lines.size()).indexOf("---") + 1;
        assertTrue(end > 1, "frontmatter closing marker missing");
        List<String> frontmatter = lines.subList(1, end);
        assertEquals(2, frontmatter.size(), "Codex skill frontmatter should only contain name/description");
        assertTrue(frontmatter.get(0).startsWith("name: anatomist"));
        assertTrue(frontmatter.get(1).startsWith("description: "));

        for (String category : List.of(
                "Entry discovery",
                "Local context",
                "Forward trace",
                "Branch/control-flow slice",
                "Reverse impact",
                "Type relation",
                "Field relation",
                "Architecture map",
                "Framework wiring",
                "Evidence hygiene")) {
            assertTrue(text.contains(category), "missing IDEA task category: " + category);
        }

        for (String rule : List.of(
                "hierarchy` is upward only",
                "implementors-of <type> --recursive",
                "context=field_type",
                "field-access",
                "branches-of",
                "--in-branch",
                "project_meta",
                "source-window",
                "through-callbacks",
                "stats.depth_truncated",
                "stats.limit_truncated",
                "depth_limit_reached",
                "follow both entries in `next_queries`",
                "## Checkout and index isolation (P0)",
                "The index identity is the final DB path, not the Git branch.",
                "Never pass `doctor.index_path` from a different checkout as `--output`.",
                "persistent empty `.lock` file is not itself an owner")) {
            assertTrue(text.contains(rule), "missing skill rule: " + rule);
        }
    }

    @Test
    void flowSkillContractRequiresFullPathsAndExactEndpoints() throws Exception {
        Path skill = Path.of(System.getProperty("user.dir")).resolve("SKILL.md");
        String text = Files.readString(skill, StandardCharsets.UTF_8);

        for (String requirement : List.of(
                "`--dataflow-mode full`",
                "`FLOW_COVERAGE_INCOMPLETE`",
                "Do not run `flow-path` or `taint-path` against `summary` or `scoped` indexes.",
                "[--from-slot arg:0]",
                "[--to-slot return]",
                "`FLOW_ENDPOINT_AMBIGUOUS`",
                "sink `arg:N` or `this`")) {
            assertTrue(text.contains(requirement),
                    "missing flow skill contract: " + requirement);
        }
    }
}
