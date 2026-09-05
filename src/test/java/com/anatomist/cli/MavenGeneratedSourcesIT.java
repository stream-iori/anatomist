package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenGeneratedSourcesIT {

    @Test
    void generatedRootsParticipateInFullAndIncrementalIndexing(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("reactor");
        Path module = project.resolve("app");
        Files.createDirectories(module.resolve("src/main/java/p"));
        Files.writeString(project.resolve("pom.xml"), pom("parent", "pom",
                "<modules><module>app</module></modules>"));
        Files.writeString(module.resolve("pom.xml"), pom("app", "jar", ""));
        Files.writeString(module.resolve("src/main/java/p/App.java"),
                "package p; public class App {}");

        Path generatedRoot = module.resolve("target/generated-sources/annotations");
        Path generated = generatedRoot.resolve("p/GeneratedOne.java");
        Files.createDirectories(generated.getParent());
        Files.writeString(generated,
                "package p; public class GeneratedOne { void first() {} }");
        Files.writeString(module.resolve("target/Noise.java"), "package p; class Noise {}");

        Path db = tmp.resolve("generated.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "17",
                "--output", db.toString(), "--format", "json");

        assertNode(db, "p.GeneratedOne", "app", "GENERATED", 1);
        assertNode(db, "p.Noise", "app", "GENERATED", 0);
        RunResult mainSearch = run(db, "search", "GeneratedOne");
        assertEquals(0, mainSearch.exitCode(), mainSearch.stderr());
        assertFalse(mainSearch.stdout().contains("p.GeneratedOne"), mainSearch.stdout());
        RunResult generatedSearch = run(db, "search", "GeneratedOne", "--scope", "GENERATED");
        assertEquals(0, generatedSearch.exitCode(), generatedSearch.stderr());
        assertTrue(generatedSearch.stdout().contains("p.GeneratedOne"), generatedSearch.stdout());

        Files.writeString(generated,
                "package p; public class GeneratedOne { void first() {} void second() {} }");
        incremental(project, db);
        assertNode(db, "p.GeneratedOne#second()", "app", "GENERATED", 1);

        Files.delete(generated);
        incremental(project, db);
        assertNode(db, "p.GeneratedOne", "app", "GENERATED", 0);

        Path extra = module.resolve("target/generated-sources/extra/p/GeneratedTwo.java");
        Files.createDirectories(extra.getParent());
        Files.writeString(extra, "package p; public class GeneratedTwo {}");
        incremental(project, db);
        assertNode(db, "p.GeneratedTwo", "app", "GENERATED", 1);
    }

    private static void incremental(Path project, Path db) throws Exception {
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath",
                "--java-version", "17", "--output", db.toString(), "--format", "json");
    }

    private static String pom(String artifact, String packaging, String body) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>p</groupId>
                  <artifactId>%s</artifactId>
                  <version>1</version>
                  <packaging>%s</packaging>
                  %s
                </project>
                """.formatted(artifact, packaging, body);
    }

    private static void assertNode(Path db, String symbol, String module, String scope, int expected)
            throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
             var ps = c.prepareStatement(
                     "SELECT count(*) FROM nodes WHERE symbol_id=? AND module=? AND scope=?")) {
            ps.setString(1, symbol);
            ps.setString(2, module);
            ps.setString(3, scope);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(expected, rs.getInt(1), symbol);
            }
        }
    }

    private static RunResult run(Path db, String... args) throws Exception {
        String[] command = java.util.Arrays.copyOf(args, args.length + 2);
        command[args.length] = "--index";
        command[args.length + 1] = db.toString();
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(command));
    }
}
