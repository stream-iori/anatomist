package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceIdentityIT {

    @Test
    void sameSymbolCanExistInMainAndTestAndQueriesDefaultToMain(@TempDir Path project) throws Exception {
        Path main = project.resolve("src/main/java/p/Duplicate.java");
        Path test = project.resolve("src/test/java/p/Duplicate.java");
        Files.createDirectories(main.getParent());
        Files.createDirectories(test.getParent());
        Files.writeString(main, "package p; public class Duplicate { void mainOnly() {} }");
        Files.writeString(test, "package p; public class Duplicate { void testOnly() {} }");
        Path db = project.resolve("index.db");

        int rc = new CommandLine(new IndexCommand()).execute(
                project.toString(),
                "--source-root", "app@MAIN=src/main/java",
                "--source-root", "app@TEST=src/test/java",
                "--no-classpath", "--output", db.toString());
        assertEquals(0, rc);

        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db);
             var ps = c.prepareStatement(
                     "SELECT id,module,scope FROM nodes WHERE symbol_id='p.Duplicate' ORDER BY scope");
             var rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("app::MAIN::p.Duplicate", rs.getString("id"));
            assertEquals("app", rs.getString("module"));
            assertEquals("MAIN", rs.getString("scope"));
            assertTrue(rs.next());
            assertEquals("app::TEST::p.Duplicate", rs.getString("id"));
            assertEquals("TEST", rs.getString("scope"));
            assertFalse(rs.next());
        }

        try (QueryService q = new QueryService(db)) {
            List<NodeRow> defaults = q.searchByName("Duplicate", "CLASS", 10);
            assertEquals(1, defaults.size());
            assertEquals("MAIN", defaults.get(0).scope);

            q.selectNodes("app", "TEST");
            List<NodeRow> tests = q.searchByName("Duplicate", "CLASS", 10);
            assertEquals(1, tests.size());
            assertEquals("TEST", tests.get(0).scope);
        }
    }
}
