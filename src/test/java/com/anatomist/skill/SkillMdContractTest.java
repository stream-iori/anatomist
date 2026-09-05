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
        assertTrue(Files.size(skill) <= 3 * 1024, "SKILL.md exceeds the 3 KiB budget");
        assertTrue(lines.size() <= 80, "SKILL.md exceeds the 80-line budget");
        assertTrue(text.contains("anatomist skill core"));
        assertTrue(text.contains("anatomist skill topics"));
        assertTrue(text.contains("incremental"));
        assertTrue(text.contains("otherwise built-in defaults"));
        assertTrue(text.contains("CLI flags override"));
        assertTrue(text.contains("config_source"));
        assertTrue(text.contains("anatomist pipeline --help"));
        assertTrue(text.contains("--then"));
        assertTrue(text.contains("--file pipeline.json"));
        assertTrue(text.contains("evidence(scope=stream)"));
        assertTrue(text.contains("Exit 5"));
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
                "does not materialize a separate data-flow graph",
                "resolve exact callable → source",
                "trace --to",
                "source --offset",
                "not runtime values")) {
            assertTrue(flow.contains(requirement), "missing flow decision rule: " + requirement);
        }
        for (String removed : List.of(
                "flow-materialize", "flow-path", "flow-summary", "dataflow-mode", "--dataflow")) {
            assertFalse(flow.contains(removed), "flow scene exposes removed command: " + removed);
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
        assertTrue(trace.contains("full signatures"));
        assertTrue(trace.contains("Partial names"));
        assertTrue(trace.contains("pick one entity"));

        String relations = Files.readString(sceneDir().resolve("relations.md"));
        assertTrue(relations.contains("Short names"));
        assertTrue(relations.contains("--module"));
        assertTrue(relations.contains("--scope"));

        String flow = Files.readString(sceneDir().resolve("flow.md"));
        assertTrue(flow.contains("full exact signature"));
        assertTrue(flow.contains("resolve ambiguity"));
        assertTrue(flow.contains("source --offset"));
    }

    @Test
    void exploreChoosesBoundedMethodPagesBeforeWholeClassSource() throws Exception {
        String explore = Files.readString(sceneDir().resolve("explore.md"));
        assertTrue(explore.contains("exact method"));
        assertTrue(explore.contains("source --limit/--offset"));
        assertTrue(explore.contains("state or lifecycle"));
        assertTrue(explore.contains("resolve exact methods first"));
        assertTrue(explore.contains("needs no extra page"));
    }

    @Test
    void coreExplainsConfigurationProfileDiagnostics() throws Exception {
        String core = Files.readString(sceneDir().resolve("core.md"));
        assertTrue(core.contains("config_source"));
        assertTrue(core.contains("config_path"));
        assertTrue(core.contains("scan_policy_hash"));
        assertTrue(core.contains("profile committed into the index"));
    }

    @Test
    void springSceneExplainsMetaAndMemberBindingEvidence() throws Exception {
        String spring = Files.readString(sceneDir().resolve("spring.md"));
        for (String requirement : List.of(
                "--include-meta", "--semantic member", "resolution_status",
                "inspect every candidate", "do not conclude the method is absent",
                "not `CALLS`", "@AliasFor", "runtime bean selection")) {
            assertTrue(spring.contains(requirement),
                    "spring scene missing Agent decision rule: " + requirement);
        }
    }

    private static Path repo() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static Path sceneDir() {
        return repo().resolve("src/main/resources/META-INF/anatomist/skills");
    }
}
