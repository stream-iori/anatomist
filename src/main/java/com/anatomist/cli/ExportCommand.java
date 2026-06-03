package com.anatomist.cli;

import com.anatomist.export.ExportHtmlWriter;
import com.anatomist.query.OverviewResult;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "export",
        description = "Export the index for visualization. Currently supports "
                    + "--format html: a single self-contained file with a package "
                    + "tree, a package dependency graph, and class-level drill-down.")
public class ExportCommand implements Callable<Integer> {

    @Option(names = "--format", description = "Output format: html (default).")
    String format = "html";

    @Option(names = "--output", required = true,
            description = "Path to write the export file (e.g. project.html).")
    Path output;

    @Option(names = "--max-edges",
            description = "Cap class-level edges embedded for drill-down (default: 20000; 0 = no cap).")
    int maxEdges = 20_000;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Override
    public Integer call() {
        if (!"html".equalsIgnoreCase(format)) {
            System.err.println("ERROR: unsupported --format '" + format + "' (only 'html' is supported).");
            return 2;
        }
        Path db = IndexPath.resolve(index);
        String html;
        try (QueryService q = new QueryService(db)) {
            OverviewResult ov = q.overview();
            List<Map<String, Object>> classDeps = q.classDepsInternal(maxEdges);
            html = ExportHtmlWriter.render(ExportHtmlWriter.buildPayload(ov, classDeps));
        }
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(output, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("ERROR: failed to write " + output + ": " + e.getMessage());
            return 1;
        }
        System.out.println("Wrote " + output);
        return 0;
    }
}
