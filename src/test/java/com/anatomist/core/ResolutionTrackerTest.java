package com.anatomist.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolutionTrackerTest {

    @Test
    void usesImportsToClassifyThirdPartyFailures(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java"));
        Path source = Files.createDirectories(root.resolve("com/acme")).resolve("A.java");
        Files.writeString(source, """
                package com.acme;
                import org.springframework.http.ResponseEntity;
                class A {}
                """);
        CompilationUnit unit = new JavaParser().parse(source).getResult().orElseThrow();
        ResolutionTracker tracker = new ResolutionTracker(tmp, List.of(root));
        tracker.enterFile(unit);
        tracker.enterPhase("full_extract_reference");

        tracker.record(new UnsolvedSymbolException("ResponseEntity"));

        IndexDiagnostic diagnostic = tracker.snapshot(true).diagnostics().get(0);
        assertEquals("THIRDPARTY_SYMBOL_MISSING", diagnostic.code());
        assertEquals("info", diagnostic.severity());
    }

    @Test
    void groupsFailuresByFilePhaseReasonAndScope(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java"));
        Path source = Files.createDirectories(root.resolve("p")).resolve("A.java");
        Files.writeString(source, "package p; class A {}");
        CompilationUnit unit = new JavaParser().parse(source).getResult().orElseThrow();
        ResolutionTracker tracker = new ResolutionTracker(tmp, List.of(root));
        tracker.enterFile(unit);
        tracker.enterPhase("full_extract_reference");

        tracker.record(new UnsolvedSymbolException("MissingType"));
        tracker.record(new UnsolvedSymbolException("MissingType"));

        ResolutionSummary summary = tracker.snapshot(false);
        assertEquals(2, summary.unresolved());
        assertEquals(1, summary.diagnostics().size());
        IndexDiagnostic diagnostic = summary.diagnostics().get(0);
        assertEquals("INTERNAL_SYMBOL_MISSING", diagnostic.code());
        assertEquals("src/main/java/p/A.java", diagnostic.sourceFile());
        assertEquals("MAIN", diagnostic.scope());
        assertEquals(2, diagnostic.count());
        assertTrue(diagnostic.phase().contains("reference"));
    }
}
