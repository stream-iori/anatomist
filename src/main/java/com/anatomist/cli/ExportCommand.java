package com.anatomist.cli;

import com.anatomist.export.ArchExportPayloadBuilder;
import com.anatomist.export.ExportHtmlWriter;
import com.anatomist.query.ClassEdge;
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
        mixinStandardHelpOptions = true,
        description = "Export the index for visualization.%n"
                    + "  --format html : package tree + class dependency graph%n"
                    + "  --format arch : architecture swimlane view with code drill-down")
public class ExportCommand implements Callable<Integer> {

    @Option(names = "--format", description = "Output format: html | arch (default: html).")
    String format = "html";

    @Option(names = "--output", required = true,
            description = "Path to write the export file (e.g. project.html).")
    Path output;

    @Option(names = "--max-edges",
            description = "Cap class-level edges embedded for drill-down (default: 20000; 0 = no cap).")
    int maxEdges = 20_000;

    @Option(names = "--source-root",
            description = "Source root directory for code snippet extraction (arch format only).")
    Path sourceRoot;

    @Option(names = "--max-snippets",
            description = "Max code snippets to embed in arch export (default: 2000; 0 = none).")
    int maxSnippets = 2_000;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/indexes/<repo-key>/index.db).")
    Path index;

    @Override
    public Integer call() {
        if ("arch".equalsIgnoreCase(format)) {
            return exportArch();
        } else if ("html".equalsIgnoreCase(format)) {
            return exportHtml();
        } else {
            System.err.println("ERROR: unsupported --format '" + format + "' (supported: html, arch).");
            return 2;
        }
    }

    private Integer exportHtml() {
        Path db = IndexPath.resolve(index);
        String html;
        try (QueryService q = new QueryService(db)) {
            OverviewResult ov = q.overview();
            List<ClassEdge> classDeps = q.classDepsInternal(maxEdges);
            html = ExportHtmlWriter.render(ExportHtmlWriter.buildPayload(ov, classDeps));
        }
        return writeOutput(html);
    }

    private Integer exportArch() {
        Path db = IndexPath.resolve(index);
        String html;
        try (QueryService q = new QueryService(db)) {
            ArchExportPayloadBuilder builder = new ArchExportPayloadBuilder(q.connection());
            Map<String, Object> payload = builder.build(sourceRoot, maxEdges, maxSnippets);
            html = ExportHtmlWriter.renderArch(payload);
        }
        return writeOutput(html);
    }

    private Integer writeOutput(String html) {
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
