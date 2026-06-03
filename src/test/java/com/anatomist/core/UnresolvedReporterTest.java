package com.anatomist.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the pure unresolved-symbol categorization extracted out of IndexCommand.
 * These buckets decide whether the remaining "Unresolved" count is
 * classpath-actionable or just generics/inference noise.
 */
class UnresolvedReporterTest {

    private static final Set<String> PKGS = Set.of("com.example.shop", "com.example.shop.api");

    @Test
    void jdkAndJavaxAreJdk() {
        assertEquals("JDK", UnresolvedReporter.categorize("java.util.List", PKGS));
        assertEquals("JDK", UnresolvedReporter.categorize("javax.sql.DataSource", PKGS));
        assertEquals("JDK", UnresolvedReporter.categorize("jakarta.persistence.Entity", PKGS));
    }

    @Test
    void projectPackagesAreInternal() {
        assertEquals("MISSING-TYPE-INTERNAL",
                UnresolvedReporter.categorize("com.example.shop.OrderService", PKGS));
        assertEquals("MISSING-TYPE-INTERNAL",
                UnresolvedReporter.categorize("com.example.shop.api.Dto", PKGS));
    }

    @Test
    void otherDottedTypesAreThirdParty() {
        assertEquals("MISSING-TYPE-THIRDPARTY",
                UnresolvedReporter.categorize("org.apache.commons.lang3.StringUtils", PKGS));
    }

    @Test
    void snippetsAndInferenceAreNotMissingTypes() {
        assertEquals("OTHER-INFERENCE", UnresolvedReporter.categorize("[a, b]", PKGS));
        assertEquals("OTHER-INFERENCE", UnresolvedReporter.categorize("foo(bar)", PKGS));
        assertEquals("OTHER-INFERENCE", UnresolvedReporter.categorize("a b c", PKGS));
        assertEquals("METHOD-NOT-FOUND",
                UnresolvedReporter.categorize("unable to find the method declaration foo", PKGS));
        assertEquals("NOT-A-CLASS", UnresolvedReporter.categorize("T is not a class", PKGS));
        assertEquals("UNQUALIFIED-OR-GENERIC", UnresolvedReporter.categorize("Foo", PKGS));
    }

    @Test
    void prefixOfTakesUpToThreeSegments() {
        assertEquals("org.apache.commons", UnresolvedReporter.prefixOf("org.apache.commons.lang3.StringUtils"));
        assertEquals("com.x", UnresolvedReporter.prefixOf("com.x"));
        assertEquals("Foo", UnresolvedReporter.prefixOf("Foo"));
    }

    @Test
    void printEmptySamplesEmitsNote() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        UnresolvedReporter.print(new PrintStream(buf, true, StandardCharsets.UTF_8),
                new LinkedHashMap<>(), PKGS, 5);
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("no samples captured"), out);
    }

    @Test
    void printBucketsByCategoryAndPrefix() {
        Map<String, Long> samples = new LinkedHashMap<>();
        samples.put("org.apache.commons.lang3.StringUtils", 4L);
        samples.put("java.util.List", 2L);
        samples.put("com.example.shop.OrderService", 1L);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        UnresolvedReporter.print(new PrintStream(buf, true, StandardCharsets.UTF_8),
                samples, PKGS, 20);
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("By category:"), out);
        assertTrue(out.contains("MISSING-TYPE-THIRDPARTY"), out);
        assertTrue(out.contains("org.apache.commons"), out);
        assertTrue(out.contains("of 20 total unresolved"), out);
    }
}
