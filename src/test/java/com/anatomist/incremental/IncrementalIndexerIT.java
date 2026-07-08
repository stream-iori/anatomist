package com.anatomist.incremental;

import com.anatomist.core.JavaParserFactory;
import com.anatomist.json.Json;
import com.anatomist.model.FileCacheEntry;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SqliteStore;
import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalIndexerIT {

    /**
     * Run full index against the mini-spring-shop fixture once, copying the
     * fixture into the temp dir so we can mutate it freely.
     */
    private Path setupFixtureCopy(Path tmp) throws Exception {
        return CliTestSupport.copyMiniSpringFixture(tmp);
    }

    private void assertFullIndexOk(Path project, Path db) throws Exception {
        CliTestSupport.assertIndexOk(project,
                "--project-source", CliTestSupport.miniSpringProjectSource(project),
                "--no-classpath",
                "--output", db.toString());
    }

    private void assertIncrementalOk(Path project, Path db) throws Exception {
        CliTestSupport.assertIndexOk(project,
                "--project-source", CliTestSupport.miniSpringProjectSource(project),
                "--no-classpath",
                "--output", db.toString(),
                "--incremental");
    }

    private String runIncrementalOutput(Path project, Path db, String... extraArgs) throws Exception {
        ArrayList<String> args = new ArrayList<>(List.of(
                "--project-source", CliTestSupport.miniSpringProjectSource(project),
                "--no-classpath",
                "--output", db.toString(),
                "--incremental"));
        args.addAll(List.of(extraArgs));
        RunResult result = CliTestSupport.runIndex(project, args.toArray(String[]::new));
        assertEquals(0, result.exitCode(),
                "incremental index failed\nstdout:\n" + result.stdout() + "\nstderr:\n" + result.stderr());
        return result.stdout();
    }

    @Test
    void testIncrementalModifyFile(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertFullIndexOk(project, db);

        // Find OrderService.java and modify it
        Path osvc = project.resolve("service/src/main/java/com/example/shop/service/OrderService.java");
        assertTrue(Files.exists(osvc));
        String original = Files.readString(osvc);
        Files.writeString(osvc, original + "\n// touched\n");

        String stdout = runIncrementalOutput(project, db);
        assertPositive(stdout, "Deleted nodes:");
        assertPositive(stdout, "Deleted edges:");
        assertPositive(stdout, "Written nodes:");
        assertPositive(stdout, "Written edges:");
        assertFalse(stdout.contains("New nodes:"), stdout);
        assertFalse(stdout.contains("New edges:"), stdout);

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
    void incrementalParsesOnlyRealignJavaFiles(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("proj");
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        Path a = pkg.resolve("A.java");
        Path b = pkg.resolve("B.java");
        Files.writeString(b, "package p; public class B { public void foo(){} }");
        Files.writeString(a, "package p; public class A { void run(){ new B().foo(); } }");
        Path db = tmp.resolve("index.db");

        CliTestSupport.assertIndexOk(project,
                "--project-source", src.toString(),
                "--no-classpath",
                "--output", db.toString());

        Files.writeString(b, "package p; public class B { public void foo(){} /* touched */ }");
        FileCacheService fcs = new FileCacheService();
        List<Path> sourceFiles = List.of(a, b);
        Map<String, String> diskHashes = fcs.computeFileHashes(project, sourceFiles);

        CountingJavaParserFactory factory = new CountingJavaParserFactory(8, List.of(src));
        IncrementalIndexer.Summary summary;
        try (SqliteStore store = new SqliteStore(db)) {
            FileCacheService.Changes changes = fcs.detectChanges(diskHashes, store.readFileCache());
            IncrementalIndexer indexer = new IncrementalIndexer(
                    project, List.of(src), factory, store, 8, 200);
            summary = indexer.indexIncremental(changes.changed, changes.added, changes.deleted, diskHashes);
        }

        assertFalse(summary.degradedToFull);
        assertEquals(0, factory.parseAllCalls, "incremental Java reparse must not parse every source root");
        assertEquals(1, factory.parseFilesCalls);
        assertEquals(List.of(
                b.toAbsolutePath().normalize(),
                a.toAbsolutePath().normalize()
        ), factory.parsedFiles, "changed file and realigned dependent should be parsed");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='CALLS' AND is_external=0 "
                            + "AND source_id='p.A#run()' AND target_id='p.B#foo()'"));
        }
    }

    @Test
    void testIncrementalAddFile(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertFullIndexOk(project, db);

        // Add a new file with no dependencies
        Path newFile = project.resolve("service/src/main/java/com/example/shop/service/NewSvc.java");
        Files.writeString(newFile, "package com.example.shop.service; public class NewSvc {}");

        String stdout = runIncrementalOutput(project, db);
        assertEquals(0, metric(stdout, "Deleted nodes:"));
        // Generated wiring edges are globally replaced during each incremental pass.
        // Adding a file can therefore report deleted edges even when no source nodes
        // were removed.
        metric(stdout, "Deleted edges:");
        assertPositive(stdout, "Written nodes:");
        assertFalse(stdout.contains("New nodes:"), stdout);

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
        assertFullIndexOk(project, db);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st, "SELECT count(*) FROM nodes WHERE qualified_name='com.example.shop.service.Removable'"));
        }

        Files.delete(removable);
        String stdout = runIncrementalOutput(project, db);
        assertPositive(stdout, "Deleted nodes:");
        assertEquals(0, metric(stdout, "Written nodes:"));
        assertFalse(stdout.contains("New nodes:"), stdout);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(0, scalar(st, "SELECT count(*) FROM nodes WHERE qualified_name='com.example.shop.service.Removable'"));
        }
    }

    @Test
    void testIncrementalSchemaVersionDegradation(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertFullIndexOk(project, db);

        // Bump schema_version on all file_cache rows so they appear stale-by-schema
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("UPDATE file_cache SET schema_version = 999");
        }

        // Incremental should detect mismatch and re-do full index
        assertIncrementalOk(project, db);

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
        assertFullIndexOk(project, db);

        // Empty the file_cache table to simulate "first time with --incremental"
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM file_cache");
        }

        assertIncrementalOk(project, db);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            int row = scalar(st, "SELECT count(*) FROM file_cache");
            assertTrue(row > 0, "after degraded full index, file_cache should be populated");
        }
    }

    @Test
    void incrementalIndexesNewSpringRoute(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertFullIndexOk(project, db);

        Path controller = project.resolve(
                "api/src/main/java/com/example/shop/controller/OrderController.java");
        String original = Files.readString(controller);
        Files.writeString(controller, original.replace(
                "\n}\n",
                "\n    @GetMapping(\"/ping\")\n"
                        + "    public ResponseEntity<String> ping() {\n"
                        + "        return ResponseEntity.ok(\"pong\");\n"
                        + "    }\n"
                        + "}\n"));

        assertIncrementalOk(project, db);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM nodes WHERE id='route:GET /api/orders/ping' "
                            + "AND kind='ROUTE'"));
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='HANDLES' "
                            + "AND source_id='route:GET /api/orders/ping' "
                            + "AND target_id='com.example.shop.controller.OrderController#ping()'"));
        }
    }

    @Test
    void incrementalJsonReportsDeletedAndWrittenRowsOnly(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertFullIndexOk(project, db);

        Path osvc = project.resolve("service/src/main/java/com/example/shop/service/OrderService.java");
        Files.writeString(osvc, Files.readString(osvc) + "\n// json touched\n");

        String stdout = runIncrementalOutput(project, db, "--format", "json");
        assertFalse(stdout.contains("\"new_nodes\""), stdout);
        assertFalse(stdout.contains("\"new_edges\""), stdout);

        Map<?, ?> json = (Map<?, ?>) Json.parseTree(stdout);
        Map<?, ?> stats = (Map<?, ?>) json.get("stats");
        assertTrue(((Number) stats.get("deleted_nodes")).intValue() > 0, stdout);
        assertTrue(((Number) stats.get("deleted_edges")).intValue() > 0, stdout);
        assertTrue(((Number) stats.get("written_nodes")).intValue() > 0, stdout);
        assertTrue(((Number) stats.get("written_edges")).intValue() > 0, stdout);
    }

    @Test
    void incrementalIndexesNewSpringBean(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertFullIndexOk(project, db);

        Path bean = project.resolve(
                "service/src/main/java/com/example/shop/service/NewAnnotatedService.java");
        Files.writeString(bean,
                "package com.example.shop.service;\n"
                        + "import org.springframework.stereotype.Service;\n"
                        + "@Service\n"
                        + "public class NewAnnotatedService {}\n");

        assertIncrementalOk(project, db);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM nodes WHERE id='bean:newAnnotatedService' "
                            + "AND kind='BEAN'"));
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='DEFINED_BY' "
                            + "AND source_id='bean:newAnnotatedService' "
                            + "AND target_id='com.example.shop.service.NewAnnotatedService'"));
        }
    }

    @Test
    void incrementalRebuildsWiringFromExistingDbEdges(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path db = tmp.resolve("index.db");
        assertFullIndexOk(project, db);

        Path repo = project.resolve(
                "service/src/main/java/com/example/shop/repository/SecondaryOrderRepository.java");
        Files.writeString(repo,
                "package com.example.shop.repository;\n"
                        + "import com.example.shop.domain.entity.Order;\n"
                        + "import org.springframework.stereotype.Repository;\n"
                        + "import java.util.Optional;\n"
                        + "@Repository\n"
                        + "public class SecondaryOrderRepository implements OrderRepository {\n"
                        + "  public Order save(Order order) { return order; }\n"
                        + "  public Optional<Order> findById(String id) { return Optional.empty(); }\n"
                        + "  public void deleteAll() {}\n"
                        + "}\n");

        assertIncrementalOk(project, db);

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='WIRES' "
                            + "AND source_id='com.example.shop.service.OrderService' "
                            + "AND target_id='com.example.shop.repository.SecondaryOrderRepository' "
                            + "AND metadata LIKE '%\"via\":\"injection\"%'"));
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='CALLS' "
                            + "AND source_id='com.example.shop.service.OrderService#createOrder(com.example.shop.domain.dto.CreateOrderRequest)' "
                            + "AND target_id='com.example.shop.repository.SecondaryOrderRepository#save(com.example.shop.domain.entity.Order)' "
                            + "AND metadata LIKE '%\"via\":\"injected-call\"%'"));
            assertEquals(2, scalar(st,
                    "SELECT count(*) FROM edges WHERE relation='WIRES' "
                            + "AND source_id='com.example.shop.service.OrderService' "
                            + "AND target_id IN ("
                            + "'com.example.shop.repository.InMemoryOrderRepository',"
                            + "'com.example.shop.repository.SecondaryOrderRepository') "
                            + "AND confidence='AMBIGUOUS' "
                            + "AND metadata LIKE '%\"via\":\"injection\"%'"));
        }
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static void assertPositive(String stdout, String label) {
        assertTrue(metric(stdout, label) > 0, "Expected positive " + label + " in:\n" + stdout);
    }

    private static int metric(String stdout, String label) {
        Pattern pattern = Pattern.compile(Pattern.quote(label) + "\\s*(\\d+)");
        Matcher matcher = pattern.matcher(stdout);
        assertTrue(matcher.find(), "Missing metric " + label + " in:\n" + stdout);
        return Integer.parseInt(matcher.group(1));
    }

    private static final class CountingJavaParserFactory extends JavaParserFactory {
        int parseAllCalls;
        int parseFilesCalls;
        final List<Path> parsedFiles = new ArrayList<>();

        CountingJavaParserFactory(int javaVersion, List<Path> sourcePaths) {
            super(javaVersion, List.of(), sourcePaths, true);
        }

        @Override
        public void parseAll(BiConsumer<Path, CompilationUnit> consumer) {
            parseAllCalls++;
            throw new AssertionError("incremental reparse should use parseFiles");
        }

        @Override
        public List<CompilationUnit> parseFiles(List<Path> files) {
            parseFilesCalls++;
            parsedFiles.addAll(files);
            return super.parseFiles(files);
        }
    }

}
