package com.anatomist.incremental;

import com.anatomist.cli.IndexCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Incremental coverage for the Spring-XML bean graph (T5):
 *   1. edit an xml → bean graph re-extracted, counts reflect the change;
 *   2. edit a Java class referenced by a bean → xml realigned via file_dependencies,
 *      WIRES reconnected;
 *   3. delete the xml → its BEAN nodes and DEFINED_BY/WIRES edges vanish.
 */
class IncrementalSpringXmlIT {

    private Path setupFixtureCopy(Path tmp) throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        Path src = repoRoot.resolve("fixtures/mini-spring-shop");
        Path dst = tmp.resolve("project");
        copyDir(src, dst);
        return dst;
    }

    private String projectSource(Path project) {
        return String.join(File.pathSeparator,
                project.resolve("api/src/main/java").toString(),
                project.resolve("domain/src/main/java").toString(),
                project.resolve("service/src/main/java").toString());
    }

    private int runFull(Path project, Path db) {
        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                project.toString(),
                "--project-source", projectSource(project),
                "--no-classpath",
                "--spring-xml",
                "--output", db.toString());
        return cmd.call();
    }

    private int runIncremental(Path project, Path db) {
        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                project.toString(),
                "--project-source", projectSource(project),
                "--no-classpath",
                "--spring-xml",
                "--incremental",
                "--output", db.toString());
        return cmd.call();
    }

    private Path xmlPath(Path project) {
        return project.resolve("service/src/main/resources/applicationContext.xml");
    }

    @Test
    void editXmlReExtractsBeans(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFull(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(4, xmlBeanCount(st));
        }

        // Remove the orderEventPublisher bean and the property that wires it.
        Path xml = xmlPath(project);
        String body = Files.readString(xml);
        body = body.replace(
                "    <bean id=\"orderEventPublisher\"\n"
                        + "          class=\"com.example.shop.event.OrderEventPublisher\"/>\n\n",
                "");
        body = body.replace(
                "        <property name=\"eventPublisher\" ref=\"orderEventPublisher\"/>\n",
                "");
        Files.writeString(xml, body);

        assertEquals(0, runIncremental(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(3, xmlBeanCount(st),
                    "one bean removed");
            assertEquals(0, scalar(st,
                    "SELECT count(*) FROM nodes WHERE id LIKE 'bean:orderEventPublisher@%'"),
                    "removed bean node gone");
            int wires = scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='WIRES' "
                            + "AND source_id='com.example.shop.service.OrderService'");
            assertEquals(2, wires, "OrderService now wires only 2 collaborators");
        }
    }

    @Test
    void editReferencedJavaRealignsXmlAndReconnectsWires(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFull(project, db));

        // Touch a class referenced by a bean. file_dependencies (xml -> java) should
        // pull the xml into the realign closure; the bean graph is rebuilt and WIRES
        // re-connect to the still-present class node.
        Path osvc = project.resolve(
                "service/src/main/java/com/example/shop/service/OrderService.java");
        String original = Files.readString(osvc);
        Files.writeString(osvc, original + "\n// touched\n");

        assertEquals(0, runIncremental(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(4, xmlBeanCount(st),
                    "bean graph preserved across java edit");
            int wires = scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='WIRES' AND is_external=0 "
                            + "AND source_id='com.example.shop.service.OrderService'");
            assertEquals(3, wires, "WIRES reconnected to rewritten OrderService");
        }
    }

    @Test
    void deleteXmlRemovesBeanGraph(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFull(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertTrue(xmlBeanCount(st) > 0);
            assertTrue(scalar(st, "SELECT count(*) FROM edges WHERE relation='WIRES'") > 0);
        }

        Files.delete(xmlPath(project));
        assertEquals(0, runIncremental(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(0, xmlBeanCount(st),
                    "all XML beans gone after xml delete");
            assertEquals(0, scalar(st, "SELECT count(*) FROM edges WHERE relation='WIRES'"),
                    "all WIRES gone after xml delete");
            assertEquals(0, xmlDefinedByCount(st),
                    "all XML DEFINED_BY gone after xml delete");
        }
    }

    private static int xmlBeanCount(Statement st) throws Exception {
        return scalar(st, "SELECT count(*) FROM nodes WHERE kind='BEAN' AND source_file LIKE '%.xml'");
    }

    private static int xmlDefinedByCount(Statement st) throws Exception {
        return scalar(st, "SELECT count(*) FROM edges WHERE relation='DEFINED_BY' "
                + "AND source_file LIKE '%.xml'");
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static void copyDir(Path src, Path dst) throws Exception {
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(p -> {
                try {
                    Path rel = src.relativize(p);
                    Path target = dst.resolve(rel.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
