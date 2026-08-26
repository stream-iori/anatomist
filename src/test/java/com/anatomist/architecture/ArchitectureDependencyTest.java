package com.anatomist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureDependencyTest {

    @Test
    void coreDoesNotDependOnIncrementalPackage() throws Exception {
        List<String> offenders = importsMatching(
                Path.of("src/main/java/com/anatomist/core"),
                "import com.anatomist.incremental.");

        assertTrue(offenders.isEmpty(),
                "core must not import incremental; move shared cache/index contracts lower: " + offenders);
    }

    @Test
    void coreDoesNotDependOnApplicationOrAdapters() throws Exception {
        Path core = Path.of("src/main/java/com/anatomist/core");
        List<String> forbidden = List.of(
                "import com.anatomist.application.",
                "import com.anatomist.cli.",
                "import com.anatomist.extract.",
                "import com.anatomist.flow.",
                "import com.anatomist.framework.",
                "import com.anatomist.query.",
                "import com.anatomist.store.");
        List<String> offenders = forbidden.stream()
                .flatMap(prefix -> uncheckedImports(core, prefix).stream())
                .distinct().sorted().toList();

        assertTrue(offenders.isEmpty(),
                "core must contain dependency-light indexing primitives only: " + offenders);
    }

    @Test
    void lowerLayersDoNotDependOnApplicationOrCli() throws Exception {
        List<Path> roots = List.of("core", "extract", "flow", "framework", "incremental",
                        "model", "query", "semantic", "store").stream()
                .map(name -> Path.of("src/main/java/com/anatomist", name)).toList();
        List<String> offenders = roots.stream().flatMap(root -> Stream.concat(
                        uncheckedImports(root, "import com.anatomist.application.").stream(),
                        uncheckedImports(root, "import com.anatomist.cli.").stream()))
                .distinct().sorted().toList();

        assertTrue(offenders.isEmpty(),
                "only CLI may depend on the application composition layer: " + offenders);
    }

    @Test
    void frameworkSpiDoesNotWireSpringAndStoreDoesNotDependOnExtractors() throws Exception {
        List<String> offenders = Stream.concat(
                        importsMatching(Path.of("src/main/java/com/anatomist/framework"),
                                "import com.anatomist.framework.spring.").stream(),
                        importsMatching(Path.of("src/main/java/com/anatomist/store"),
                                "import com.anatomist.extract.").stream())
                .sorted().toList();

        assertTrue(offenders.isEmpty(),
                "adapter wiring must point toward framework/model contracts: " + offenders);
    }

    @Test
    void jsonPackageDoesNotDependOnQueryDtos() throws Exception {
        List<String> offenders = importsMatching(
                Path.of("src/main/java/com/anatomist/json"),
                "import com.anatomist.query.");

        assertTrue(offenders.isEmpty(),
                "json must stay generic; DTO codecs belong outside json: " + offenders);
    }

    @Test
    void indexingOrchestratorsDoNotImportJavaSql() throws Exception {
        List<String> offenders = Stream.concat(
                        importsMatching(Path.of("src/main/java/com/anatomist/core"), "import java.sql.").stream(),
                        importsMatching(Path.of("src/main/java/com/anatomist/incremental"), "import java.sql.").stream())
                .sorted()
                .toList();

        assertTrue(offenders.isEmpty(),
                "indexing flow must use store APIs instead of raw java.sql: " + offenders);
    }

    @Test
    void indexingOrchestratorsDoNotContainRawSqlLiterals() throws Exception {
        List<String> offenders = Stream.concat(
                        filesContaining(Path.of("src/main/java/com/anatomist/core"), List.of(
                                "\"SELECT ", "\"INSERT ", "\"UPDATE ", "\"DELETE ")).stream(),
                        filesContaining(Path.of("src/main/java/com/anatomist/incremental"), List.of(
                                "\"SELECT ", "\"INSERT ", "\"UPDATE ", "\"DELETE ")).stream())
                .sorted()
                .toList();

        assertTrue(offenders.isEmpty(),
                "indexing flow SQL belongs in store/query adapters: " + offenders);
    }

    private static List<String> importsMatching(Path root, String importPrefix) throws Exception {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> contains(p, importPrefix))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }

    private static List<String> uncheckedImports(Path root, String importPrefix) {
        try {
            return importsMatching(root, importPrefix);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> filesContaining(Path root, List<String> texts) throws Exception {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> texts.stream().anyMatch(t -> contains(p, t)))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        }
    }

    private static boolean contains(Path path, String text) {
        try {
            return Files.readString(path).contains(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
