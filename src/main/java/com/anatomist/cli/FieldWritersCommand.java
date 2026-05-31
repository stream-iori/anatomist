package com.anatomist.cli;

import com.anatomist.query.EdgeRow;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "field-writers",
        description = "Methods/lambdas that WRITE a field — 'who changed order.status'.")
public class FieldWritersCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Field ref: pkg.Class#name, Class.name, or bare name.")
    String field;

    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<EdgeRow> rows = q.fieldWriters(field);
            JsonFormatter.emit(System.out, new QueryEnvelope("field-writers " + field, rows));
            return 0;
        }
    }
}
