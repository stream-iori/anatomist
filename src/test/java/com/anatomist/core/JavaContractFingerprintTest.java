package com.anatomist.core;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JavaContractFingerprintTest {

    @Test
    void ignoresCommentsBodiesAndInitializers() {
        String before = """
                package p;
                class A {
                    // old comment
                    int value = 1;
                    A() { value = 2; }
                    int compute(String input) { return input.length(); }
                }
                """;
        String after = """
                package p;
                class A {
                    // new comment
                    int value = 99;
                    A() { throw new IllegalStateException(); }
                    int compute(String input) { int x = 3; return x; }
                }
                """;

        assertEquals(fingerprint(before), fingerprint(after));
    }

    @Test
    void changesWhenTypeContractChanges() {
        String baseline = "package p; class A { int compute(String input) { return 1; } }";

        assertNotEquals(fingerprint(baseline), fingerprint(
                "package p; class A { long compute(String input) { return 1; } }"));
        assertNotEquals(fingerprint(baseline), fingerprint(
                "package p; class A extends Base { int compute(String input) { return 1; } }"));
        assertNotEquals(fingerprint(baseline), fingerprint(
                "package p; @Deprecated class A { int compute(String input) { return 1; } }"));
    }

    private static String fingerprint(String source) {
        return JavaContractFingerprint.of(StaticJavaParser.parse(source));
    }
}
