package com.anatomist.core;

import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WiringResolverTest {

    @Test
    void apply_addsWiresAndInjectedCallWhenImplementationIsUnique() {
        ExtractionResult r = new ExtractionResult();
        r.edges.add(edge("pkg.Owner", "pkg.Service", "INJECTS", null));
        r.edges.add(edge("pkg.ServiceImpl", "pkg.Service", "IMPLEMENTS", null));
        r.edges.add(edge("pkg.ServiceImpl#doIt()", "pkg.Service#doIt()", "OVERRIDES", null));
        Edge call = edge("pkg.Owner#run()", "pkg.Service#doIt()", "CALLS", "INTERFACE");
        r.edges.add(call);

        int added = new WiringResolver().apply(r);

        assertEquals(2, added);
        assertTrue(r.edges.stream().anyMatch(e -> "WIRES".equals(e.relation)
                && "pkg.Owner".equals(e.sourceId)
                && "pkg.ServiceImpl".equals(e.targetId)
                && "INFERRED".equals(e.confidence)));
        assertTrue(r.edges.stream().anyMatch(e -> "CALLS".equals(e.relation)
                && "pkg.Owner#run()".equals(e.sourceId)
                && "pkg.ServiceImpl#doIt()".equals(e.targetId)
                && "INFERRED".equals(e.confidence)
                && e.metadata != null
                && e.metadata.contains("\"via\":\"injected-call\"")));
    }

    private static Edge edge(String source, String target, String relation, String callKind) {
        Edge e = new Edge();
        e.sourceId = source;
        e.targetId = target;
        e.relation = relation;
        e.callKind = callKind;
        e.confidence = "EXTRACTED";
        e.isExternal = false;
        e.sourceLocation = "L1";
        return e;
    }
}
