package com.anatomist.skill;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Information ownership and distribution checks; query behavior is exercised by CLI tests. */
class SkillMdContractTest {
    private static final Set<String> GUIDES = Set.of(
            "core", "topics", "explore", "source", "trace", "relations", "spring", "versions", "maintenance");

    @Test
    void installedSkillIsACompactDiscoveryEntry() throws Exception {
        Path skill = repo().resolve("SKILL.md");
        List<String> lines = Files.readAllLines(skill, StandardCharsets.UTF_8);
        assertEquals("---", lines.getFirst());
        int end = lines.subList(1, lines.size()).indexOf("---") + 1;
        assertTrue(end > 1, "frontmatter closing marker missing");
        var frontmatter = lines.subList(1, end);
        assertEquals(2, frontmatter.size());
        assertTrue(frontmatter.getFirst().startsWith("name: anatomist"));
        assertTrue(frontmatter.get(1).startsWith("description: \""));
        assertTrue(frontmatter.get(1).endsWith("\""));
        assertTrue(Files.size(skill) <= 1024, "discovery entry exceeds 1 KiB");
        assertTrue(lines.size() <= 24, "discovery entry exceeds 24 lines");
    }

    @Test
    void distributedGuidesMatchThePublicScenesAndStayBounded() throws Exception {
        try (var paths = Files.list(sceneDir())) {
            assertEquals(GUIDES, paths.filter(p -> p.toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString().replace(".md", "")).collect(Collectors.toSet()));
        }
        for (String guide : GUIDES) {
            Path path = sceneDir().resolve(guide + ".md");
            byte[] expected = Files.readAllBytes(path);
            assertTrue(expected.length <= 3 * 1024, guide + " exceeds 3 KiB");
            try (var embedded = getClass().getResourceAsStream("/META-INF/anatomist/skills/" + guide + ".md")) {
                assertNotNull(embedded, guide);
                assertArrayEquals(expected, embedded.readAllBytes(), guide);
            }
            String text = new String(expected, StandardCharsets.UTF_8);
            assertFalse(text.contains("Usage:"), guide + " duplicates CLI help");
            assertFalse(text.contains("anatomist --skill"), guide + " references a removed entrypoint");
        }
        for (String removed : List.of("branch", "flow")) {
            assertNull(getClass().getResource("/META-INF/anatomist/skills/" + removed + ".md"));
        }
    }

    @Test
    void conditionalMaintenanceDetailsHaveOneOwner() throws Exception {
        String entry = Files.readString(repo().resolve("SKILL.md"));
        String core = Files.readString(sceneDir().resolve("core.md"));
        String maintenance = Files.readString(sceneDir().resolve("maintenance.md"));
        for (String field : List.of("config_source", "config_path", "scan_policy_hash", "--changed-files-from")) {
            assertFalse(entry.contains(field), field + " leaked into discovery");
            assertFalse(core.contains(field), field + " leaked into common rules");
            assertTrue(maintenance.contains(field), field + " missing from maintenance");
        }
        for (String guide : GUIDES) {
            String text = Files.readString(sceneDir().resolve(guide + ".md"));
            assertFalse(text.contains("copied_pages"), guide + " embeds backup protocol internals");
            assertFalse(text.contains("flow-materialize"), guide + " advertises removed data-flow command");
        }
    }

    private static Path repo() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static Path sceneDir() {
        return repo().resolve("src/main/resources/META-INF/anatomist/skills");
    }
}
