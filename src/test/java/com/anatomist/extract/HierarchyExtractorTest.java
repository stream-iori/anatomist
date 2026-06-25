package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserTestSupport;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.github.javaparser.ast.CompilationUnit;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HierarchyExtractorTest {

    private final ExtractionContext ctx = new ExtractionContext(
            Path.of("."), List.of(), new NodeIdGenerator(), null, "MAIN");

    @Test
    void emitsInheritsAndImplements_external() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "import java.util.AbstractList;\n"
                + "import java.io.Serializable;\n"
                + "public class A extends AbstractList<String> implements Serializable {\n"
                + "  public String get(int i) { return null; }\n"
                + "  public int size() { return 0; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new HierarchyExtractor(ctx).extract(cu, r);

        boolean inheritsAbstractList = r.edges.stream()
                .anyMatch(e -> "INHERITS".equals(e.relation)
                        && e.isExternal
                        && "java.util.AbstractList".equals(e.externalTargetFqn));
        boolean implementsSerializable = r.edges.stream()
                .anyMatch(e -> "IMPLEMENTS".equals(e.relation)
                        && e.isExternal
                        && "java.io.Serializable".equals(e.externalTargetFqn));
        assertTrue(inheritsAbstractList, "INHERITS edge missing; got " + r.edges);
        assertTrue(implementsSerializable, "IMPLEMENTS edge missing; got " + r.edges);
    }

    @Test
    void emitsOverridesEdge() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A {\n"
                + "  @Override public String toString() { return \"a\"; }\n"
                + "  @Override public int hashCode() { return 42; }\n"
                + "}\n");
        ExtractionResult r = new ExtractionResult();
        new HierarchyExtractor(ctx).extract(cu, r);

        long overrides = r.edges.stream()
                .filter(e -> "OVERRIDES".equals(e.relation))
                .filter(e -> e.isExternal)
                .filter(e -> e.externalTargetFqn != null
                        && e.externalTargetFqn.startsWith("java.lang.Object#"))
                .count();
        assertTrue(overrides >= 2, "expected ≥2 OVERRIDES to Object; got " + r.edges);
    }

    @Test
    void interfaceExtendsInterface_emitsInherits() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "import java.io.Closeable;\n"
                + "public interface I extends Closeable {}\n");
        ExtractionResult r = new ExtractionResult();
        new HierarchyExtractor(ctx).extract(cu, r);

        boolean inheritsCloseable = r.edges.stream()
                .anyMatch(e -> "INHERITS".equals(e.relation)
                        && e.isExternal
                        && "java.io.Closeable".equals(e.externalTargetFqn));
        assertTrue(inheritsCloseable, "interface→interface INHERITS missing; got " + r.edges);
    }

    @Test
    void unresolvedImplementsList_stillInfersInterfaceOverridesByAst() {
        CompilationUnit cu = JavaParserTestSupport.parse(
                "package pkg;\n"
                + "public class A implements I, MissingBase {\n"
                + "  public void run(Missing m) {}\n"
                + "}\n"
                + "interface I { void run(Missing m); }\n");
        ExtractionResult r = new ExtractionResult();
        new HierarchyExtractor(ctx).extract(cu, r);

        assertTrue(r.edges.stream().anyMatch(e ->
                "OVERRIDES".equals(e.relation)
                        && "INFERRED".equals(e.confidence)
                        && "pkg.A#run(<unresolved>)".equals(e.sourceId)
                        && "pkg.I#run(<unresolved>)".equals(e.targetId)),
                "fallback OVERRIDES edge missing; got " + r.edges);
    }
}
