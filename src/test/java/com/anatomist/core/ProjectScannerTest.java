package com.anatomist.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectScannerTest {

    @Test
    void scan_skipsDefaultExcludes(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path tgt = Files.createDirectories(tmp.resolve("target"));
        Files.writeString(src.resolve("Y.java"), "class Y {}");
        Files.writeString(tgt.resolve("X.java"), "class X {}");

        ProjectScanner scanner = new ProjectScanner();
        List<Path> files = scanner.scan(tmp);

        assertEquals(1, files.size(), "expected only Y.java; got " + files);
        assertEquals("Y.java", files.get(0).getFileName().toString());
    }

    @Test
    void scan_appliesCustomExcludes(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Path foo = Files.createDirectories(tmp.resolve("foo"));
        Files.writeString(src.resolve("Y.java"), "class Y {}");
        Files.writeString(foo.resolve("Z.java"), "class Z {}");

        ProjectScanner scanner = new ProjectScanner(Set.of("foo"));
        List<Path> files = scanner.scan(tmp);

        assertEquals(1, files.size(), "expected only Y.java; got " + files);
        assertEquals("Y.java", files.get(0).getFileName().toString());
    }

    @Test
    void scan_ignoresSymlinks(@TempDir Path tmp) throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(src.resolve("Y.java"), "class Y {}");
        try {
            Files.createSymbolicLink(tmp.resolve("loop"), tmp);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            return;
        }

        ProjectScanner scanner = new ProjectScanner();
        List<Path> files = scanner.scan(tmp);

        long count = files.stream()
                .filter(p -> p.getFileName().toString().equals("Y.java"))
                .count();
        assertEquals(1, count, "Y.java seen multiple times via symlink: " + files);
    }

    @Test
    void scan_skipsExplicitGeneratedSourceRootUnderTarget(@TempDir Path tmp) throws Exception {
        Path generated = Files.createDirectories(tmp.resolve("module/target/generated-sources/annotations"));
        Files.writeString(generated.resolve("Generated.java"), "class Generated {}");

        List<Path> files = new ProjectScanner().scan(generated);
        assertTrue(files.isEmpty(), "target generated sources must not be scanned; got " + files);
    }

    @Test
    void scan_trustsResolvedGeneratedRootWithoutOpeningTargetTree(@TempDir Path tmp) throws Exception {
        Path generated = Files.createDirectories(tmp.resolve("module/target/generated-sources/annotations"));
        Path source = generated.resolve("Generated.java");
        Files.writeString(source, "class Generated {}");
        Files.writeString(tmp.resolve("module/target/Noise.java"), "class Noise {}");

        SourceRoot root = new SourceRoot(generated, "module", SourceScope.GENERATED);
        List<Path> files = new ProjectScanner().scanSourceRoots(List.of(root));

        assertEquals(List.of(source), files);
    }

    @Test
    void scanPolicyAppliesProjectRelativeIncludeThenExclude(@TempDir Path tmp) throws Exception {
        Path main = Files.createDirectories(tmp.resolve("src/main/java/p"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        Path keep = main.resolve("Keep.java");
        Files.writeString(keep, "class Keep {}");
        Files.writeString(main.resolve("DropIT.java"), "class DropIT {}");
        Files.writeString(other.resolve("Outside.java"), "class Outside {}");

        ScanPolicy policy = new ScanPolicy(tmp,
                List.of("src/**/*.java"), List.of("**/*IT.java"), Set.of());
        List<Path> files = new ProjectScanner(Set.of(), policy).scan(tmp);

        assertEquals(List.of(keep), files);
    }

    @Test
    void scanPolicyFingerprintIsOrderIndependent(@TempDir Path tmp) {
        SourceRoot root = new SourceRoot(tmp.resolve("src"), "app", SourceScope.MAIN);
        ScanPolicy first = new ScanPolicy(tmp,
                List.of("b/**", "a/**"), List.of("**/B.java", "**/A.java"), Set.of("foo"));
        ScanPolicy second = new ScanPolicy(tmp,
                List.of("a/**", "b/**"), List.of("**/A.java", "**/B.java"), Set.of("foo"));

        assertEquals(first.fingerprint(List.of(root), List.of(SourceScope.MAIN)),
                second.fingerprint(List.of(root), List.of(SourceScope.MAIN)));
    }
}
