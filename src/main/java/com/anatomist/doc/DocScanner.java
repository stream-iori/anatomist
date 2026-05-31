package com.anatomist.doc;

import com.anatomist.model.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks a project tree and yields a {@link Document} per matched markdown file.
 *
 * Matched: README.md (root or any module root), docs/**\/*.md, **\/ADR-*.md.
 * Excluded: CHANGELOG.md, swagger*.json, openapi*.json (BR-007).
 */
public class DocScanner {

    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern ADR = Pattern.compile("(?i)^ADR-.*\\.md$");

    public List<Document> scan(Path projectRoot) throws IOException {
        List<Document> out = new ArrayList<>();
        if (!Files.isDirectory(projectRoot)) return out;

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.equals(".git") || name.equals("target") || name.equals("build")
                        || name.equals("node_modules") || name.equals(".anatomist")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = projectRoot.relativize(file);
                if (!isMarkdownCandidate(relative)) return FileVisitResult.CONTINUE;
                if (isExcluded(relative)) return FileVisitResult.CONTINUE;

                String content = Files.readString(file, StandardCharsets.UTF_8);
                String stem = stripExt(relative.getFileName().toString());

                Document d = new Document();
                d.path = toForwardSlash(relative);
                d.title = parseTitle(content, stem);
                d.content = content;
                d.docType = detectDocType(relative);
                d.module = detectModule(relative, projectRoot);
                out.add(d);
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static boolean isMarkdownCandidate(Path relative) {
        String name = relative.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) return false;
        if (name.equalsIgnoreCase("README.md")) return true;
        if (ADR.matcher(name).matches()) return true;
        // anything under a "docs" directory (any level)
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (relative.getName(i).toString().equalsIgnoreCase("docs")) return true;
        }
        return false;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String toForwardSlash(Path p) {
        return p.toString().replace('\\', '/');
    }

    static String parseTitle(String content, String pathStem) {
        Matcher m = H1.matcher(content);
        if (m.find()) return m.group(1).trim();
        return pathStem;
    }

    static String detectDocType(Path relative) {
        String name = relative.getFileName().toString();
        if (ADR.matcher(name).matches()) return "ADR";
        if ("README.md".equalsIgnoreCase(name)) return "README";
        if (name.equalsIgnoreCase("CHANGELOG.md")) return "CHANGELOG";
        if (relative.toString().toLowerCase(Locale.ROOT).startsWith("docs/")
                || relative.toString().toLowerCase(Locale.ROOT).contains("/docs/")) return "DOC";
        return "DOC";
    }

    static String detectModule(Path relative, Path projectRoot) {
        // If the file lives under <module>/(docs|README.md), the first
        // path segment is the module. For files directly under root, null.
        if (relative.getNameCount() < 2) return null;
        return relative.getName(0).toString();
    }

    static boolean isExcluded(Path relative) {
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("changelog.md")) return true;
        if (name.startsWith("swagger") && name.endsWith(".json")) return true;
        if (name.startsWith("openapi") && name.endsWith(".json")) return true;
        return false;
    }
}
