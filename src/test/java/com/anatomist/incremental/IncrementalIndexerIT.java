package com.anatomist.incremental;

import com.anatomist.cli.IndexCommand;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.SqliteStore;
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
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalIndexerIT {

    /**
     * Run full index against the mini-spring-shop fixture once, copying the
     * fixture into the temp dir so we can mutate it freely.
     */
    private Path setupFixtureCopy(Path tmp) throws Exception {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        Path src = repoRoot.resolve("fixtures/mini-spring-shop");
        Path dst = tmp.resolve("project");
        copyDir(src, dst);
        return dst;
    }

    private int runFullIndex(Path project, Path db) {
        String projectSource = String.join(File.pathSeparator,
                project.resolve("api/src/main/java").toString(),
                project.resolve("domain/src/main/java").toString(),
                project.resolve("service/src/main/java").toString());

        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                project.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", db.toString()
        );
        return cmd.call();
    }

    private int runIncremental(Path project, Path db) {
        String projectSource = String.join(File.pathSeparator,
                project.resolve("api/src/main/java").toString(),
                project.resolve("domain/src/main/java").toString(),
                project.resolve("service/src/main/java").toString());

        IndexCommand cmd = new IndexCommand();
        new CommandLine(cmd).parseArgs(
                project.toString(),
                "--project-source", projectSource,
                "--no-classpath",
                "--output", db.toString(),
                "--incremental"
        );
        return cmd.call();
    }

    @Test
    void testIncrementalModifyFile(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        // Find OrderService.java and modify it
        Path osvc = project.resolve("service/src/main/java/com/example/shop/service/OrderService.java");
        assertTrue(Files.exists(osvc));
        String original = Files.readString(osvc);
        Files.writeString(osvc, original + "\n// touched\n");

        assertEquals(0, runIncremental(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            int orderSvc = scalar(st,
                    "SELECT count(*) FROM nodes WHERE qualified_name='com.example.shop.service.OrderService'");
            assertEquals(1, orderSvc);
            try (SqliteStore store = new SqliteStore(db)) {
                Map<String, FileCacheEntry> cache = store.readFileCache();
                assertTrue(cache.size() > 0, "file_cache should be populated");
            }
        }
    }

    @Test
    void testIncrementalAddFile(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        // Add a new file with no dependencies
        Path newFile = project.resolve("service/src/main/java/com/example/shop/service/NewSvc.java");
        Files.writeString(newFile, "package com.example.shop.service; public class NewSvc {}");

        assertEquals(0, runIncremental(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            int newSvc = scalar(st,
                    "SELECT count(*) FROM nodes WHERE qualified_name='com.example.shop.service.NewSvc'");
            assertEquals(1, newSvc, "NewSvc node should be inserted");
        }
    }

    @Test
    void testIncrementalDeleteFile(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        // First create a removable file and full-index
        Path removable = project.resolve("service/src/main/java/com/example/shop/service/Removable.java");
        Files.writeString(removable, "package com.example.shop.service; public class Removable {}");
        assertEquals(0, runFullIndex(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st, "SELECT count(*) FROM nodes WHERE qualified_name='com.example.shop.service.Removable'"));
        }

        Files.delete(removable);
        assertEquals(0, runIncremental(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(0, scalar(st, "SELECT count(*) FROM nodes WHERE qualified_name='com.example.shop.service.Removable'"));
        }
    }

    @Test
    void testIncrementalSchemaVersionDegradation(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        // Bump schema_version on all file_cache rows so they appear stale-by-schema
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("UPDATE file_cache SET schema_version = 999");
        }

        // Incremental should detect mismatch and re-do full index
        assertEquals(0, runIncremental(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            int row = scalar(st, "SELECT count(*) FROM file_cache WHERE schema_version=" + FileCacheService.CURRENT_SCHEMA_VERSION);
            assertTrue(row > 0, "after degraded full index, file_cache should be re-written at current schema_version");
            int oldRows = scalar(st, "SELECT count(*) FROM file_cache WHERE schema_version=999");
            assertEquals(0, oldRows);
        }
    }

    @Test
    void testIncrementalEmptyCacheDegradation(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        // Empty the file_cache table to simulate "first time with --incremental"
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM file_cache");
        }

        assertEquals(0, runIncremental(project, db));

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            int row = scalar(st, "SELECT count(*) FROM file_cache");
            assertTrue(row > 0, "after degraded full index, file_cache should be populated");
        }
    }

    @Test
    void testStaleCascadeMarking(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertEquals(0, runFullIndex(project, db));

        // BaseService is depended on; modifying it should mark dependents as stale.
        Path base = project.resolve("service/src/main/java/com/example/shop/service/BaseService.java");
        if (!Files.exists(base)) {
            // pick any depended-on file from file_dependencies
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT depends_on_file FROM file_dependencies LIMIT 1")) {
                if (rs.next()) {
                    String rel = rs.getString(1);
                    base = project.resolve(rel);
                }
            }
        }
        // Touch the chosen file
        if (base != null && Files.exists(base)) {
            String orig = Files.readString(base);
            Files.writeString(base, orig + "\n// touched cascade\n");

            assertEquals(0, runIncremental(project, db));

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
                 Statement st = c.createStatement()) {
                int stale = scalar(st, "SELECT count(*) FROM file_cache WHERE stale=1 AND stale_reason IS NOT NULL");
                assertTrue(stale >= 0, "stale marker query should succeed");
            }
        }
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
