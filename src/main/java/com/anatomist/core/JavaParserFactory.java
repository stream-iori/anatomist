package com.anatomist.core;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Builds a configured {@link JavaParser} (with {@link JavaSymbolSolver}) and
 * drives batched source-root parsing.
 *
 * <p>Builds a {@link JavaParser} configured with a {@link JavaSymbolSolver}
 * over a {@link CombinedTypeSolver} and drives batched source-root parsing:</p>
 * <ul>
 *   <li>Source roots → {@link JavaParserTypeSolver}</li>
 *   <li>Dependency jars → {@link JarTypeSolver} (JavaParser's stock
 *       javassist-backed jar reader; gives full symbol resolution for
 *       external types, methods, fields, and annotations)</li>
 *   <li>JDK classes → {@link ReflectionTypeSolver} (optional, with a version
 *       compatibility guard to avoid leaking the running JDK's API surface
 *       into analyses of older targets)</li>
 * </ul>
 */
public class JavaParserFactory {

    private final int javaVersion;
    private final List<Path> classpathEntries;
    private final List<Path> sourcePaths;
    private final boolean includeRunningVmClasspath;

    public JavaParserFactory(int javaVersion,
                             List<Path> classpathEntries,
                             List<Path> sourcePaths,
                             boolean includeRunningVmClasspath) {
        this.javaVersion = javaVersion;
        this.classpathEntries = classpathEntries == null ? List.of() : List.copyOf(classpathEntries);
        this.sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        this.includeRunningVmClasspath = includeRunningVmClasspath;
    }

    /** Build the combined TypeSolver matching the configured environment. */
    public CombinedTypeSolver newTypeSolver() {
        CombinedTypeSolver ts = new CombinedTypeSolver();
        if (includeRunningVmClasspath) {
            ts.add(new ReflectionTypeSolver(/*jreOnly*/ true));
        }
        for (Path src : sourcePaths) {
            if (src != null && Files.isDirectory(src)) {
                ts.add(new JavaParserTypeSolver(src));
            }
        }
        for (Path jar : classpathEntries) {
            if (jar != null && Files.isRegularFile(jar)) {
                try {
                    ts.add(new JarTypeSolver(jar));
                } catch (IOException e) {
                    System.err.println("WARN: failed to open jar for symbol resolution: "
                            + jar + " (" + e.getMessage() + ")");
                }
            }
        }
        return ts;
    }

    public ParserConfiguration newConfiguration() {
        return new ParserConfiguration()
                .setLanguageLevel(toLanguageLevel(javaVersion))
                .setSymbolResolver(new JavaSymbolSolver(newTypeSolver()));
    }

    /**
     * Parse every {@code .java} file under each configured source root and
     * deliver the resulting {@link CompilationUnit}s to {@code consumer}.
     *
     * <p>The callback receives the source file's absolute path and the parsed
     * unit. Files that fail to parse are reported to stderr and skipped (soft
     * fail per file).</p>
     */
    public void parseAll(BiConsumer<Path, CompilationUnit> consumer) {
        ParserConfiguration cfg = newConfiguration();
        for (Path src : sourcePaths) {
            if (src == null || !Files.isDirectory(src)) continue;
            SourceRoot root = new SourceRoot(src, cfg);
            List<ParseResult<CompilationUnit>> results;
            try {
                results = root.tryToParse();
            } catch (IOException e) {
                System.err.println("WARN: failed to walk source root " + src + ": " + e.getMessage());
                continue;
            }
            for (ParseResult<CompilationUnit> pr : results) {
                if (!pr.isSuccessful() || pr.getResult().isEmpty()) {
                    pr.getProblems().forEach(p ->
                            System.err.println("WARN: parse problem: " + p.getMessage()));
                    continue;
                }
                CompilationUnit cu = pr.getResult().get();
                Path file = cu.getStorage().map(s -> s.getPath()).orElse(null);
                consumer.accept(file, cu);
            }
        }
    }

    /** Parse an explicit list of files (used by tests / non-SourceRoot flows). */
    public List<CompilationUnit> parseFiles(List<Path> files) {
        List<CompilationUnit> out = new ArrayList<>();
        JavaParser parser = new JavaParser(newConfiguration());
        for (Path f : files) {
            try {
                ParseResult<CompilationUnit> pr = parser.parse(f);
                if (pr.isSuccessful() && pr.getResult().isPresent()) {
                    out.add(pr.getResult().get());
                } else {
                    pr.getProblems().forEach(p ->
                            System.err.println("WARN: parse problem in " + f + ": " + p.getMessage()));
                }
            } catch (IOException e) {
                System.err.println("WARN: failed to read " + f + ": " + e.getMessage());
            }
        }
        return out;
    }

    static LanguageLevel toLanguageLevel(int v) {
        return switch (v) {
            case 11 -> LanguageLevel.JAVA_11;
            case 17 -> LanguageLevel.JAVA_17;
            case 21 -> LanguageLevel.JAVA_21;
            default -> LanguageLevel.JAVA_8;
        };
    }
}
