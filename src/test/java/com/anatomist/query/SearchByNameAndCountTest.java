package com.anatomist.query;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2: precise simple-name search ({@code --name}), unbounded counting ({@code --count}),
 * and recursive implementors. Mirrors the imerchantsettle pitfalls: FTS matches the
 * package path (inflated "Facade" count) while precise name + recursion give the real answer.
 */
class SearchByNameAndCountTest {

    static Path dbPath;
    static SqliteStore store;

    @BeforeAll
    static void setUp(@TempDir Path tmp) {
        dbPath = tmp.resolve("name.db");
        store = new SqliteStore(dbPath);
        store.initSchema();

        ExtractionResult r = new ExtractionResult();
        // Interfaces and classes whose simple names follow suffix patterns.
        r.nodes.add(type("com.x.facade.api.FooService", "FooService", "INTERFACE"));
        r.nodes.add(type("com.x.facade.api.BarService", "BarService", "INTERFACE"));
        r.nodes.add(type("com.x.facade.dto.FooRequest", "FooRequest", "CLASS")); // lives under .facade but not a Service
        r.nodes.add(type("com.x.plugin.PaySuccessPlugin", "PaySuccessPlugin", "CLASS"));
        r.nodes.add(type("com.x.plugin.RefundPlugin", "RefundPlugin", "CLASS"));

        // Implementor hierarchy: Iface ← AbstractBase ← ConcreteImpl (differently named,
        // mirroring cases where some impls don't end in "Plugin").
        r.nodes.add(type("com.x.plugin.SubscriberPlugin", "SubscriberPlugin", "INTERFACE"));
        r.nodes.add(type("com.x.plugin.AbstractPluginBase", "AbstractPluginBase", "CLASS"));
        r.nodes.add(type("com.x.plugin.WithdrawReverseHandler", "WithdrawReverseHandler", "CLASS"));
        store.write(r);

        ExtractionResult e = new ExtractionResult();
        // AbstractPluginBase implements SubscriberPlugin (direct)
        e.edges.add(impl("com.x.plugin.AbstractPluginBase", "com.x.plugin.SubscriberPlugin", "IMPLEMENTS"));
        // WithdrawReverseHandler extends AbstractPluginBase (transitive)
        e.edges.add(impl("com.x.plugin.WithdrawReverseHandler", "com.x.plugin.AbstractPluginBase", "INHERITS"));
        store.write(e);
    }

    @AfterAll
    static void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void searchByName_suffixMatchesSimpleNameOnly() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> services = q.searchByName("*Service", "INTERFACE", 50);
            assertEquals(2, services.size(), "only the two *Service interfaces");
            assertTrue(services.stream().allMatch(n -> n.label.endsWith("Service")));

            List<NodeRow> plugins = q.searchByName("*Plugin", "CLASS", 50);
            assertEquals(2, plugins.size(), "two *Plugin classes (Abstract/Concrete excluded by name)");
        }
    }

    @Test
    void countByName_independentOfLimit() {
        try (QueryService q = new QueryService(dbPath)) {
            // limit smaller than the true count must not affect the count.
            List<NodeRow> limited = q.searchByName("*Service", "INTERFACE", 1);
            assertEquals(1, limited.size(), "list respects limit");
            assertEquals(2, q.countByName("*Service", "INTERFACE"), "count ignores limit");
        }
    }

    @Test
    void implementorsOf_nonRecursive_directOnly() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> direct = q.implementorsOf("SubscriberPlugin", false);
            assertEquals(1, direct.size(), "only the direct implementor AbstractPluginBase");
            assertEquals("com.x.plugin.AbstractPluginBase", direct.get(0).id);
            assertEquals(1, q.countImplementorsOf("SubscriberPlugin", false));
        }
    }

    @Test
    void implementorsOf_recursive_includesTransitive() {
        try (QueryService q = new QueryService(dbPath)) {
            List<NodeRow> all = q.implementorsOf("SubscriberPlugin", true);
            assertEquals(2, all.size(), "AbstractPluginBase + WithdrawReverseHandler");
            assertTrue(all.stream().anyMatch(n -> "com.x.plugin.WithdrawReverseHandler".equals(n.id)),
                    "transitive concrete impl surfaced");
            assertEquals(2, q.countImplementorsOf("SubscriberPlugin", true));
        }
    }

    private static Node type(String id, String label, String kind) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = kind;
        n.qualifiedName = id; n.sourceFile = label + ".java";
        n.pkg = id.substring(0, id.lastIndexOf('.')); n.scope = "MAIN";
        return n;
    }

    private static Edge impl(String src, String tgt, String relation) {
        Edge e = new Edge();
        e.sourceId = src; e.targetId = tgt; e.relation = relation;
        e.isExternal = false;
        return e;
    }
}
