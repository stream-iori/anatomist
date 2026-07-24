package com.anatomist.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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
        assertEquals("THIRDPARTY_SYMBOL_MISSING", diagnostic.code());
        assertEquals("src/main/java/p/A.java", diagnostic.sourceFile());
        assertEquals("MAIN", diagnostic.scope());
        assertEquals(2, diagnostic.count());
        assertTrue(diagnostic.phase().contains("reference"));
    }

    @Test
    void classifiesImportedSourceTypeAsInternalAndSamePrefixSdkAsThirdParty(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java"));
        Path source = Files.createDirectories(root.resolve("com/ipay/app")).resolve("A.java");
        Files.writeString(source, """
                package com.ipay.app;
                import com.ipay.app.LocalType;
                import com.ipay.sdk.RemoteType;
                class A {}
                """);
        Files.writeString(source.getParent().resolve("LocalType.java"),
                "package com.ipay.app; class LocalType {}");
        CompilationUnit unit = new JavaParser().parse(source).getResult().orElseThrow();
        ResolutionTracker tracker = new ResolutionTracker(tmp, List.of(root));
        tracker.enterFile(unit);
        tracker.enterPhase("full_extract_reference");
        tracker.record(new UnsolvedSymbolException("LocalType"));
        tracker.record(new UnsolvedSymbolException("RemoteType"));

        var codes = tracker.snapshot(false).diagnostics().stream()
                .map(IndexDiagnostic::code).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("INTERNAL_SYMBOL_MISSING", "THIRDPARTY_SYMBOL_MISSING"), codes);
    }

    @Test
    void recordsSymbolLineAndJdkDomainBeforeCallPhaseFallback(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java"));
        Path source = Files.createDirectories(root.resolve("p")).resolve("A.java");
        Files.writeString(source, """
                package p;
                import java.nio.file.Files;
                class A { void run(java.nio.file.Path tmp) { Files.walk(tmp); } }
                """);
        CompilationUnit unit = new JavaParser().parse(source).getResult().orElseThrow();
        MethodCallExpr call = unit.findFirst(MethodCallExpr.class).orElseThrow();
        ResolutionTracker tracker = new ResolutionTracker(tmp, List.of(root));
        tracker.enterFile(unit);
        tracker.enterPhase("full_extract_call_graph");

        tracker.record(new UnsolvedSymbolException("Files.walk(tmp)"), call, "walk");

        IndexDiagnostic diagnostic = tracker.snapshot(false).diagnostics().get(0);
        assertEquals("JDK_SYMBOL_MISMATCH", diagnostic.code());
        assertEquals("walk", diagnostic.symbol());
        assertTrue(diagnostic.sample().startsWith("L3:"), diagnostic.sample());
        assertTrue(diagnostic.sample().contains("Files.walk(tmp)"), diagnostic.sample());
    }

    @Test
    void distinctSourceSitesAreSeparateGroups(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java"));
        Path source = Files.createDirectories(root.resolve("p")).resolve("A.java");
        Files.writeString(source, """
                package p;
                class A { void run() {
                  missing();
                  missing();
                } }
                """);
        CompilationUnit unit = new JavaParser().parse(source).getResult().orElseThrow();
        List<MethodCallExpr> calls = unit.findAll(MethodCallExpr.class);
        ResolutionTracker tracker = new ResolutionTracker(tmp, List.of(root));
        tracker.enterFile(unit);
        tracker.enterPhase("full_extract_call_graph");
        calls.forEach(call -> tracker.record(
                new UnsolvedSymbolException("missing()"), call, "missing"));

        ResolutionSummary summary = tracker.snapshot(false);
        assertEquals(2, summary.diagnostics().size());
        assertTrue(summary.diagnostics().stream().noneMatch(d -> d.sample().contains("[unknown]")));
    }

    @Test
    void nestedMemberFailureIsNotMisclassifiedFromOuterJdkCall(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java"));
        Path source = Files.createDirectories(root.resolve("p")).resolve("A.java");
        Files.writeString(source, """
                package p;
                class A { void run(Thread.State state) {
                  java.util.Map.of("state", state.name());
                } }
                """);
        CompilationUnit unit = new JavaParser().parse(source).getResult().orElseThrow();
        MethodCallExpr outer = unit.findAll(MethodCallExpr.class).stream()
                .filter(call -> call.getNameAsString().equals("of")).findFirst().orElseThrow();
        ResolutionTracker tracker = new ResolutionTracker(tmp, List.of(root));
        tracker.enterFile(unit);
        tracker.enterPhase("full_extract_call_graph");

        tracker.record(new UnsolvedSymbolException(
                "Method 'name' cannot be resolved in context state.name()"), outer, "of");

        assertEquals("METHOD_NOT_FOUND", tracker.snapshot(false).diagnostics().get(0).code());
    }

    @Test
    void genericFactoryArgumentsInConstructorFailureAreInferenceDiagnostics(@TempDir Path tmp)
            throws Exception {
        Path root = Files.createDirectories(tmp.resolve("src/main/java"));
        Path source = Files.createDirectories(root.resolve("p")).resolve("A.java");
        Files.writeString(source, """
                package p;
                import java.util.List;
                class A { A(List<String> values) {} static A empty() { return new A(List.of()); } }
                """);
        CompilationUnit unit = new JavaParser().parse(source).getResult().orElseThrow();
        ObjectCreationExpr creation = unit.findFirst(ObjectCreationExpr.class).orElseThrow();
        ResolutionTracker tracker = new ResolutionTracker(tmp, List.of(root));
        tracker.enterFile(unit);
        tracker.enterPhase("full_extract_call_graph");

        tracker.record(new UnsolvedSymbolException(
                "We are unable to find the constructor declaration corresponding to " + creation),
                creation, "A");

        assertEquals("GENERIC_INFERENCE_FAILED",
                tracker.snapshot(false).diagnostics().get(0).code());
    }
}
