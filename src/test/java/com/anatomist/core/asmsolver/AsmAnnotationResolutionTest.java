package com.anatomist.core.asmsolver;

import com.anatomist.cli.IndexCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests complex annotation resolution scenarios through AsmTypeSolver.
 */
class AsmAnnotationResolutionTest {

    private Path index(Path project, Path src, Path db, String classpath) {
        IndexCommand cmd = new IndexCommand();
        String[] args = classpath != null
                ? new String[]{project.toString(), "--project-source", src.toString(),
                        "--classpath", classpath, "--output", db.toString()}
                : new String[]{project.toString(), "--project-source", src.toString(),
                        "--no-classpath", "--output", db.toString()};
        new CommandLine(cmd).parseArgs(args);
        assertEquals(0, cmd.call(), "index should succeed");
        return db;
    }

    private int annotationCount(Path db, String fqn) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM annotations WHERE annotation_fqn = ?")) {
            ps.setString(1, fqn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String annotationAttrs(Path db, String fqn) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT attributes FROM annotations WHERE annotation_fqn = ? LIMIT 1")) {
            ps.setString(1, fqn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void annotationWithAttributes_resolvesFqnAndAttributes(@TempDir Path tmp) throws Exception {
        // @RequestMapping(value="/api", method=RequestMethod.GET)
        // Needs: FQN resolution + attribute extraction
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path jar = springWebJar();
        if (jar == null) return; // skip if spring-web not in .m2

        Files.writeString(src.resolve("Controller.java"),
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                public class Controller {
                    @RequestMapping(value = "/api", method = RequestMethod.GET)
                    public void handle() {}
                }
                """);

        Path db = index(tmp, src, tmp.resolve("index.db"), jar.toString());
        assertEquals(1, annotationCount(db, "org.springframework.web.bind.annotation.RequestMapping"));
        String attrs = annotationAttrs(db, "org.springframework.web.bind.annotation.RequestMapping");
        assertNotNull(attrs);
        assertTrue(attrs.contains("/api"), "attributes should contain value: " + attrs);
    }

    @Test
    void multipleAnnotationsOnSameElement(@TempDir Path tmp) throws Exception {
        // Multiple annotations from JAR on same method
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path junitJar = junitJar();
        if (junitJar == null) return;

        Files.writeString(src.resolve("Sample.java"),
                """
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.DisplayName;
                public class Sample {
                    @Test
                    @DisplayName("hello")
                    public void myTest() {}
                }
                """);

        Path db = index(tmp, src, tmp.resolve("index.db"), junitJar.toString());
        assertEquals(1, annotationCount(db, "org.junit.jupiter.api.Test"));
        assertEquals(1, annotationCount(db, "org.junit.jupiter.api.DisplayName"));
    }

    @Test
    void annotationOnClass_resolves(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path junitJar = junitJar();
        if (junitJar == null) return;

        Files.writeString(src.resolve("Suite.java"),
                """
                import org.junit.jupiter.api.Disabled;
                @Disabled("not ready")
                public class Suite {}
                """);

        Path db = index(tmp, src, tmp.resolve("index.db"), junitJar.toString());
        assertEquals(1, annotationCount(db, "org.junit.jupiter.api.Disabled"));
        String attrs = annotationAttrs(db, "org.junit.jupiter.api.Disabled");
        assertNotNull(attrs);
        assertTrue(attrs.contains("not ready"), "attributes should capture value: " + attrs);
    }

    @Test
    void repeatableAnnotation(@TempDir Path tmp) throws Exception {
        // Custom repeatable annotation defined in source (not jar)
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("pkg"));
        Files.writeString(src.resolve("pkg/Tag.java"),
                """
                package pkg;
                import java.lang.annotation.*;
                @Repeatable(Tags.class)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Tag { String value(); }
                """);
        Files.writeString(src.resolve("pkg/Tags.java"),
                """
                package pkg;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Tags { Tag[] value(); }
                """);
        Files.writeString(src.resolve("pkg/Service.java"),
                """
                package pkg;
                @Tag("billing")
                @Tag("payment")
                public class Service {}
                """);

        Path db = index(tmp, src, tmp.resolve("index.db"), null);
        // Repeated annotations may be stored individually or as container
        int tagCount = annotationCount(db, "pkg.Tag");
        int tagsCount = annotationCount(db, "pkg.Tags");
        assertTrue(tagCount >= 1 || tagsCount >= 1,
                "repeatable annotation should resolve; Tag=" + tagCount + " Tags=" + tagsCount);
    }

    @Test
    void metaAnnotation_resolvedOnAnnotationDecl(@TempDir Path tmp) throws Exception {
        // @Retention and @Target on a custom annotation should resolve
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("pkg"));
        Files.writeString(src.resolve("pkg/MyAnno.java"),
                """
                package pkg;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface MyAnno {}
                """);
        Files.writeString(src.resolve("pkg/User.java"),
                """
                package pkg;
                public class User {
                    @MyAnno public void doStuff() {}
                }
                """);

        Path db = index(tmp, src, tmp.resolve("index.db"), null);
        assertEquals(1, annotationCount(db, "pkg.MyAnno"), "custom annotation should resolve");
        assertTrue(annotationCount(db, "java.lang.annotation.Retention") >= 1,
                "@Retention meta-annotation should resolve");
        assertTrue(annotationCount(db, "java.lang.annotation.Target") >= 1,
                "@Target meta-annotation should resolve");
    }

    private static Path junitJar() {
        Path p = Path.of(System.getProperty("user.home"),
                ".m2/repository/org/junit/jupiter/junit-jupiter-api/5.10.2/junit-jupiter-api-5.10.2.jar");
        return Files.isRegularFile(p) ? p : null;
    }

    private static Path springWebJar() {
        // Try common versions
        for (String v : new String[]{"6.1.4", "6.1.3", "6.0.9", "5.3.30", "5.3.27"}) {
            Path p = Path.of(System.getProperty("user.home"),
                    ".m2/repository/org/springframework/spring-web/" + v + "/spring-web-" + v + ".jar");
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }
}
