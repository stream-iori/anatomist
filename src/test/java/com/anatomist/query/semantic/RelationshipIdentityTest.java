package com.anatomist.query.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipIdentityTest {
    @Test
    void ignoresFieldAndTargetOrderButChangesSemanticEndpointsAndProvider() {
        String first = RelationshipIdentity.of("call_site", RelationshipIdentity.fields(
                "provider", "java-core", "language", "java", "caller", "p.A#run()",
                "targets", List.of("p.B#b()", "p.C#c()"), "dispatch_kind", "virtual"));
        String reordered = RelationshipIdentity.of("call_site", RelationshipIdentity.fields(
                "dispatch_kind", "virtual", "targets", List.of("p.C#c()", "p.B#b()"),
                "caller", "p.A#run()", "language", "java", "provider", "java-core"));
        assertEquals(first, reordered);
        assertTrue(first.matches("rel:sha256:[0-9a-f]{64}"));
        assertNotEquals(first, RelationshipIdentity.of("call_site", RelationshipIdentity.fields(
                "provider", "java-core", "language", "java", "caller", "p.A#changed()",
                "targets", List.of("p.B#b()", "p.C#c()"), "dispatch_kind", "virtual")));
        assertNotEquals(first, RelationshipIdentity.of("call_site", RelationshipIdentity.fields(
                "provider", "rust-core", "language", "rust", "caller", "p.A#run()",
                "targets", List.of("p.B#b()", "p.C#c()"), "dispatch_kind", "virtual")));
    }

    @Test
    void duplicateTargetsDoNotChangeRelationshipIdentity() {
        String unique = RelationshipIdentity.of("call_site",
                RelationshipIdentity.fields("targets", List.of("a", "b")));
        String duplicate = RelationshipIdentity.of("call_site",
                RelationshipIdentity.fields("targets", List.of("b", "a", "a")));
        assertEquals(unique, duplicate);
    }
}
