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

@Command(
        name = "index-docs",
        mixinStandardHelpOptions = true,
        description = "Scan project markdown documents into SQLite documents + doc_content FTS5."
)
public class IndexDocsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the project root.")
    Path projectPath;

    @Option(names = "--output", description = "Output SQLite database path (default: ~/.anatomist/<repo>/index.db).")
    Path output;

    @Override
    public Integer call() {
        long started = System.currentTimeMillis();
        try {
            if (projectPath == null || !Files.isDirectory(projectPath)) {
                System.err.println("ERROR: project path does not exist or is not a directory: " + projectPath);
                return 1;
            }
            Path projectRoot = projectPath.toAbsolutePath().normalize();

            Path dbPath = output == null
                    ? DefaultIndexPath.forIndexWrite(projectRoot)
                    : output.toAbsolutePath().normalize();
            Files.createDirectories(dbPath.getParent());

            DocScanner scanner = new DocScanner();
            List<Document> docs = scanner.scan(projectRoot);

            try (com.anatomist.store.IndexLock wLock = com.anatomist.store.IndexLock.forWrite(dbPath);
                 SqliteStore store = new SqliteStore(dbPath)) {
                store.initSchema();
                store.insertDocuments(docs);
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
}
