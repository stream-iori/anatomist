package com.anatomist.cli;

import com.anatomist.core.ClasspathDetector;
import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JdtParserFactory;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.core.ProjectScanner;
import com.anatomist.extract.MethodExtractor;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.model.ExtractionResult;
import com.anatomist.store.SqliteStore;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FileASTRequestor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(
        name = "index",
        description = "Index a Java project into a SQLite database."
)
public class IndexCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the Java project to index.")
    Path projectPath;

    @Option(names = "--java-version", description = "Target Java language version (default: 8).")
    Integer javaVersion;

    @Option(names = "--exclude", description = "Comma-separated directory names to exclude.")
    String exclude;

    @Option(names = "--output", description = "Output SQLite database path (default: <project>/.anatomist/index.db).")
    Path output;

    @Option(names = "--classpath", description = "Override classpath entries (path-separator delimited).")
    String classpath;

    @Option(names = "--project-source", description = "Override project source roots (path-separator delimited).")
    String projectSource;

    @Option(names = "--no-classpath", description = "Skip classpath detection; bindings to external types will be null.")
    boolean noClasspath;

    @Override
    public Integer call() {
        long started = System.currentTimeMillis();
        try {
            if (projectPath == null || !Files.isDirectory(projectPath)) {
                System.err.println("ERROR: project path does not exist or is not a directory: " + projectPath);
                return 1;
            }
            Path projectRoot = projectPath.toAbsolutePath().normalize();

            ClasspathDetector cd = new ClasspathDetector();

            List<Path> sourcePaths = resolveSourcePaths(cd, projectRoot);
            if (sourcePaths.isEmpty()) {
                System.err.println("ERROR: no source paths resolved for " + projectRoot);
                return 1;
            }

            List<String> classpathEntries = resolveClasspath(cd, projectRoot);

            Set<String> extraExcludes = exclude == null || exclude.isEmpty()
                    ? Collections.emptySet()
                    : new HashSet<>(Arrays.asList(exclude.split(",")));
            ProjectScanner scanner = new ProjectScanner(extraExcludes);
            List<Path> sourceFiles = scanner.scan(sourcePaths);
            if (sourceFiles.isEmpty()) {
                System.err.println("ERROR: no .java files found under " + sourcePaths);
                return 1;
            }

            int jv = javaVersion == null ? 8 : javaVersion;
            JdtParserFactory factory = new JdtParserFactory(
                    jv,
                    classpathEntries,
                    sourcePaths.stream().map(Path::toString).collect(Collectors.toList()),
                    false
            );

            NodeIdGenerator idGen = new NodeIdGenerator();
            ExtractionContext ctx = new ExtractionContext(projectRoot, sourcePaths, idGen, null, "MAIN");
            TypeExtractor typeExtractor = new TypeExtractor(ctx);
            MethodExtractor methodExtractor = new MethodExtractor(ctx);

            ExtractionResult result = new ExtractionResult();

            factory.parseAll(sourceFiles, new FileASTRequestor() {
                @Override
                public void acceptAST(String sourceFilePath, CompilationUnit ast) {
                    String relative = relativize(projectRoot, Path.of(sourceFilePath));
                    ast.setProperty("source_file", relative);
                    typeExtractor.extract(ast, result);
                    methodExtractor.extract(ast, result);
                }
            });

            Path dbPath = output == null
                    ? projectRoot.resolve(".anatomist").resolve("index.db")
                    : output.toAbsolutePath().normalize();
            Files.createDirectories(dbPath.getParent());
            Files.deleteIfExists(dbPath);

            try (SqliteStore store = new SqliteStore(dbPath)) {
                store.initSchema();
                store.write(result);
            }

            long elapsed = System.currentTimeMillis() - started;
            long types = result.nodes.stream().filter(n -> isType(n.kind)).count();
            long methods = result.nodes.stream().filter(n -> "METHOD".equals(n.kind)).count();
            long contains = result.edges.stream().filter(e -> "CONTAINS".equals(e.relation)).count();

            System.out.println("Indexed " + projectRoot);
            System.out.println("  Source paths: " + sourcePaths);
            System.out.println("  Classpath:    " + classpathEntries.size() + " jars");
            System.out.println("  Source files: " + sourceFiles.size());
            System.out.println("  Types:        " + types);
            System.out.println("  Methods:      " + methods);
            System.out.println("  CONTAINS:     " + contains);
            System.out.println("  Output:       " + dbPath);
            System.out.println("Done in " + elapsed + "ms");
            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: index failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    List<Path> resolveSourcePaths(ClasspathDetector cd, Path projectRoot) {
        if (projectSource != null && !projectSource.isEmpty()) {
            List<Path> out = new ArrayList<>();
            for (String p : projectSource.split(File.pathSeparator)) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                Path resolved = Path.of(t);
                if (!resolved.isAbsolute()) resolved = projectRoot.resolve(t);
                out.add(resolved);
            }
            return out;
        }
        return cd.detectSourcePaths(projectRoot);
    }

    List<String> resolveClasspath(ClasspathDetector cd, Path projectRoot) {
        if (noClasspath) return Collections.emptyList();
        if (classpath != null && !classpath.isEmpty()) {
            return Arrays.stream(classpath.split(File.pathSeparator))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
        return cd.detect(projectRoot);
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static boolean isType(String kind) {
        return "CLASS".equals(kind) || "INTERFACE".equals(kind) || "ENUM".equals(kind);
    }
}
