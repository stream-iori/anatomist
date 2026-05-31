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

@Command(name = "field-readers",
        description = "Methods/lambdas that READ a field (F1 字段级).")
public class FieldReadersCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Field ref: pkg.Class#name, Class.name, or bare name.")
    String field;

    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<EdgeRow> rows = q.fieldReaders(field);
            JsonFormatter.emit(System.out, new QueryEnvelope("field-readers " + field, rows));
            return 0;
        }
    }
}
