package com.anatomist.incremental;

import com.anatomist.cli.IndexCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-file realignment: when a depended-on file is reparsed, SQLite's
 * {@code ON DELETE CASCADE} on {@code edges.target_id} removes dependents' edges
 * into it; only realigning (reparsing) those dependents in the same pass
 * recreates them. These ITs pin that behavior, the prune cross-file fix, and the
 * fan-out cap → full degradation.
 */
class IncrementalRealignIT {

    private Path srcRoot(Path project) {
        return project.resolve("src");
    }

    private void write(Path project, String relPath, String content) throws Exception {
        Path f = srcRoot(project).resolve(relPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private int run(Path project, Path db, String... extra) {
        IndexCommand cmd = new IndexCommand();
        String[] base = {
                project.toString(),
                "--project-source", srcRoot(project).toString(),
                "--no-classpath",
                "--output", db.toString()
        };
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        new CommandLine(cmd).parseArgs(args);
        return cmd.call();
    }

    private int internalCallEdges(Path db, String sourceId, String targetId) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            return scalar(st, "SELECT count(*) FROM edges WHERE relation='CALLS' AND is_external=0 "
                    + "AND source_id='" + sourceId + "' AND target_id='" + targetId + "'");
        }
    }

    @Test
    void realignRecreatesDependentEdgeAfterDependedFileReparsed(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        write(project, "p/B.java", "package p; public class B { public void foo(){} }");
        write(project, "p/A.java", "package p; public class A { void run(){ new B().foo(); } }");
        Path db = tmp.resolve("index.db");
        assertEquals(0, run(project, db));
        assertEquals(1, internalCallEdges(db, "p.A#run()", "p.B#foo()"),
                "full index should record A.run -> B.foo");

        // Touch B only (trivial change keeps foo()'s id). A is unchanged on disk.
        write(project, "p/B.java", "package p; public class B { public void foo(){} /* touched */ }");
        assertEquals(0, run(project, db, "--incremental"));

        assertEquals(1, internalCallEdges(db, "p.A#run()", "p.B#foo()"),
                "realign should reparse dependent A and recreate its edge into reparsed B");
    }

    @Test
    void pruneKeepsEdgesIntoStableNonReparsedFiles(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        write(project, "p/C.java", "package p; public class C { public void help(){} }");
        write(project, "p/B.java", "package p; public class B { void foo(){ new C().help(); } }");
        Path db = tmp.resolve("index.db");
        assertEquals(0, run(project, db));
        assertEquals(1, internalCallEdges(db, "p.B#foo()", "p.C#help()"));

        // Change B's body; C does not depend on B, so C is NOT reparsed.
        write(project, "p/B.java", "package p; public class B { void foo(){ new C().help(); int x=1; } }");
        assertEquals(0, run(project, db, "--incremental"));

        assertEquals(1, internalCallEdges(db, "p.B#foo()", "p.C#help()"),
                "B's edge into stable C must survive prune (known set unions DB node ids)");
    }

    @Test
    void fanOutCapDegradesToFull(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        write(project, "p/B.java", "package p; public class B { public void foo(){} }");
        write(project, "p/A.java", "package p; public class A { void run(){ new B().foo(); } }");
        Path db = tmp.resolve("index.db");
        assertEquals(0, run(project, db));

        // Touch B; its dependent A makes the closure size 2 (B + A) > cap 1.
        write(project, "p/B.java", "package p; public class B { public void foo(){} /* touched */ }");

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        int rc;
        try {
            rc = run(project, db, "--incremental", "--max-realign-files", "1");
        } finally {
            System.setErr(origErr);
        }
        assertEquals(0, rc);
        String err = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("incremental degraded to full (realign closure"),
                "expected degrade-to-full message; got: " + err);
        // Full re-index leaves the edge correct.
        assertEquals(1, internalCallEdges(db, "p.A#run()", "p.B#foo()"));
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
