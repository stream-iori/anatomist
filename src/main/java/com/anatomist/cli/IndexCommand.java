package com.anatomist.cli;

import com.anatomist.core.ClasspathDetector;
import com.anatomist.core.ExtractionContext;
import com.anatomist.core.JavaParserFactory;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.core.ProjectScanner;
import com.anatomist.extract.AnnotationExtractor;
import com.anatomist.extract.CallGraphExtractor;
import com.anatomist.extract.FieldAccessExtractor;
import com.anatomist.extract.FieldExtractor;
import com.anatomist.extract.HierarchyExtractor;
import com.anatomist.extract.MethodExtractor;
import com.anatomist.extract.ReferenceExtractor;
import com.anatomist.extract.TypeExtractor;
import com.anatomist.model.ExtractionResult;
import com.anatomist.store.SqliteStore;
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

    @Option(names = "--no-classpath", description = "Skip classpath detection; external types will be unresolved.")
    boolean noClasspath;

    @Option(names = "--vm-classpath",
            description = "Add ReflectionTypeSolver so JDK types resolve. "
                    + "Defaults to true so java.lang.* / java.util.* are visible "
                    + "without an explicit classpath. Turn off when analysing a "
                    + "much older target than the running JDK.",
            defaultValue = "true", arity = "1")
    boolean vmClasspath;

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

            List<Path> classpathEntries = resolveClasspath(cd, projectRoot);

            Set<String> extraExcludes = exclude == null || exclude.isEmpty()
                    ? Collections.emptySet()
                    : new HashSet<>(Arrays.asList(exclude.split(",")));
            ProjectScanner scanner = new ProjectScanner(extraExcludes);
            List<Path> sourceFiles = scanner.scan(sourcePaths);
            if (sourceFiles.isEmpty()) {
                System.err.println("ERROR: no .java files found under " + sourcePaths);
                return 1;
            }

            int jv;
            if (javaVersion != null) {
                jv = javaVersion;
            } else {
                jv = cd.detectJavaVersion(projectRoot).orElse(8);
            }
            System.err.println("Parsing with Java " + jv);
            JavaParserFactory factory = new JavaParserFactory(
                    jv, classpathEntries, sourcePaths, vmClasspath);

            NodeIdGenerator idGen = new NodeIdGenerator();
            ExtractionContext ctx = new ExtractionContext(projectRoot, sourcePaths, idGen, null, "MAIN");
            TypeExtractor typeExtractor = new TypeExtractor(ctx);
            FieldExtractor fieldExtractor = new FieldExtractor(ctx);
            MethodExtractor methodExtractor = new MethodExtractor(ctx);
            AnnotationExtractor annotationExtractor = new AnnotationExtractor(ctx);
            HierarchyExtractor hierarchyExtractor = new HierarchyExtractor(ctx);
            ReferenceExtractor referenceExtractor = new ReferenceExtractor(ctx);
            CallGraphExtractor callGraphExtractor = new CallGraphExtractor(ctx);
            FieldAccessExtractor fieldAccessExtractor = new FieldAccessExtractor(ctx);

            ExtractionResult result = new ExtractionResult();

            factory.parseAll((filePath, cu) -> {
                String relative = filePath == null ? null : relativize(projectRoot, filePath);
                if (relative != null) cu.setData(TypeExtractor.SourceFileKey.KEY, relative);
                // Order matters: nodes first, then edges that reference them.
                typeExtractor.extract(cu, result);
                fieldExtractor.extract(cu, result);
                methodExtractor.extract(cu, result);
                annotationExtractor.extract(cu, result);
                hierarchyExtractor.extract(cu, result);
                referenceExtractor.extract(cu, result);
                callGraphExtractor.extract(cu, result);
                fieldAccessExtractor.extract(cu, result);
            });

            Path dbPath = output == null
                    ? projectRoot.resolve(".anatomist").resolve("index.db")
                    : output.toAbsolutePath().normalize();
            Files.createDirectories(dbPath.getParent());
            Files.deleteIfExists(dbPath);

            try (SqliteStore store = new SqliteStore(dbPath)) {
                store.initSchema();
                int dropped = pruneDanglingInternalEdges(result);
                if (dropped > 0) {
                    System.err.println("WARN: dropped " + dropped + " edges with dangling internal target (extractor gaps)");
                }
                store.write(result);
            }

            long elapsed = System.currentTimeMillis() - started;
            long types = result.nodes.stream().filter(n -> isType(n.kind)).count();
            long methods = result.nodes.stream().filter(n -> "METHOD".equals(n.kind)).count();
            long fields = result.nodes.stream().filter(n -> "FIELD".equals(n.kind)).count();
            long contains = countEdges(result, "CONTAINS");
            long inherits = countEdges(result, "INHERITS");
            long implementsRel = countEdges(result, "IMPLEMENTS");
            long overrides = countEdges(result, "OVERRIDES");
            long references = countEdges(result, "REFERENCES");
            long calls = countEdges(result, "CALLS");
            long reads = countEdges(result, "READS");
            long writes = countEdges(result, "WRITES");

            System.out.println("Indexed " + projectRoot);
            System.out.println("  Source paths: " + sourcePaths);
            System.out.println("  Classpath:    " + classpathEntries.size() + " jars");
            System.out.println("  Source files: " + sourceFiles.size());
            System.out.println("  Types:        " + types);
            System.out.println("  Methods:      " + methods);
            System.out.println("  Fields:       " + fields);
            System.out.println("  Annotations:  " + result.annotations.size());
            System.out.println("  CONTAINS:     " + contains);
            System.out.println("  INHERITS:     " + inherits);
            System.out.println("  IMPLEMENTS:   " + implementsRel);
            System.out.println("  OVERRIDES:    " + overrides);
            System.out.println("  REFERENCES:   " + references);
            System.out.println("  CALLS:        " + calls);
            System.out.println("  READS:        " + reads);
            System.out.println("  WRITES:       " + writes);
            System.out.println("  Unresolved:   " + ctx.unresolvedCount());
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

    List<Path> resolveClasspath(ClasspathDetector cd, Path projectRoot) {
        if (noClasspath) return Collections.emptyList();
        if (classpath != null && !classpath.isEmpty()) {
            return Arrays.stream(classpath.split(File.pathSeparator))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(Path::of)
                    .collect(Collectors.toList());
        }
        return cd.detect(projectRoot).stream().map(Path::of).collect(Collectors.toList());
    }

    private static String relativize(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static boolean isType(String kind) {
        return "CLASS".equals(kind) || "INTERFACE".equals(kind) || "ENUM".equals(kind)
                || "ANONYMOUS_CLASS".equals(kind);
    }

    private static long countEdges(ExtractionResult r, String relation) {
        return r.edges.stream().filter(e -> relation.equals(e.relation)).count();
    }

    /**
     * Drop any edge / annotation whose source / internal target does not
     * resolve to a known node — would otherwise blow the FK on insert. The
     * underlying coverage gap (LOCAL_CLASS / LAMBDA / METHOD_REF nodes not
     * yet emitted) is the actual fix; this is the last line of defence.
     */
    private static int pruneDanglingInternalEdges(ExtractionResult r) {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (com.anatomist.model.Node n : r.nodes) known.add(n.id);
        int before = r.edges.size() + r.annotations.size();
        r.edges.removeIf(e -> !e.isExternal && (e.targetId == null || !known.contains(e.targetId)));
        r.edges.removeIf(e -> e.sourceId == null || !known.contains(e.sourceId));
        r.annotations.removeIf(a -> a.nodeId == null || !known.contains(a.nodeId));
        return before - r.edges.size() - r.annotations.size();
    }
}
