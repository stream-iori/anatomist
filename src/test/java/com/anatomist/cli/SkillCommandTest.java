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
        assertTrue(unknown.stderr().contains("core, explore, trace"), unknown.stderr());

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
    void indexAndDoctorHelpExposeConfigurationContracts() throws Exception {
        CliTestSupport.RunResult index = run("index", "--help");
        assertEquals(0, index.exitCode(), index.stderr());
        assertTrue(index.stdout().contains("otherwise built-in defaults"), index.stdout());
        assertTrue(index.stdout().contains("Built-in scan: MAIN + GENERATED"), index.stdout());
        assertTrue(index.stdout().contains("Precedence: CLI,"), index.stdout());
        assertTrue(index.stdout().contains("Maven/Gradle, then Java 8."), index.stdout());
        assertTrue(index.stdout().contains("Add TEST to effective scan scopes"), index.stdout());
        assertTrue(index.stdout().contains("Lombok is off by default"), index.stdout());
        assertTrue(index.stdout().contains("[extensions.lombok]"), index.stdout());

        CliTestSupport.RunResult doctor = run("doctor", "--help");
        assertEquals(0, doctor.exitCode(), doctor.stderr());
        assertTrue(doctor.stdout().contains("config_source"), doctor.stdout());
        assertTrue(doctor.stdout().contains("config_path"), doctor.stdout());
        assertTrue(doctor.stdout().contains("scan_policy_hash"), doctor.stdout());
    }

    private static CliTestSupport.RunResult run(String... args) throws Exception {
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(args));
    }
}
