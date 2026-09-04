package com.anatomist.framework;

import com.anatomist.core.JavaParserFactory;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionLifecycleTest {

    @Test
    void rejectsDuplicateIdsAndFingerprintTracksVersion() {
        JavaUnitAnalyzer first = analyzer("same", "1");
        JavaUnitAnalyzer duplicate = analyzer("same", "2");
        assertThrows(IllegalArgumentException.class, () -> PreparedExtensions.prepare(
                new AnalyzerRegistry(List.of(), List.of(first, duplicate), List.of())));

        String v1 = PreparedExtensions.prepare(new AnalyzerRegistry(
                List.of(), List.of(analyzer("a", "1")), List.of())).fingerprint();
        String v2 = PreparedExtensions.prepare(new AnalyzerRegistry(
                List.of(), List.of(analyzer("a", "2")), List.of())).fingerprint();
        assertNotEquals(v1, v2);

        String callV1 = PreparedExtensions.prepare(new AnalyzerRegistry(
                List.of(), List.of(callEvidence("call", "1")), List.of(), List.of(), List.of()))
                .fingerprint();
        String callV2 = PreparedExtensions.prepare(new AnalyzerRegistry(
                List.of(), List.of(callEvidence("call", "2")), List.of(), List.of(), List.of()))
                .fingerprint();
        assertNotEquals(callV1, callV2);
    }

    @Test
    void astProcessorAugmentsMainAndSourceSolverAndIsIdempotent(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path a = src.resolve("p/A.java");
        Path b = src.resolve("p/B.java");
        Files.createDirectories(a.getParent());
        Files.writeString(a, "package p; public class A {}");
        Files.writeString(b, "package p; public class B { void use() { new A().generated(); } }");

        AstModelExtension generatedMethod = new AstModelExtension() {
            @Override public String id() { return "test-model"; }
            @Override public void augment(CompilationUnit unit) {
                unit.findAll(ClassOrInterfaceDeclaration.class).forEach(type -> {
                    if (type.getMethodsByName("generated").isEmpty()) {
                        type.addMethod("generated").setPublic(true);
                    }
                });
            }
        };
        PreparedExtensions prepared = PreparedExtensions.prepare(new AnalyzerRegistry(
                List.of(generatedMethod), List.of(), List.of()));
        JavaParserFactory factory = new JavaParserFactory(25, List.of(), List.of(src), true,
                (Path) null, prepared.processorSuppliers());

        List<CompilationUnit> units = factory.parseFiles(List.of(a, b));
        CompilationUnit aUnit = units.stream().filter(unit -> unit.getStorage()
                .map(storage -> storage.getPath().equals(a)).orElse(false)).findFirst().orElseThrow();
        generatedMethod.augment(aUnit);
        assertEquals(1, aUnit.findAll(ClassOrInterfaceDeclaration.class).getFirst()
                .getMethodsByName("generated").size());

        MethodCallExpr call = units.stream().flatMap(unit -> unit.findAll(MethodCallExpr.class).stream())
                .filter(expr -> expr.getNameAsString().equals("generated")).findFirst().orElseThrow();
        assertEquals("p.A.generated()", call.resolve().getQualifiedSignature());
    }

    @Test
    void everyMatchingProjectAnalyzerSharesResourceAndOwnsItsFacts() {
        ProjectResource resource = new ProjectResource(Path.of("app.xml"), "app.xml", "test");
        ProjectResourceAnalyzer a = projectAnalyzer("producer-a", "A");
        ProjectResourceAnalyzer b = projectAnalyzer("producer-b", "B");
        PreparedExtensions prepared = PreparedExtensions.prepare(new AnalyzerRegistry(
                List.of(), List.of(), List.of(a, b)));
        ExtractionResult result = new ExtractionResult();
        AnalysisContext context = new AnalysisContext(Path.of("."), List.of(), null, null, true);

        new ProjectAnalysisRunner().run(prepared, context, List.of(resource),
                new DefaultProjectFactView(Set.of("source", "target"), Map.of()), result);

        assertEquals(List.of("producer-a", "producer-b"),
                result.edges.stream().map(edge -> edge.producerId).toList());
        assertTrue(result.edges.stream().allMatch(edge -> "app.xml".equals(edge.sourceFile)));
    }

    @Test
    void astFailureRollsBackThatExtensionAndContinues(@TempDir Path tmp) throws Exception {
        AstModelExtension broken = new AstModelExtension() {
            @Override public String id() { return "broken-model"; }
            @Override public void augment(CompilationUnit unit) {
                unit.getClassByName("A").orElseThrow().addMethod("mustNotLeak");
                throw new IllegalStateException("boom");
            }
        };
        AstModelExtension healthy = new AstModelExtension() {
            @Override public String id() { return "healthy-model"; }
            @Override public void augment(CompilationUnit unit) {
                unit.getClassByName("A").orElseThrow().addMethod("survives");
            }
        };
        PreparedExtensions prepared = PreparedExtensions.prepare(new AnalyzerRegistry(
                List.of(broken, healthy), List.of(), List.of()));
        Path source = tmp.resolve("A.java");
        Files.writeString(source, "class A {}");
        JavaParserFactory factory = new JavaParserFactory(25, List.of(), List.of(tmp), true,
                (Path) null, prepared.processorSuppliers());

        CompilationUnit unit = factory.parseFiles(List.of(source)).getFirst();

        var type = unit.getClassByName("A").orElseThrow();
        assertTrue(type.getMethodsByName("mustNotLeak").isEmpty());
        assertEquals(1, type.getMethodsByName("survives").size());
        assertTrue(ExtensionAstData.diagnostics(unit).stream()
                .anyMatch(value -> value.code().equals("EXTENSION_AST_FAILED")
                        && value.symbol().equals("broken-model")));
    }

    @Test
    void resourceProviderFailureIsIsolatedAndInventoryIsShared() {
        ProjectResourceProvider broken = new ProjectResourceProvider() {
            @Override public String id() { return "broken-resource"; }
            @Override public boolean enabled(AnalysisContext context) { return true; }
            @Override public Set<String> kinds() { return Set.of("test"); }
            @Override public boolean mayContain(Path path) { return true; }
            @Override public List<ProjectResource> discover(AnalysisContext context,
                                                             com.anatomist.core.ProjectScanner scanner) {
                throw new IllegalStateException("boom");
            }
        };
        ProjectResourceProvider healthy = new ProjectResourceProvider() {
            @Override public String id() { return "healthy-resource"; }
            @Override public boolean enabled(AnalysisContext context) { return true; }
            @Override public Set<String> kinds() { return Set.of("test"); }
            @Override public boolean mayContain(Path path) { return true; }
            @Override public List<ProjectResource> discover(AnalysisContext context,
                                                             com.anatomist.core.ProjectScanner scanner) {
                return List.of(new ProjectResource(Path.of("app.xml"), "app.xml", "test"));
            }
        };
        PreparedExtensions prepared = PreparedExtensions.prepare(new AnalyzerRegistry(
                List.of(), List.of(), List.of(broken, healthy), List.of()));
        ExtensionReport report = new ExtensionReport();

        List<ProjectResource> resources = new ProjectResourceDiscovery().discover(
                prepared, new AnalysisContext(Path.of("."), List.of(), null, null, true),
                new com.anatomist.core.ProjectScanner(), report);

        assertEquals(List.of("app.xml"), resources.stream().map(ProjectResource::sourceFile).toList());
        assertTrue(report.diagnostics().stream()
                .anyMatch(value -> value.code().equals("EXTENSION_RESOURCE_DISCOVERY_FAILED")
                        && value.symbol().equals("broken-resource")));
    }

    private static JavaUnitAnalyzer analyzer(String id, String version) {
        return new JavaUnitAnalyzer() {
            @Override public String id() { return id; }
            @Override public String version() { return version; }
            @Override public void analyze(CompilationUnit unit, ExtractionResult result) {}
        };
    }

    private static CallSiteEvidenceProvider callEvidence(String id, String version) {
        return new CallSiteEvidenceProvider() {
            @Override public String id() { return id; }
            @Override public String version() { return version; }
            @Override public java.util.Optional<Evidence> observe(
                    MethodCallExpr call, FallbackCallSite site) {
                return java.util.Optional.empty();
            }
        };
    }

    private static ProjectResourceAnalyzer projectAnalyzer(String id, String label) {
        return new ProjectResourceAnalyzer() {
            @Override public String id() { return id; }
            @Override public boolean enabled(AnalysisContext context) { return true; }
            @Override public ResourceSelector selector() { return ResourceSelector.kind("test"); }
            @Override public void analyze(AnalysisContext context, List<ProjectResource> resources,
                                          ProjectFactView facts, ExtractionResult result) {
                Edge edge = Edge.call("source", "target", label, null);
                edge.sourceFile = resources.getFirst().sourceFile();
                result.edges.add(edge);
            }
        };
    }
}
