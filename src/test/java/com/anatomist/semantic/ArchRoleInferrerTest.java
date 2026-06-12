package com.anatomist.semantic;

import com.anatomist.model.ArchRole;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArchRoleInferrerTest {

    @Test
    void matchL1_controllerMapsToEntry() {
        ArchRole r = ArchRoleInferrer.matchL1("com.x.OrderController",
                Set.of("org.springframework.web.bind.annotation.RestController"));
        assertNotNull(r);
        assertEquals("ENTRY", r.role);
        assertEquals("auto_annotation", r.confidence);
    }

    @Test
    void matchL1_repositoryMapsToRepository() {
        ArchRole r = ArchRoleInferrer.matchL1("com.x.OrderRepo",
                Set.of("org.springframework.stereotype.Repository"));
        assertNotNull(r);
        assertEquals("REPOSITORY", r.role);
    }

    @Test
    void matchL1_configurationMapsToInfrastructure() {
        ArchRole r = ArchRoleInferrer.matchL1("com.x.AppConfig",
                Set.of("org.springframework.context.annotation.Configuration"));
        assertNotNull(r);
        assertEquals("INFRASTRUCTURE", r.role);
    }

    @Test
    void matchL1_entityMapsToDomainModel() {
        ArchRole r = ArchRoleInferrer.matchL1("com.x.Order",
                Set.of("jakarta.persistence.Entity"));
        assertNotNull(r);
        assertEquals("DOMAIN_MODEL", r.role);
    }

    @Test
    void matchL1_serviceReturnsNull() {
        ArchRole r = ArchRoleInferrer.matchL1("com.x.OrderService",
                Set.of("org.springframework.stereotype.Service"));
        assertNull(r);
    }

    @Test
    void matchL1_noMatchReturnsNull() {
        ArchRole r = ArchRoleInferrer.matchL1("com.x.Something",
                Set.of("com.custom.MyAnnotation"));
        assertNull(r);
    }

    @Test
    void extractRoleFromAttributes_simpleValue() {
        assertEquals("APPLICATION", ArchRoleInferrer.extractRoleFromAttributes("{\"value\": \"APPLICATION\"}"));
    }

    @Test
    void extractRoleFromAttributes_qualifiedValue() {
        assertEquals("DOMAIN_SERVICE", ArchRoleInferrer.extractRoleFromAttributes("{\"value\": \"Category.DOMAIN_SERVICE\"}"));
    }

    @Test
    void extractRoleFromAttributes_nullOrEmpty() {
        assertNull(ArchRoleInferrer.extractRoleFromAttributes(null));
        assertNull(ArchRoleInferrer.extractRoleFromAttributes(""));
    }

    @Test
    void inferL1_processesMultipleNodes() {
        ArchRoleInferrer inferrer = new ArchRoleInferrer(null);
        Map<String, Set<String>> annots = Map.of(
                "com.x.OrderController", Set.of("org.springframework.web.bind.annotation.RestController"),
                "com.x.OrderRepo", Set.of("org.springframework.stereotype.Repository"),
                "com.x.OrderService", Set.of("org.springframework.stereotype.Service")
        );
        List<ArchRole> roles = inferrer.inferL1(annots);
        assertEquals(2, roles.size());

        Set<String> resolvedRoles = new java.util.HashSet<>();
        for (ArchRole r : roles) resolvedRoles.add(r.role);
        assertTrue(resolvedRoles.contains("ENTRY"));
        assertTrue(resolvedRoles.contains("REPOSITORY"));
    }
}
