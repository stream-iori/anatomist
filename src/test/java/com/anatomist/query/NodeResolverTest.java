package com.anatomist.query;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the FQN-resolution behavior extracted out of QueryService into
 * {@link NodeResolver}: type/method/field id resolution across exact-id,
 * qualified-name, short-class, and bare-label forms.
 */
class NodeResolverTest {

    private Connection conn;
    private NodeResolver resolver;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        Path db = tmp.resolve("nr.db");
        try (SqliteStore store = new SqliteStore(db)) {
            store.initSchema();
            ExtractionResult r = new ExtractionResult();
            r.nodes.add(type("com.x.Order", "Order"));
            r.nodes.add(method("com.x.Order#create(java.lang.String)", "create", "com.x.Order#create"));
            r.nodes.add(method("com.x.Order#create()", "create", "com.x.Order#create"));
            r.nodes.add(field("com.x.Order#total", "total"));
            r.nodes.add(type("com.y.OrderService", "OrderService"));
            store.write(r);
        }
        conn = DriverManager.getConnection("jdbc:sqlite:" + db);
        resolver = new NodeResolver(conn);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    void resolveTypeByFqnThenLabel() {
        assertEquals(List.of("com.x.Order"), resolver.resolveTypeIds("com.x.Order"));
        assertEquals(List.of("com.x.Order"), resolver.resolveTypeIds("Order"));
        assertTrue(resolver.resolveTypeIds("Nope").isEmpty());
    }

    @Test
    void resolveTypeStripsMethodPart() {
        assertEquals(List.of("com.x.Order"), resolver.resolveTypeIds("com.x.Order#create"));
    }

    @Test
    void resolveMethodExactIdAndQualifiedName() {
        assertEquals(List.of("com.x.Order#create(java.lang.String)"),
                resolver.resolveMethodIds("com.x.Order#create(java.lang.String)"));
        // qualified_name match returns both overloads
        List<String> all = resolver.resolveMethodIds("com.x.Order#create");
        assertEquals(2, all.size());
        assertTrue(all.contains("com.x.Order#create()"));
    }

    @Test
    void resolveMethodShortClassAndDotForm() {
        assertTrue(resolver.resolveMethodIds("Order#create").contains("com.x.Order#create()"));
        assertTrue(resolver.resolveMethodIds("com.x.Order.create").contains("com.x.Order#create()"));
    }

    @Test
    void resolveFieldForms() {
        assertEquals(List.of("com.x.Order#total"), resolver.resolveFieldIds("com.x.Order#total"));
        assertEquals(List.of("com.x.Order#total"), resolver.resolveFieldIds("com.x.Order.total"));
        assertEquals(List.of("com.x.Order#total"), resolver.resolveFieldIds("Order.total"));
        assertEquals(List.of("com.x.Order#total"), resolver.resolveFieldIds("total"));
    }

    @Test
    void resolveNodeRowPrefersMethodWhenHashPresent() {
        NodeRow n = resolver.resolveNodeRow("com.x.Order#create(java.lang.String)");
        assertNotNull(n);
        assertEquals("com.x.Order#create(java.lang.String)", n.id);
        assertEquals("METHOD", n.kind);
    }

    @Test
    void resolveNodeRowFallsBackToType() {
        NodeRow n = resolver.resolveNodeRow("Order");
        assertNotNull(n);
        assertEquals("com.x.Order", n.id);
        assertEquals("CLASS", n.kind);
    }

    private static Node type(String id, String label) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "CLASS"; n.qualifiedName = id;
        n.pkg = id.substring(0, id.lastIndexOf('.')); n.sourceFile = label + ".java";
        n.sourceLocation = "L1"; n.scope = "MAIN";
        return n;
    }

    private static Node method(String id, String label, String qn) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "METHOD"; n.qualifiedName = qn;
        n.pkg = "com.x"; n.sourceFile = "Order.java"; n.sourceLocation = "L2"; n.scope = "MAIN";
        return n;
    }

    private static Node field(String id, String label) {
        Node n = new Node();
        n.id = id; n.label = label; n.kind = "FIELD"; n.qualifiedName = id;
        n.pkg = "com.x"; n.sourceFile = "Order.java"; n.sourceLocation = "L3"; n.scope = "MAIN";
        return n;
    }
}
