package com.anatomist.cli;

import com.anatomist.query.ContextFilter;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "field-access",
        description = "Methods/lambdas that access a field — reads, writes, or both.")
public class FieldAccessCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Field ref: pkg.Class#name, Class.name, or bare name.")
    String field;

    @Option(names = "--mode", description = "Access mode: reads | writes | all (default: all).")
    String mode = "all";

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Option(names = "--in-loop", description = "Keep only edges occurring inside a loop (for/foreach/while/do).")
    boolean inLoop;

    @Option(names = "--in-branch", description = "Keep only edges occurring inside a branch (if/else/case/catch/ternary).")
    boolean inBranch;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<EdgeRow> rows;
            switch (mode.toLowerCase()) {
                case "reads":
                    rows = q.fieldReaders(field);
                    break;
                case "writes":
                    rows = q.fieldWriters(field);
                    break;
                default:
                    rows = new ArrayList<>(q.fieldReaders(field));
                    rows.addAll(q.fieldWriters(field));
                    break;
            }
            rows = ContextFilter.apply(rows, inLoop, inBranch);
            JsonFormatter.emit(System.out,
                    new QueryEnvelope("field-access " + field + " --mode " + mode, rows));
            return 0;
        }
    }
}
