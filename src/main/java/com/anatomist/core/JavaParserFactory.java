package com.anatomist.core;

import com.anatomist.core.asmsolver.AsmTypeSolver;
import com.anatomist.core.asmsolver.JarClassFileSource;
import com.anatomist.core.logging.AnatomistLog;
import com.anatomist.core.nativeimage.EmbeddedJdkTypeSolver;
import com.anatomist.core.nativeimage.JdkTypeCatalog;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;

import java.io.IOException;
import java.io.InputStream;
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
 *   <li>Dependency jars → {@link AsmTypeSolver} (native-image compatible
 *       ASM-based jar reader, no class loading)</li>
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
        // Source paths first — project types should resolve before JDK/classpath
        for (Path src : sourcePaths) {
            if (src != null && Files.isDirectory(src)) {
                ts.add(new JavaParserTypeSolver(src));
            }
        }
        if (includeRunningVmClasspath) {
            // ReflectionTypeSolver provides full JDK type information and works
            // correctly with JavaParser's symbol resolution. The EmbeddedJdkTypeSolver
            // (from jdkN-types.bin) is a native-image fallback only — its incomplete
            // type declarations break method resolution in CombinedTypeSolver.
            if (isNativeImage()) {
                TypeSolver jdkSolver = tryLoadEmbeddedJdkSolver();
                if (jdkSolver != null) {
                    ts.add(jdkSolver);
                } else {
                    ts.add(new ReflectionTypeSolver(/*jreOnly*/ true));
                }
            } else {
                ts.add(new ReflectionTypeSolver(/*jreOnly*/ true));
            }
        }
        for (Path jar : classpathEntries) {
            if (jar != null && Files.isRegularFile(jar)) {
                try {
                    ts.add(new AsmTypeSolver(new JarClassFileSource(jar)));
                } catch (RuntimeException e) {
                    AnatomistLog.warn("failed to open jar (asm) for symbol resolution: "
                            + jar + " (" + e.getMessage() + ")");
                }
            }
        }
        return ts;
    }

    /** Look up a pre-generated JDK catalog under
     *  {@code META-INF/anatomist/jdkN-types.bin} matching the build target's
     *  Java version (falling back to the runtime's version). Returns {@code null}
     *  when no catalog ships with the current build — the caller then falls
     *  back to {@link ReflectionTypeSolver}. */
    private TypeSolver tryLoadEmbeddedJdkSolver() {
        int targetRelease = Math.max(javaVersion, 8);
        // Try the requested target first, then walk DOWN to the highest catalog
        // we shipped that's ≤ target. Catalogs are forward-compatible enough for
        // anatomist's erased-type needs.
        for (int v = targetRelease; v >= 8; v--) {
            String res = "/META-INF/anatomist/jdk" + v + "-types.bin";
            try (InputStream in = JavaParserFactory.class.getResourceAsStream(res)) {
                if (in == null) continue;
                JdkTypeCatalog cat = JdkTypeCatalog.readFrom(in);
                return new EmbeddedJdkTypeSolver(cat);
            } catch (IOException ignore) {
                // try next version
            }
        }
        return null;
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
                AnatomistLog.warn("failed to walk source root " + src + ": " + e.getMessage());
                continue;
            }
            for (ParseResult<CompilationUnit> pr : results) {
                if (!pr.isSuccessful() || pr.getResult().isEmpty()) {
                    pr.getProblems().forEach(p ->
                            AnatomistLog.debug("parse problem: " + p.getMessage()));
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

    private static boolean isNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    static LanguageLevel toLanguageLevel(int v) {
        return switch (v) {
            case 9 -> LanguageLevel.JAVA_9;
            case 10 -> LanguageLevel.JAVA_10;
            case 11 -> LanguageLevel.JAVA_11;
            case 12 -> LanguageLevel.JAVA_12;
            case 13 -> LanguageLevel.JAVA_13;
            case 14 -> LanguageLevel.JAVA_14;
            case 15 -> LanguageLevel.JAVA_15;
            case 16 -> LanguageLevel.JAVA_16;
            case 17 -> LanguageLevel.JAVA_17;
            case 18 -> LanguageLevel.JAVA_18;
            case 19 -> LanguageLevel.JAVA_19;
            case 20 -> LanguageLevel.JAVA_20;
            case 21 -> LanguageLevel.JAVA_21;
            default -> LanguageLevel.JAVA_8;
        };
    }
}
