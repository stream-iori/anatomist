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
}
