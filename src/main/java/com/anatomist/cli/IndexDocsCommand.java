package com.anatomist.cli;

import com.anatomist.doc.DocScanner;
import com.anatomist.model.Document;
import com.anatomist.store.SqliteStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(modelTransformer = AgentHelp.class,
        name = "index-docs",
        mixinStandardHelpOptions = true,
        description = "Index project Markdown for related-docs queries."
)
public class IndexDocsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the project root.")
    Path projectPath;

    @Option(names = {"--output", "--index"},
            description = "SQLite database path (default: ~/.anatomist/indexes/<repo-key>/index.db).")
    Path output;

    @Override
    public Integer call() {
        long started = System.currentTimeMillis();
        try {
            if (projectPath == null || !Files.isDirectory(projectPath)) {
                System.err.println("ERROR: project path does not exist or is not a directory: " + projectPath);
                return 1;
            }
            Path projectRoot = projectPath.toRealPath().normalize();

            Path dbPath = output == null
                    ? DefaultIndexPath.forIndexWrite(projectRoot)
                    : output.toAbsolutePath().normalize();
            Files.createDirectories(dbPath.getParent());
            com.anatomist.version.SnapshotFiles.requireMutable(dbPath);

            DocScanner scanner = new DocScanner();
            List<Document> docs = scanner.scan(projectRoot);

            try (com.anatomist.store.IndexOperationLock operation =
                         com.anatomist.store.IndexOperationLock.forWrite(dbPath);
                 com.anatomist.store.IndexLock wLock = com.anatomist.store.IndexLock.forWrite(dbPath);
                 SqliteStore store = new SqliteStore(dbPath)) {
                if (!store.schemaExists()) store.initSchema();
                String indexedRoot = store.readProjectMeta("source_root").orElse("");
                if (!indexedRoot.isBlank() && !sameProject(indexedRoot, projectRoot)) {
                    System.err.println("ERROR: INDEX_PROJECT_MISMATCH: requested " + projectRoot
                            + ", index belongs to " + indexedRoot);
                    return 2;
                }
                store.replaceDocumentsForProject(docs, projectRoot.toString());
            }

            long elapsed = System.currentTimeMillis() - started;
            System.out.println("Indexed docs from " + projectRoot);
            System.out.println("  Documents: " + docs.size());
            System.out.println("  Output:    " + dbPath);
            System.out.println("Done in " + elapsed + "ms");
            return 0;
        } catch (Exception e) {
            System.err.println("ERROR: index-docs failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static boolean sameProject(String indexedRoot, Path requestedRoot) {
        try {
            return Path.of(indexedRoot).toRealPath().normalize().equals(requestedRoot);
        } catch (Exception invalidIdentity) {
            return false;
        }
    }
}
