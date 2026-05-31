package com.anatomist.cli;

import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "package-deps",
        description = "Aggregated package-level dependency edges (CALLS+REFERENCES+IMPLEMENTS+INHERITS).")
public class PackageDepsCommand implements Callable<Integer> {

    @Option(names = "--index") Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<Map<String, Object>> rows = q.packageDeps();
            JsonFormatter.emit(System.out, new QueryEnvelope("package-deps", rows));
            return 0;
        }
    }
}
