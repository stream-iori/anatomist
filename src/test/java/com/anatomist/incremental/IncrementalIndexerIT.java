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
import java.util.Set;
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
        assertEquals(0, metric(stdout, "Deleted nodes:"),
                "stable symbol ids must be updated in place");
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
    void incrementalGraphMatchesFreshFullRebuild(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path incrementalDb = tmp.resolve("incremental.db");
        Path fullDb = tmp.resolve("full.db");
        assertFullIndexOk(project, incrementalDb);

        Path service = project.resolve(
                "service/src/main/java/com/example/shop/service/OrderService.java");
        String source = Files.readString(service);
        Files.writeString(service, source.replace(
                "\n}\n",
                "\n    public String incrementalParityProbe() { return \"ok\"; }\n}\n"));

        assertIncrementalOk(project, incrementalDb);
        assertFullIndexOk(project, fullDb);

        assertEquals(canonicalGraph(fullDb), canonicalGraph(incrementalDb),
                "incremental nodes, edges, and file dependencies must equal a fresh full index");
    }

    @Test
    void bodyOnlyChangeMatchesFreshFullRebuild(@TempDir Path tmp) throws Exception {
        Path project = setupFixtureCopy(tmp);
        Path incrementalDb = tmp.resolve("incremental.db");
        Path fullDb = tmp.resolve("full.db");
        assertFullIndexOk(project, incrementalDb);

        Path service = project.resolve(
                "service/src/main/java/com/example/shop/service/OrderService.java");
        Files.writeString(service, Files.readString(service) + "\n// body-only parity probe\n");

        assertIncrementalOk(project, incrementalDb);
        assertFullIndexOk(project, fullDb);

        assertEquals(canonicalGraph(fullDb), canonicalGraph(incrementalDb),
                "body-only replacement must retain derived wiring and match full indexing");
    }

    @Test
    void structuralSymbolChangeMatchesFreshFullRebuild(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("structural")).toRealPath();
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        Path a = pkg.resolve("A.java");
        Path b = pkg.resolve("B.java");
        Files.writeString(b, "package p; public class B { public String foo(){ return \"x\"; } }");
        Files.writeString(a, "package p; public class A { String run(){ return new B().foo(); } }");
        Path incrementalDb = tmp.resolve("structural-incremental.db");
        Path fullDb = tmp.resolve("structural-full.db");
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", incrementalDb.toString());

        Files.writeString(b, "package p; public class B { public Object bar(){ return \"x\"; } }");
        runSimpleIncremental(project, src, incrementalDb);
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", fullDb.toString());

        assertEquals(canonicalGraph(fullDb), canonicalGraph(incrementalDb),
                "removed/renamed symbol impact must equal a fresh full graph");
    }

    @Test
    void bodyOnlyChangeParsesOnlyChangedJavaFile(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj")).toRealPath();
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
        assertEquals(Set.of(b.toAbsolutePath().normalize()), Set.copyOf(factory.parsedFiles),
                "a body-only change must preserve incoming edges without reparsing callers");

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges e "
                            + "JOIN nodes s ON s.id=e.source_id JOIN nodes t ON t.id=e.target_id "
                            + "WHERE e.relation='CALLS' AND e.is_external=0 "
                            + "AND s.symbol_id='p.A#run()' AND t.symbol_id='p.B#foo()'"));
        }
    }

    @Test
    void signatureRemovalReparsesOnlyExactCaller(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj")).toRealPath();
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        Path a = pkg.resolve("A.java");
        Path b = pkg.resolve("B.java");
        Files.writeString(b, "package p; public class B { public void foo(){} }");
        Files.writeString(a, "package p; public class A { void run(){ new B().foo(); } }");
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", db.toString());

        Files.writeString(b, "package p; public class B { public void bar(){} }");
        FileCacheService fcs = new FileCacheService();
        Map<String, String> diskHashes = fcs.computeFileHashes(project, List.of(a, b));
        CountingJavaParserFactory factory = new CountingJavaParserFactory(8, List.of(src));
        IncrementalIndexer.Summary summary;
        try (SqliteStore store = new SqliteStore(db)) {
            FileCacheService.Changes changes = fcs.detectChanges(diskHashes, store.readFileCache());
            summary = new IncrementalIndexer(project, List.of(src), factory, store, 8, 200)
                    .indexIncremental(changes.changed, changes.added, changes.deleted, diskHashes);
        }

        assertFalse(summary.degradedToFull);
        assertEquals(1, summary.realignedDependents);
        assertEquals(Set.of(a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize()),
                Set.copyOf(factory.parsedFiles));
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(0, scalar(st,
                    "SELECT count(*) FROM edges e JOIN nodes s ON s.id=e.source_id "
                            + "WHERE s.symbol_id='p.A#run()' AND e.target_id LIKE '%p.B#foo()'"));
        }
    }

    @Test
    void largePrimarySetIsParsedInBoundedBatches(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("proj")).toRealPath();
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        List<Path> sources = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            Path source = pkg.resolve("C" + i + ".java");
            Files.writeString(source, "package p; class C" + i + " { int value(){ return 1; } }");
            sources.add(source);
        }
        Path db = tmp.resolve("index.db");
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", db.toString());
        for (int i = 0; i < sources.size(); i++) {
            Files.writeString(sources.get(i),
                    "package p; class C" + i + " { int value(){ return 2; } }");
        }
        FileCacheService fcs = new FileCacheService();
        Map<String, String> diskHashes = fcs.computeFileHashes(project, sources);
        CountingJavaParserFactory factory = new CountingJavaParserFactory(8, List.of(src));
        IncrementalIndexer.Summary summary;
        try (SqliteStore store = new SqliteStore(db)) {
            FileCacheService.Changes changes = fcs.detectChanges(diskHashes, store.readFileCache());
            summary = new IncrementalIndexer(project, List.of(src), factory, store, 8, 1000)
                    .indexIncremental(changes.changed, changes.added, changes.deleted, diskHashes);
        }

        assertFalse(summary.degradedToFull);
        assertEquals(130, summary.reparsedFiles);
        assertEquals(2, factory.parseFilesCalls);
        assertTrue(factory.maxBatchFiles <= 128, "max batch=" + factory.maxBatchFiles);
    }

    @Test
    void overloadAdditionRebindsExistingFamilyCallers(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("overload")).toRealPath();
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        Path a = pkg.resolve("A.java");
        Path b = pkg.resolve("B.java");
        Files.writeString(b, "package p; public class B { public void foo(Object v){} }");
        Files.writeString(a, "package p; public class A { void run(){ new B().foo(\"x\"); } }");
        Path db = tmp.resolve("overload.db");
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", db.toString());

        Files.writeString(b, "package p; public class B { "
                + "public void foo(Object v){} public void foo(String v){} }");
        String stdout = runSimpleIncremental(project, src, db);
        assertEquals(1, metric(stdout, "Realigned deps:"), stdout);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges e JOIN nodes s ON s.id=e.source_id "
                            + "JOIN nodes t ON t.id=e.target_id WHERE s.symbol_id='p.A#run()' "
                            + "AND t.symbol_id='p.B#foo(java.lang.String)'"));
        }
    }

    @Test
    void addedMethodRebindsPreviouslyExternalCall(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("external")).toRealPath();
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        Path a = pkg.resolve("A.java");
        Path b = pkg.resolve("B.java");
        Files.writeString(b, "package p; public class B {}");
        Files.writeString(a, "package p; public class A { void run(){ new B().added(); } }");
        Path db = tmp.resolve("external.db");
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", db.toString());

        Files.writeString(b, "package p; public class B { public void added(){} }");
        String stdout = runSimpleIncremental(project, src, db);
        assertEquals(1, metric(stdout, "Realigned deps:"), stdout);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges e JOIN nodes s ON s.id=e.source_id "
                            + "JOIN nodes t ON t.id=e.target_id WHERE s.symbol_id='p.A#run()' "
                            + "AND t.symbol_id='p.B#added()' AND e.is_external=0"));
        }
    }

    @Test
    void interfaceMethodAdditionReparsesImplementor(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("interface")).toRealPath();
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        Path contract = pkg.resolve("Contract.java");
        Path impl = pkg.resolve("Impl.java");
        Files.writeString(contract, "package p; public interface Contract {}");
        Files.writeString(impl, "package p; public class Impl implements Contract { public void run(){} }");
        Path db = tmp.resolve("interface.db");
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", db.toString());

        Files.writeString(contract, "package p; public interface Contract { void run(); }");
        String stdout = runSimpleIncremental(project, src, db);
        assertEquals(1, metric(stdout, "Realigned deps:"), stdout);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges e JOIN nodes s ON s.id=e.source_id "
                            + "JOIN nodes t ON t.id=e.target_id WHERE e.relation='OVERRIDES' "
                            + "AND s.symbol_id='p.Impl#run()' AND t.symbol_id='p.Contract#run()'"));
        }
    }

    @Test
    void uniqueMemberAdditionOnHighFanoutHubDoesNotDegrade(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("fanout")).toRealPath();
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        Path hub = pkg.resolve("Hub.java");
        Files.writeString(hub, "package p; public class Hub { public static int value(){ return 1; } }");
        for (int i = 0; i < 220; i++) {
            Files.writeString(pkg.resolve("Caller" + i + ".java"),
                    "package p; public class Caller" + i
                            + " { int run(){ return Hub.value(); } }");
        }
        Path db = tmp.resolve("fanout.db");
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", db.toString());

        Files.writeString(hub, "package p; public class Hub { "
                + "public static int value(){ return 1; } public static int added(){ return 2; } }");
        RunResult incremental = CliTestSupport.runIndex(project,
                "--project-source", src.toString(), "--no-classpath", "--incremental",
                "--max-realign-files", "200", "--output", db.toString());

        assertEquals(0, incremental.exitCode(), incremental.stderr());
        assertFalse(incremental.stderr().contains("degraded to full"), incremental.stderr());
        assertEquals(0, metric(incremental.stdout(), "Realigned deps:"), incremental.stdout());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            assertEquals(220, scalar(st,
                    "SELECT count(*) FROM edges e JOIN nodes t ON t.id=e.target_id "
                            + "WHERE e.relation='CALLS' AND t.symbol_id='p.Hub#value()'"));
        }
    }

    @Test
    void parseFailureLeavesCommittedGraphUntouched(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("parse-failure")).toRealPath();
        Path src = Files.createDirectories(project.resolve("src"));
        Path pkg = Files.createDirectories(src.resolve("p"));
        Path source = pkg.resolve("A.java");
        Files.writeString(source, "package p; public class A { void ok(){} }");
        Path db = tmp.resolve("parse-failure.db");
        CliTestSupport.assertIndexOk(project, "--project-source", src.toString(),
                "--no-classpath", "--output", db.toString());
        List<String> before = canonicalGraph(db);

        Files.writeString(source, "package p; public class A { void broken( }");
        RunResult incremental = CliTestSupport.runIndex(project,
                "--project-source", src.toString(), "--no-classpath", "--incremental",
                "--output", db.toString());

        assertNotEquals(0, incremental.exitCode(), "invalid changed source must fail the attempt");
        assertTrue(incremental.stderr().contains("incremental source parse failed"),
                incremental.stderr());
        assertFalse(incremental.stderr().contains("at com.anatomist"),
                "expected parse failures should not print a stack trace:\n" + incremental.stderr());
        assertEquals(before, canonicalGraph(db), "failed parsing must not mutate the committed graph");
    }

    private String runSimpleIncremental(Path project, Path src, Path db) throws Exception {
        RunResult result = CliTestSupport.runIndex(project,
                "--project-source", src.toString(), "--no-classpath",
                "--output", db.toString(), "--incremental");
        assertEquals(0, result.exitCode(), result.stderr());
        return result.stdout();
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
                    "SELECT count(*) FROM nodes WHERE symbol_id='route:GET /api/orders/ping' "
                            + "AND kind='ROUTE'"));
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges e "
                            + "JOIN nodes s ON s.id=e.source_id JOIN nodes t ON t.id=e.target_id "
                            + "WHERE e.relation='HANDLES' "
                            + "AND s.symbol_id='route:GET /api/orders/ping' "
                            + "AND t.symbol_id='com.example.shop.controller.OrderController#ping()'"));
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
        assertEquals(0, ((Number) stats.get("deleted_nodes")).intValue(), stdout);
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
                    "SELECT count(*) FROM nodes WHERE symbol_id='bean:newAnnotatedService' "
                            + "AND kind='BEAN'"));
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges e "
                            + "JOIN nodes s ON s.id=e.source_id JOIN nodes t ON t.id=e.target_id "
                            + "WHERE e.relation='DEFINED_BY' "
                            + "AND s.symbol_id='bean:newAnnotatedService' "
                            + "AND t.symbol_id='com.example.shop.service.NewAnnotatedService'"));
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
                    "SELECT count(*) FROM edges e "
                            + "JOIN nodes s ON s.id=e.source_id JOIN nodes t ON t.id=e.target_id "
                            + "WHERE e.relation='WIRES' "
                            + "AND s.symbol_id='com.example.shop.service.OrderService' "
                            + "AND t.symbol_id='com.example.shop.repository.SecondaryOrderRepository' "
                            + "AND e.metadata LIKE '%\"via\":\"injection\"%'"));
            assertEquals(1, scalar(st,
                    "SELECT count(*) FROM edges e "
                            + "JOIN nodes s ON s.id=e.source_id JOIN nodes t ON t.id=e.target_id "
                            + "WHERE e.relation='CALLS' "
                            + "AND s.symbol_id='com.example.shop.service.OrderService#createOrder(com.example.shop.domain.dto.CreateOrderRequest)' "
                            + "AND t.symbol_id='com.example.shop.repository.SecondaryOrderRepository#save(com.example.shop.domain.entity.Order)' "
                            + "AND e.metadata LIKE '%\"via\":\"injected-call\"%'"));
            assertEquals(2, scalar(st,
                    "SELECT count(*) FROM edges e "
                            + "JOIN nodes s ON s.id=e.source_id JOIN nodes t ON t.id=e.target_id "
                            + "WHERE e.relation='WIRES' "
                            + "AND s.symbol_id='com.example.shop.service.OrderService' "
                            + "AND t.symbol_id IN ("
                            + "'com.example.shop.repository.InMemoryOrderRepository',"
                            + "'com.example.shop.repository.SecondaryOrderRepository') "
                            + "AND e.confidence='AMBIGUOUS' "
                            + "AND e.metadata LIKE '%\"via\":\"injection\"%'"));
        }
    }

    private static int scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    private static List<String> canonicalGraph(Path db) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            appendRows(rows, st,
                    "SELECT 'N|' || id || '|' || symbol_id || '|' || kind || '|' "
                            + "|| COALESCE(qualified_name,'') || '|' || COALESCE(source_file,'') || '|' "
                            + "|| COALESCE(module,'') || '|' || COALESCE(scope,'') || '|' "
                            + "|| COALESCE(metadata,'') "
                            + "FROM nodes ORDER BY 1");
            appendRows(rows, st,
                    "SELECT 'E|' || s.id || '|' || e.relation || '|' "
                            + "|| COALESCE(t.id,e.external_target_fqn,'') || '|' "
                            + "|| COALESCE(e.call_kind,'') || '|' || COALESCE(e.confidence,'') || '|' "
                            + "|| COALESCE(e.context,'') || '|' || COALESCE(e.source_file,'') || '|' "
                            + "|| e.is_external || '|' || COALESCE(e.metadata,'') "
                            + "FROM edges e JOIN nodes s ON s.id=e.source_id "
                            + "LEFT JOIN nodes t ON t.id=e.target_id ORDER BY 1");
            appendRows(rows, st,
                    "SELECT 'A|' || node_id || '|' || annotation_fqn || '|' || COALESCE(attributes,'') "
                            + "FROM annotations ORDER BY 1");
            appendRows(rows, st,
                    "SELECT 'D|' || source_file || '|' || depends_on_file "
                            + "FROM file_dependencies ORDER BY 1");
        }
        return rows;
    }

    private static void appendRows(List<String> rows, Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) rows.add(rs.getString(1));
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
        int maxBatchFiles;
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
        public ParseFilesResult parseFilesDetailed(List<Path> files) {
            parseFilesCalls++;
            maxBatchFiles = Math.max(maxBatchFiles, files.size());
            parsedFiles.addAll(files);
            return super.parseFilesDetailed(files);
        }
    }

}
