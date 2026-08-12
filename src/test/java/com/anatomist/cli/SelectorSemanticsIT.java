package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectorSemanticsIT {

    @Test
    void structuralCommandsShareExactFamilyAndAmbiguityRules(@TempDir Path tmp)
            throws Exception {
        Path project = CliTestSupport.createSimpleMavenProject(tmp, false);
        write(project, "a/Port.java", """
                package a;
                public interface Port {}
                class PortImpl implements Port {}
                """);
        write(project, "b/Port.java", """
                package b;
                public interface Port {}
                class PortImpl implements Port {}
                """);
        write(project, "a/Box.java", """
                package a;
                public class Box { public int value; int read() { return value; } }
                """);
        write(project, "b/Box.java", """
                package b;
                public class Box { public int value; int read() { return value; } }
                """);
        write(project, "p/Overloads.java", """
                package p;
                public class Overloads {
                    void stringSink() {}
                    void intSink() {}
                    void extraSink() {}
                    void pick(String value) { stringSink(); }
                    void pick(int value) { intSink(); }
                    void pickExtra(String value) { extraSink(); }
                }
                """);
        write(project, "q/Other.java", """
                package q;
                public class Other { void pick(String value) {} }
                """);
        write(project, "p/Empty.java", """
                package p;
                public class Empty {
                    int idle;
                    void noop() {}
                }
                """);

        Path db = tmp.resolve("selectors.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "17",
                "--output", db.toString(), "--format", "json");

        for (String[] command : new String[][] {
                {"context", "Port"},
                {"context", "--enrich", "Port", "--format", "json"},
                {"hierarchy", "Port"},
                {"implementors-of", "Port"},
                {"deps-of", "Port"},
                {"used-by", "Port"},
                {"field-access", "Box.value"},
                {"callees-of", "pick"}
        }) {
            RunResult result = run(db, command);
            assertEquals(2, result.exitCode(), result.stdout());
            assertTrue(result.stdout().contains("SYMBOL_AMBIGUOUS"), result.stdout());
            assertTrue(result.stdout().contains("next_queries"), result.stdout());
        }

        RunResult exactType = run(db, "implementors-of", "a.Port");
        assertEquals(0, exactType.exitCode(), exactType.stderr());
        assertTrue(exactType.stdout().contains("a.PortImpl"), exactType.stdout());
        assertFalse(exactType.stdout().contains("b.PortImpl"), exactType.stdout());

        RunResult family = run(db, "callees-of", "p.Overloads#pick");
        assertEquals(0, family.exitCode(), family.stderr());
        assertTrue(family.stdout().contains("stringSink"), family.stdout());
        assertTrue(family.stdout().contains("intSink"), family.stdout());
        assertFalse(family.stdout().contains("extraSink"), family.stdout());

        RunResult exact = run(db, "context", "p.Overloads#pick(java.lang.String)");
        assertEquals(0, exact.exitCode(), exact.stderr());
        assertTrue(exact.stdout().contains("p.Overloads#pick(java.lang.String)"), exact.stdout());
        assertFalse(exact.stdout().contains("p.Overloads#pick(int)"), exact.stdout());

        for (String[] command : new String[][] {
                {"context", "missing.DoesNotExist"},
                {"context", "--enrich", "missing.DoesNotExist", "--format", "json"},
                {"hierarchy", "missing.DoesNotExist"},
                {"implementors-of", "missing.DoesNotExist"},
                {"deps-of", "missing.DoesNotExist"},
                {"used-by", "missing.DoesNotExist"},
                {"field-access", "missing.DoesNotExist#field"},
                {"callees-of", "missing.DoesNotExist#run()"},
                {"callers-of", "missing.DoesNotExist#run()"},
                {"branches-of", "missing.DoesNotExist#run()"}
        }) {
            RunResult result = run(db, command);
            assertEquals(2, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("SYMBOL_NOT_FOUND"), result.stdout());
            assertFalse(result.stdout().contains("confirmed_empty"), result.stdout());
        }

        for (String[] command : new String[][] {
                {"callees-of", "p.Empty#noop()"},
                {"callers-of", "p.Empty#noop()"},
                {"branches-of", "p.Empty#noop()"},
                {"field-access", "p.Empty#idle"}
        }) {
            RunResult result = run(db, command);
            assertEquals(0, result.exitCode(), result.stdout() + result.stderr());
            assertTrue(result.stdout().contains("confirmed_empty"), result.stdout());
        }

        RunResult invalidPath = run(db, "call-path",
                "p.Overloads#pick(java.lang.Boolean)", "p.Overloads#stringSink()");
        assertEquals(2, invalidPath.exitCode(), invalidPath.stdout());
        assertTrue(invalidPath.stdout().contains("SYMBOL_NOT_FOUND"), invalidPath.stdout());
        assertFalse(invalidPath.stdout().contains("\"found\" : true"), invalidPath.stdout());
    }

    private static void write(Path project, String relative, String source) throws Exception {
        Path file = project.resolve("src/main/java").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static RunResult run(Path db, String... args) throws Exception {
        String[] command = java.util.Arrays.copyOf(args, args.length + 2);
        command[args.length] = "--index";
        command[args.length + 1] = db.toString();
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(command));
    }
}
