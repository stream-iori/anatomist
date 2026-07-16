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
