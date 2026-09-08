package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

class SkillCommandTest {

    @Test
    void defaultsToCoreAndEverySceneIsAvailable() throws Exception {
        CliTestSupport.RunResult defaultResult = run("skill");
        CliTestSupport.RunResult core = run("skill", "core");
        assertEquals(0, defaultResult.exitCode(), defaultResult.stderr());
        assertEquals(core.stdout(), defaultResult.stdout());

        for (String scene : SkillCommand.SCENES) {
            CliTestSupport.RunResult result = run("skill", scene);
            assertEquals(0, result.exitCode(), scene + ": " + result.stderr());
            assertTrue(result.stdout().startsWith("# "), scene);
            assertFalse(result.stdout().contains("Usage:"), scene);
        }
    }

    @Test
    void unknownSceneAndRemovedOptionFailClearly() throws Exception {
        CliTestSupport.RunResult unknown = run("skill", "unknown");
        assertEquals(2, unknown.exitCode());
        assertTrue(unknown.stderr().contains("unknown skill scene"), unknown.stderr());
        for (String scene : SkillCommand.SCENES) assertTrue(unknown.stderr().contains(scene));
        for (String old : java.util.List.of("branch", "flow")) {
            var removedScene = run("skill", old);
            assertEquals(2, removedScene.exitCode());
            assertTrue(removedScene.stderr().contains("anatomist skill source"));
        }

        CliTestSupport.RunResult removed = run("--skill", "flow");
        assertEquals(2, removed.exitCode());
        assertTrue((removed.stdout() + removed.stderr()).contains("Unknown option"));
    }

    @Test
    void rootHelpCrossLinksButDoesNotEmbedGuidance() throws Exception {
        CliTestSupport.RunResult help = run("--help");
        assertEquals(0, help.exitCode(), help.stderr());
        assertTrue(help.stdout().contains("anatomist skill topics"));
        assertTrue(help.stdout().contains("skill"));
        assertFalse(help.stdout().contains("watch"), help.stdout());
        assertFalse(help.stdout().contains("Full analysis creates CFG"));
    }

    @Test
    void agentGuidanceDoesNotRecommendWatch() throws Exception {
        for (String scene : SkillCommand.SCENES) {
            CliTestSupport.RunResult result = run("skill", scene);
            assertFalse(result.stdout().toLowerCase(java.util.Locale.ROOT).contains("watch"),
                    scene + ": " + result.stdout());
        }
        String rootSkill = java.nio.file.Files.readString(java.nio.file.Path.of("SKILL.md"));
        assertFalse(rootSkill.toLowerCase(java.util.Locale.ROOT).contains("watch"), rootSkill);
    }

    @Test
    void allSceneLinksResolve() throws Exception {
        var texts = new java.util.ArrayList<String>();
        texts.add(java.nio.file.Files.readString(java.nio.file.Path.of("SKILL.md")));
        for (String scene : SkillCommand.SCENES) texts.add(run("skill", scene).stdout());
        var links = java.util.regex.Pattern.compile("skill ([a-z]+)");
        for (String content : texts) {
            var matcher = links.matcher(content);
            while (matcher.find()) assertEquals(0, run("skill", matcher.group(1)).exitCode(), matcher.group());
        }
    }

    private static CliTestSupport.RunResult run(String... args) throws Exception {
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(args));
    }
}
