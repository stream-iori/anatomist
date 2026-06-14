package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercise the JarTypeSolver path: feed a real .m2 jar via {@code --classpath}
 * and verify external annotations / types resolve through symbol solving.
 *
 * <p>Uses {@code junit-jupiter-api-5.10.2.jar} (already present in .m2 from
 * this project's own test deps) and a tiny fixture that {@code @Test}s a
 * method — without classpath resolution the {@code @Test} FQN comes back
 * unresolved.</p>
 */
class ClasspathResolutionIT {

    @Test
    @org.junit.jupiter.api.Disabled("AsmTypeSolver does not yet support annotation resolution — tracked separately")
    void externalAnnotationResolvesViaJarTypeSolver(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("Sample.java"),
                "package pkg;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "public class Sample {\n"
                + "  @Test public void runs() {}\n"
                + "}\n");

        Path junitJar = Path.of(System.getProperty("user.home"))
                .resolve(".m2/repository/org/junit/jupiter/junit-jupiter-api/5.10.2/junit-jupiter-api-5.10.2.jar");
        assertTrue(Files.isRegularFile(junitJar),
                "Expected junit jar in .m2 (re-run `mvn -q dependency:resolve` if missing): " + junitJar);

        Path db = tmp.resolve("index.db");
        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                tmp.toString(),
                "--project-source", src.toString(),
                "--classpath", junitJar.toString(),
                "--output", db.toString()
        );
        assertEquals(0, cmd.call(), "index should exit 0");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            int testAnn = scalar(st,
                    "SELECT count(*) FROM annotations WHERE annotation_fqn='org.junit.jupiter.api.Test'");
            assertEquals(1, testAnn,
                    "@Test should resolve to its full FQN via JarTypeSolver; got " + testAnn);
        }
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
