package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.anatomist.store.IndexLock;

import static org.junit.jupiter.api.Assertions.*;

class SemanticPipelineIT {
    @TempDir Path tmp;
    Path project;
    Path db;

    @BeforeEach
    void indexProject() throws Exception {
        project = CliTestSupport.createSimpleMavenProject(tmp, false);
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p;
                @Deprecated class A {
                    int state;
                    void pick(String value) {}
                    void pick(Integer value) {}
                    void run() { new B().go(); new B().stop(); pick(null); }
                    void guarded(boolean enabled) { if (enabled) { state++; new B().go(); } }
                }
                class B { void go() {} void stop() {} }
                """);
        Files.writeString(project.resolve("src/main/java/p/Types.java"), """
                package p;
                interface Gateway { default void execute() {} static void helper() {} }
                abstract class AbstractGateway implements Gateway { public abstract void execute(); }
                final class ConcreteGateway extends AbstractGateway { public final void execute() {} }
                class GatewayCaller {
                    void virtual(Gateway gateway) { gateway.execute(); }
                    void exact() { ConcreteGateway value = new ConcreteGateway(); value.execute(); Gateway.helper(); }
                }
                sealed interface Mode permits FastMode {}
                final class FastMode implements Mode {}
                """);
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/applicationContext.xml"), """
                <beans xmlns='http://www.springframework.org/schema/beans'>
                  <bean id='gatewayTemplate' abstract='true'/>
                  <bean id='gateway' parent='gatewayTemplate' class='p.ConcreteGateway'/>
                </beans>
                """);
        db = tmp.resolve("semantic.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "17",
                "--spring-xml", "--output", db.toString(), "--format", "json");
    }

    @Test
    void typeRuntimeCallableAndDispatchSemanticsStaySeparate() throws Exception {
        RunResult type = run("", "resolve", "p.Gateway", "--kind", "type", "--exact", "--unique");
        RunResult relations = run(type.stdout(), "type-relations", "--direction", "incoming");
        assertEquals(0, relations.exitCode(), relations.stderr());
        assertRelationshipIds(relations.stdout());
        assertTrue(relations.stdout().contains("CONFORMS_TO"), relations.stdout());

        RunResult implementations = run(type.stdout(), "runtime-implementations", "--instantiability", "yes");
        assertEquals(0, implementations.exitCode(), implementations.stderr());
        assertTrue(implementations.stdout().contains("p.ConcreteGateway"), implementations.stdout());
        assertFalse(implementations.stdout().contains("\"qualified_name\":\"p.AbstractGateway\""), implementations.stdout());
        assertEquals("unknown", lastRecord(implementations.stdout()).get("coverage"));

        RunResult method = run("", "resolve", "p.Gateway#execute()", "--kind", "callable", "--exact", "--unique");
        RunResult callableRelations = run(method.stdout(), "callable-relations", "--direction", "incoming", "--transitive");
        assertEquals(0, callableRelations.exitCode(), callableRelations.stderr());
        assertRelationshipIds(callableRelations.stdout());
        assertTrue(callableRelations.stdout().contains("IMPLEMENTS_CONTRACT"), callableRelations.stdout());

        RunResult caller = run("", "resolve", "p.GatewayCaller#virtual(p.Gateway)", "--kind", "callable", "--exact", "--unique");
        RunResult calls = run(caller.stdout(), "calls");
        RunResult dispatch = run(calls.stdout(), "dispatch");
        assertEquals(0, dispatch.exitCode(), dispatch.stderr());
        assertRelationshipIds(dispatch.stdout());
        assertTrue(dispatch.stdout().contains("\"algorithm\":\"CHA\""), dispatch.stdout());
        assertTrue(dispatch.stdout().contains("p.ConcreteGateway#execute()"), dispatch.stdout());
        assertFalse(dispatch.stdout().contains("\"target_name\":\"p.AbstractGateway#execute\""), dispatch.stdout());

        RunResult sealed = run("", "resolve", "p.Mode", "--kind", "type", "--exact", "--unique");
        RunResult permits = run(sealed.stdout(), "type-relations", "--direction", "incoming");
        assertEquals(0, permits.exitCode(), permits.stderr());
        assertTrue(permits.stdout().contains("java.permits"), permits.stdout());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.prepareStatement(
                     "SELECT value FROM project_meta WHERE key='dropped_dangling_edges'");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("0", rows.getString(1));
        }
    }

    @Test
    void artifactMembersAndBindingsFormCanonicalPipeline() throws Exception {
        RunResult search = run("", "search", "applicationContext", "--kind", "artifact", "--format", "ndjson");
        RunResult artifact = run(search.stdout(), "resolve", "--unique");
        RunResult members = run(artifact.stdout(), "members", "--kind", "component", "--recursive");
        assertEquals(0, members.exitCode(), members.stderr());
        assertTrue(members.stdout().contains("gatewayTemplate"), members.stdout());
        assertTrue(members.stdout().contains("gateway"), members.stdout());

        RunResult type = run("", "resolve", "p.ConcreteGateway", "--kind", "type", "--exact", "--unique");
        RunResult bindings = run(type.stdout(), "bindings", "--direction", "incoming", "--semantic", "realizes");
        assertEquals(0, bindings.exitCode(), bindings.stderr());
        assertRelationshipIds(bindings.stdout());
        assertTrue(bindings.stdout().contains("spring.xml.class"), bindings.stdout());
        assertTrue(bindings.stdout().contains("REALIZES"), bindings.stdout());
    }

    @Test
    void remainingAtomicOperationsAreTypedAndComposable() throws Exception {
        RunResult type = run("", "resolve", "p.A", "--kind", "type", "--exact", "--unique");
        RunResult describe = run(type.stdout(), "describe");
        assertEquals(0, describe.exitCode(), describe.stderr());
        assertTrue(describe.stdout().contains("\"record\":\"declaration\""), describe.stdout());
        RunResult source = run(describe.stdout(), "source");
        assertEquals(0, source.exitCode(), source.stderr());
        assertTrue(source.stdout().contains("source_slice"), source.stdout());

        RunResult annotations = run(type.stdout(), "annotations");
        assertEquals(0, annotations.exitCode(), annotations.stderr());
        assertTrue(annotations.stdout().contains("Deprecated"), annotations.stdout());

        RunResult field = run("", "resolve", "p.A#state", "--kind", "value", "--exact", "--unique");
        RunResult accesses = run(field.stdout(), "accesses", "--mode", "all");
        assertEquals(0, accesses.exitCode(), accesses.stderr());
        assertRelationshipIds(accesses.stdout());
        assertTrue(accesses.stdout().contains("access_site"), accesses.stdout());

        RunResult callable = run("", "resolve", "p.A#guarded(boolean)", "--kind", "callable", "--exact", "--unique");
        RunResult regions = run(callable.stdout(), "regions");
        assertEquals(0, regions.exitCode(), regions.stderr());
        assertTrue(regions.stdout().contains("control_region"), regions.stdout());
        RunResult sites = run(regions.stdout(), "sites-in", "--record", "all");
        assertEquals(0, sites.exitCode(), sites.stderr());
        assertTrue(sites.stdout().contains("call_site"), sites.stdout());
        assertTrue(sites.stdout().contains("access_site"), sites.stdout());

        RunResult start = run("", "resolve", "p.A#run()", "--kind", "callable", "--exact", "--unique");
        RunResult trace = run(start.stdout(), "trace", "--to", "p.B#go()", "--dispatch", "resolved");
        assertEquals(0, trace.exitCode(), trace.stderr());
        assertTrue(trace.stdout().contains("\"record\":\"trace\""), trace.stdout());
    }

    @Test
    void searchResolveCallsSourcePipelineIsFramed() throws Exception {
        RunResult search = run("", "search", "--name", "A", "--kind", "type",
                "--format", "ndjson");
        assertEquals(0, search.exitCode(), search.stderr());
        assertRecords(search.stdout(), "entity_candidate", "evidence");

        RunResult resolve = run(search.stdout(), "resolve", "--unique");
        assertEquals(0, resolve.exitCode(), resolve.stderr());
        assertRecords(resolve.stdout(), "entity", "evidence");

        RunResult callable = run("", "resolve", "p.A#run()", "--kind", "callable",
                "--exact", "--unique");
        RunResult calls = run(callable.stdout(), "calls", "--direction", "outgoing");
        assertEquals(0, calls.exitCode(), calls.stderr());
        List<Map<String, Object>> callRecords = records(calls.stdout());
        assertTrue(callRecords.stream().anyMatch(r -> "call_site".equals(r.get("record"))));
        assertFalse(calls.stdout().contains("OVERRIDES"));
        List<Map<String, Object>> sites = callRecords.stream()
                .filter(r -> "call_site".equals(r.get("record"))).toList();
        assertTrue(sites.size() >= 3, sites.toString());
        assertEquals(sites.size(), sites.stream().map(r -> r.get("id")).distinct().count());
        for (Map<String, Object> site : sites) {
            assertTrue(String.valueOf(site.get("relationship_id"))
                    .matches("rel:sha256:[0-9a-f]{64}"), site.toString());
            Map<?, ?> range = (Map<?, ?>) site.get("source");
            assertNotNull(range.get("start_column"), site.toString());
            assertNotNull(range.get("end_column"), site.toString());
            assertNotNull(site.get("syntax_target"), site.toString());
        }
        assertTrue(sites.stream().anyMatch(site ->
                "ambiguous".equals(site.get("resolution_status"))
                        && ((List<?>) site.get("resolved_targets")).size() == 2), sites.toString());
        assertTrue(sites.stream()
                .filter(site -> String.valueOf(site.get("syntax_target")).startsWith("new B"))
                .allMatch(site -> ((List<?>) site.get("resolved_targets")).size() == 1),
                sites.toString());

        Map<String, Object> ambiguous = sites.stream()
                .filter(site -> "pick".equals(site.get("syntax_target")))
                .filter(site -> "ambiguous".equals(site.get("resolution_status")))
                .findFirst().orElseThrow();
        String oneTarget = String.valueOf(((Map<?, ?>) ((List<?>)
                ambiguous.get("resolved_targets")).getFirst()).get("id"));
        RunResult target = run("", "resolve", oneTarget, "--kind", "callable", "--unique");
        assertEquals(0, target.exitCode(), target.stderr() + " target=" + oneTarget);
        RunResult incoming = run(target.stdout(), "calls", "--direction", "incoming");
        assertEquals(0, incoming.exitCode(), incoming.stderr());
        Map<String, Object> incomingSite = records(incoming.stdout()).stream()
                .filter(record -> "call_site".equals(record.get("record")))
                .findFirst().orElseThrow();
        assertEquals(2, ((List<?>) incomingSite.get("resolved_targets")).size());

        RunResult source = run(calls.stdout(), "source", "--limit", "50");
        assertEquals(0, source.exitCode(), source.stderr());
        assertTrue(source.stdout().contains("source_slice"), source.stdout());
        assertTrue(source.stdout().contains("new B().go()"), source.stdout());
        assertEquals("stream", lastRecord(source.stdout()).get("scope"));
    }

    @Test
    void relationshipIdSurvivesLineMovementButChangesWithEndpoint() throws Exception {
        String selector = "p.A#run()";
        RunResult beforeEntity = run("", "resolve", selector, "--kind", "callable", "--exact", "--unique");
        RunResult beforeCalls = run(beforeEntity.stdout(), "calls");
        Map<String, Object> before = records(beforeCalls.stdout()).stream()
                .filter(row -> "call_site".equals(row.get("record")))
                .filter(row -> String.valueOf(row.get("resolved_targets")).contains("#go"))
                .findFirst().orElseThrow();

        Path source = project.resolve("src/main/java/p/A.java");
        Files.writeString(source, "\n" + Files.readString(source));
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath", "--spring-xml",
                "--java-version", "17", "--output", db.toString());
        RunResult afterEntity = run("", "resolve", selector, "--kind", "callable", "--exact", "--unique");
        RunResult afterCalls = run(afterEntity.stdout(), "calls");
        Map<String, Object> after = records(afterCalls.stdout()).stream()
                .filter(row -> "call_site".equals(row.get("record")))
                .filter(row -> String.valueOf(row.get("resolved_targets")).contains("#go"))
                .findFirst().orElseThrow();
        assertNotEquals(before.get("id"), after.get("id"));
        assertEquals(before.get("relationship_id"), after.get("relationship_id"));

        String changed = Files.readString(source).replace("new B().go()", "new B().stop()");
        Files.writeString(source, changed);
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath", "--spring-xml",
                "--java-version", "17", "--output", db.toString());
        RunResult changedEntity = run("", "resolve", selector, "--kind", "callable", "--exact", "--unique");
        RunResult changedCalls = run(changedEntity.stdout(), "calls");
        Map<String, Object> endpoint = records(changedCalls.stdout()).stream()
                .filter(row -> "call_site".equals(row.get("record")))
                .filter(row -> String.valueOf(row.get("resolved_targets")).contains("#stop"))
                .findFirst().orElseThrow();
        assertNotEquals(before.get("relationship_id"), endpoint.get("relationship_id"));
    }

    private static void assertRelationshipIds(String stream) {
        List<Map<String, Object>> relationships = records(stream).stream()
                .filter(row -> !Set.of("evidence", "stream_header")
                        .contains(row.get("record"))).toList();
        assertFalse(relationships.isEmpty(), stream);
        relationships.forEach(row -> assertTrue(String.valueOf(row.get("relationship_id"))
                .matches("rel:sha256:[0-9a-f]{64}"), row.toString()));
    }

    @Test
    void rejectsUnframedByDefaultAndAllowsExplicitOverride() throws Exception {
        RunResult entity = run("", "resolve", "p.A#run()", "--kind", "callable",
                "--exact", "--unique");
        String dataOnly = entity.stdout().lines()
                .filter(line -> line.contains("\"record\":\"stream_header\"")
                        || line.contains("\"record\":\"entity\""))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        RunResult rejected = run(dataOnly, "calls");
        assertEquals(4, rejected.exitCode(), rejected.stderr());
        assertTrue(rejected.stderr().contains("MISSING_STREAM_EVIDENCE"));
        RunResult accepted = run(dataOnly, "calls", "--accept-unframed");
        assertEquals(0, accepted.exitCode(), accepted.stderr());
        assertEquals("unknown", lastRecord(accepted.stdout()).get("coverage"));
    }

    @Test
    void indexRevisionChangesOnFactsButNotNoopIncremental() throws Exception {
        String first = revision();
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath", "--spring-xml",
                "--output", db.toString(), "--format", "json");
        assertEquals(first, revision());
        Files.writeString(project.resolve("src/main/java/p/A.java"), """
                package p; class A { void run() {} void changed() {} }
                """);
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath", "--spring-xml",
                "--output", db.toString(), "--format", "json");
        assertNotEquals(first, revision());
    }

    @Test
    void callSiteIdsAreStableAcrossFullRebuild() throws Exception {
        List<String> before = callSiteIds();
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--java-version", "17", "--spring-xml",
                "--output", db.toString(), "--format", "json");
        assertEquals(before, callSiteIds());
    }

    @Test
    void emptyFramedPipelineRemainsComposable() throws Exception {
        RunResult search = run("", "search", "--name", "DoesNotExist", "--format", "ndjson");
        RunResult resolve = run(search.stdout(), "resolve", "--unique");
        RunResult calls = run(resolve.stdout(), "calls");
        RunResult source = run(calls.stdout(), "source");
        assertEquals(0, search.exitCode(), search.stderr());
        assertEquals(0, resolve.exitCode(), resolve.stderr());
        assertEquals(0, calls.exitCode(), calls.stderr());
        assertEquals(0, source.exitCode(), source.stderr());
        assertEquals("empty", lastRecord(source.stdout()).get("status"));
        assertEquals("complete", lastRecord(source.stdout()).get("coverage"));
    }

    @Test
    void unsupportedContinueConsumesFramesAndEmitsEvidence() throws Exception {
        RunResult entity = run("", "resolve", "p.A#run()", "--kind", "callable",
                "--exact", "--unique");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE call_site_targets");
            statement.executeUpdate("DROP TABLE call_sites");
        }
        RunResult continued = run(entity.stdout(), "calls", "--on-unsupported", "continue");
        assertEquals(0, continued.exitCode(), continued.stderr());
        assertTrue(continued.stdout().contains("UNSUPPORTED_CAPABILITY"), continued.stdout());
        assertEquals("partial", lastRecord(continued.stdout()).get("status"));
    }

    @Test
    void readLockIsHeldUntilSemanticInputFinishes() throws Exception {
        RunResult entity = run("", "resolve", "p.A#run()", "--kind", "callable",
                "--exact", "--unique");
        String entityLine = entity.stdout().lines()
                .filter(line -> line.contains("\"record\":\"stream_header\"")
                        || line.contains("\"record\":\"entity\""))
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        CountDownLatch waitingForTail = new CountDownLatch(1);
        CountDownLatch releaseTail = new CountDownLatch(1);
        InputStream old = System.in;
        System.setIn(new BlockingTailInputStream(entityLine, waitingForTail, releaseTail));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pipeline = executor.submit(() -> CliTestSupport.capture(() ->
                    new CommandLine(new AnatomistCli()).execute("calls", "--accept-unframed",
                            "--index", db.toString())));
            assertTrue(waitingForTail.await(2, TimeUnit.SECONDS));
            assertThrows(IndexLock.LockTimeoutException.class,
                    () -> IndexLock.forWrite(db, 100));
            releaseTail.countDown();
            assertEquals(0, pipeline.get(3, TimeUnit.SECONDS).exitCode());
        } finally {
            releaseTail.countDown();
            System.setIn(old);
        }
        try (IndexLock ignored = IndexLock.forWrite(db, 1_000)) {
            assertNotNull(ignored);
        }
    }

    private RunResult run(String stdin, String... args) throws Exception {
        String[] command = java.util.Arrays.copyOf(args, args.length + 2);
        command[args.length] = "--index";
        command[args.length + 1] = db.toString();
        InputStream old = System.in;
        try {
            System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(command));
        } finally {
            System.setIn(old);
        }
    }

    private String revision() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.prepareStatement(
                     "SELECT value FROM project_meta WHERE key='index_revision_id'");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private List<String> callSiteIds() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.prepareStatement(
                     "SELECT 'callsite:sha256:'||lower(hex(stable_hash)) "
                             + "FROM call_sites ORDER BY stable_hash");
             var rows = statement.executeQuery()) {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            while (rows.next()) ids.add(rows.getString(1));
            assertFalse(ids.isEmpty());
            return ids;
        }
    }

    private static void assertRecords(String ndjson, String... expected) {
        List<String> actual = records(ndjson).stream()
                .map(record -> String.valueOf(record.get("record"))).toList();
        for (String value : expected) assertTrue(actual.contains(value), actual.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(String ndjson) {
        return ndjson.lines().filter(line -> !line.isBlank())
                .map(Json::parseTree).map(value -> (Map<String, Object>) value).toList();
    }

    private static Map<String, Object> lastRecord(String ndjson) {
        return records(ndjson).getLast();
    }

    private static final class BlockingTailInputStream extends InputStream {
        private final byte[] prefix;
        private final CountDownLatch waiting;
        private final CountDownLatch release;
        private int offset;

        private BlockingTailInputStream(String prefix, CountDownLatch waiting,
                                        CountDownLatch release) {
            this.prefix = prefix.getBytes(StandardCharsets.UTF_8);
            this.waiting = waiting;
            this.release = release;
        }

        @Override public int read() throws IOException {
            if (offset < prefix.length) return prefix[offset++] & 0xff;
            waiting.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("tail timeout");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
            }
            return -1;
        }

        @Override public int read(byte[] target, int start, int length) throws IOException {
            if (offset < prefix.length) {
                int copied = Math.min(length, prefix.length - offset);
                System.arraycopy(prefix, offset, target, start, copied);
                offset += copied;
                return copied;
            }
            return read();
        }
    }
}
