package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderCapabilityIT {
    @Test
    void javaIndexDoesNotPretendToAnswerPython(@TempDir Path tmp) throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--output", db.toString());

        CliTestSupport.RunResult failed = CliTestSupport.capture(() ->
                new CommandLine(new AnatomistCli()).execute("search", "Example",
                        "--language", "python", "--index", db.toString()));
        assertEquals(3, failed.exitCode(), failed.stderr());
        assertEquals("UNSUPPORTED_CAPABILITY",
                ((Map<?, ?>) Json.parseTree(failed.stderr())).get("code"));

        CliTestSupport.RunResult continued = CliTestSupport.capture(() ->
                new CommandLine(new AnatomistCli()).execute("search", "Example",
                        "--language", "python", "--on-unsupported", "continue",
                        "--index", db.toString(), "--format", "json"));
        assertEquals(0, continued.exitCode(), continued.stderr());
        List<?> records = (List<?>) Json.parseTree(continued.stdout());
        Map<?, ?> evidence = (Map<?, ?>) records.getFirst();
        assertEquals("unsupported", evidence.get("status"));
        assertEquals("python", evidence.get("language"));
        assertEquals("search", evidence.get("operation"));
    }
}
