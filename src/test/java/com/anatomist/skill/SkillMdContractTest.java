package com.anatomist.skill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillMdContractTest {

    private static final List<String> SCENES = List.of(
            "core", "explore", "trace", "branch", "relations", "spring", "flow", "topics");

    @Test
    void installedSkillIsOnlyACompactDiscoveryEntry() throws Exception {
        Path skill = repo().resolve("SKILL.md");
        String text = Files.readString(skill, StandardCharsets.UTF_8);
        List<String> lines = Files.readAllLines(skill, StandardCharsets.UTF_8);

        assertEquals("---", lines.get(0));
        int end = lines.subList(1, lines.size()).indexOf("---") + 1;
        assertTrue(end > 1, "frontmatter closing marker missing");
        List<String> frontmatter = lines.subList(1, end);
        assertEquals(2, frontmatter.size(),
                "Codex skill frontmatter should only contain name/description");
        assertTrue(frontmatter.get(0).startsWith("name: anatomist"));
        assertTrue(frontmatter.get(1).startsWith("description: \""));
        assertTrue(frontmatter.get(1).endsWith("\""),
                "description containing ':' must remain valid quoted YAML");
        assertTrue(Files.size(skill) <= 2 * 1024, "SKILL.md exceeds the 2 KiB budget");
        assertTrue(lines.size() <= 60, "SKILL.md exceeds the 60-line budget");
        assertTrue(text.contains("anatomist skill core"));
        assertTrue(text.contains("anatomist skill topics"));
        assertTrue(text.contains("incremental"));
        assertTrue(text.contains("otherwise built-in defaults"));
        assertTrue(text.contains("CLI flags override"));
        assertTrue(text.contains("config_source"));
        assertFalse(text.toLowerCase().contains("watch"));
        assertFalse(text.contains("## Task details"));
    }

    @Test
    void scenesStaySmallOrthogonalAndFreeOfContinuousIndexGuidance() throws Exception {
        Path sceneDir = sceneDir();
        String core = Files.readString(sceneDir.resolve("core.md"));
        for (String scene : SCENES) {
            Path path = sceneDir.resolve(scene + ".md");
            assertTrue(Files.isRegularFile(path), "missing scene: " + scene);
            String text = Files.readString(path);
            assertTrue(Files.size(path) <= 6 * 1024, scene + " exceeds the 6 KiB budget");
            assertTrue(core.getBytes(StandardCharsets.UTF_8).length
                            + text.getBytes(StandardCharsets.UTF_8).length <= 10 * 1024,
                    "core + " + scene + " exceeds the 10 KiB budget");
            assertFalse(text.contains("Usage:"), scene + " duplicates CLI help");
            assertFalse(text.toLowerCase().contains("watch"),
                    scene + " must not recommend continuous indexing");
            assertFalse(text.contains("anatomist --skill"), scene + " exposes a second entrypoint");
        }
    }

    @Test
    void flowSceneUsesTheCheapestSufficientAnalysis() throws Exception {
        String flow = Files.readString(sceneDir().resolve("flow.md"));
        for (String requirement : List.of(
                "Data-flow is opt-in and expensive",
                "Never enable it for ordinary call tracing",
                "flow-materialize",
                "Scoped coverage",
                "Full coverage only after disclosing cost",
                "Never upgrade to full coverage automatically",
                "Empty partial results do not prove absence")) {
            assertTrue(flow.contains(requirement), "missing flow decision rule: " + requirement);
        }
        for (String structural : List.of("explore", "trace", "branch", "relations", "spring")) {
            String text = Files.readString(sceneDir().resolve(structural + ".md"));
            assertFalse(text.contains("dataflow-mode"), structural + " leaks flow profile syntax");
            assertFalse(text.contains("flow-materialize"), structural + " recommends data-flow");
        }
    }

    @Test
    void sceneGuidanceExplainsSelectorBoundariesWithoutDuplicatingHelp() throws Exception {
        String trace = Files.readString(sceneDir().resolve("trace.md"));
        assertTrue(trace.contains("full method signature"));
        assertTrue(trace.contains("methodExtra"));
        assertTrue(trace.contains("ambiguity response"));

        String relations = Files.readString(sceneDir().resolve("relations.md"));
        assertTrue(relations.contains("Short type or field names"));
        assertTrue(relations.contains("--module"));
        assertTrue(relations.contains("--scope"));

        String flow = Files.readString(sceneDir().resolve("flow.md"));
        assertTrue(flow.contains("flow-summary"));
        assertTrue(flow.contains("full exact source"));
        assertTrue(flow.contains("never falls back"));
    }

    @Test
    void coreExplainsConfigurationProfileDiagnostics() throws Exception {
        String core = Files.readString(sceneDir().resolve("core.md"));
        assertTrue(core.contains("config_source"));
        assertTrue(core.contains("config_path"));
        assertTrue(core.contains("scan_policy_hash"));
        assertTrue(core.contains("profile committed into the index"));
    }

    private static Path repo() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static Path sceneDir() {
        return repo().resolve("src/main/resources/META-INF/anatomist/skills");
    }
}
