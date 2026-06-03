package com.anatomist.extract;

import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the XML→graph mapping. Given parsed beans, the set of node ids already
 * known (Java CLASS nodes from this batch ∪ DB), and the relative xml path, the
 * extractor emits: one BEAN node per bean, a DEFINED_BY edge BEAN→class (internal
 * when the class is known, external otherwise), and CLASS→CLASS WIRES edges for
 * each resolvable bean ref. WIRES is only emitted when the owner bean's class is
 * a known internal node.
 */
class XmlBeanExtractorTest {

    private static final String XML = "service/src/main/resources/applicationContext.xml";

    private static Edge edge(ExtractionResult r, String relation, String src) {
        return r.edges.stream()
                .filter(e -> e.relation.equals(relation) && src.equals(e.sourceId))
                .findFirst().orElseThrow(() ->
                        new AssertionError("no " + relation + " edge from " + src));
    }

    @Test
    void emitsBeanNodeWithStableId() {
        ParsedBean b = new ParsedBean("orderSvc", "com.example.OrderService", 4, List.of());
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(b),
                Set.of("com.example.OrderService"), XML, r);

        Node n = r.nodes.stream().filter(x -> "BEAN".equals(x.kind)).findFirst().orElseThrow();
        assertEquals("bean:orderSvc@" + XML, n.id);
        assertEquals("BEAN", n.kind);
        assertEquals("orderSvc", n.label);
        assertEquals(XML, n.sourceFile);
        assertEquals("L4", n.sourceLocation);
    }

    @Test
    void definedByInternalWhenClassKnown() {
        ParsedBean b = new ParsedBean("svc", "com.example.OrderService", 1, List.of());
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(b),
                Set.of("com.example.OrderService"), XML, r);

        Edge e = edge(r, "DEFINED_BY", "bean:svc@" + XML);
        assertFalse(e.isExternal);
        assertEquals("com.example.OrderService", e.targetId);
        assertNull(e.externalTargetFqn);
    }

    @Test
    void definedByExternalWhenClassUnknown() {
        ParsedBean b = new ParsedBean("ds", "org.apache.commons.dbcp.BasicDataSource", 1, List.of());
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(b), Set.of(), XML, r);

        Edge e = edge(r, "DEFINED_BY", "bean:ds@" + XML);
        assertTrue(e.isExternal);
        assertEquals("org.apache.commons.dbcp.BasicDataSource", e.externalTargetFqn);
        assertNull(e.targetId);
    }

    @Test
    void wiresClassToClassInternal() {
        ParsedBean svc = new ParsedBean("svc", "com.example.OrderService", 1, List.of("repo"));
        ParsedBean repo = new ParsedBean("repo", "com.example.RepoImpl", 2, List.of());
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(svc, repo),
                Set.of("com.example.OrderService", "com.example.RepoImpl"), XML, r);

        Edge w = edge(r, "WIRES", "com.example.OrderService");
        assertFalse(w.isExternal);
        assertEquals("com.example.RepoImpl", w.targetId);
    }

    @Test
    void wiresExternalWhenTargetClassUnknown() {
        ParsedBean svc = new ParsedBean("svc", "com.example.OrderService", 1, List.of("ds"));
        ParsedBean ds = new ParsedBean("ds", "org.third.DataSource", 2, List.of());
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(svc, ds),
                Set.of("com.example.OrderService"), XML, r);

        Edge w = edge(r, "WIRES", "com.example.OrderService");
        assertTrue(w.isExternal);
        assertEquals("org.third.DataSource", w.externalTargetFqn);
    }

    @Test
    void noWiresWhenOwnerClassUnknown() {
        // Owner bean's class is third-party → no internal CLASS source node to anchor WIRES.
        ParsedBean svc = new ParsedBean("svc", "org.third.Svc", 1, List.of("repo"));
        ParsedBean repo = new ParsedBean("repo", "com.example.RepoImpl", 2, List.of());
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(svc, repo),
                Set.of("com.example.RepoImpl"), XML, r);

        assertTrue(r.edges.stream().noneMatch(e -> "WIRES".equals(e.relation)),
                "owner class unknown → no WIRES");
    }

    @Test
    void noWiresWhenRefBeanUnresolvable() {
        // ref points to a bean name not declared anywhere → cannot map to a class.
        ParsedBean svc = new ParsedBean("svc", "com.example.OrderService", 1, List.of("ghost"));
        ExtractionResult r = new ExtractionResult();
        new XmlBeanExtractor().extract(List.of(svc),
                Set.of("com.example.OrderService"), XML, r);

        assertTrue(r.edges.stream().noneMatch(e -> "WIRES".equals(e.relation)));
        // DEFINED_BY still emitted for the bean itself.
        assertTrue(r.edges.stream().anyMatch(e -> "DEFINED_BY".equals(e.relation)));
    }
}
