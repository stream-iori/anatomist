package com.anatomist.cli;

import com.anatomist.query.FlowQueryService;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

abstract class FlowQueryCommand implements Callable<Integer> {

    @Option(names = "--index") Path index;
    @Option(names = "--module", description = "Restrict flow resolution to one module.") String module;
    @Option(names = "--scope", description = "MAIN | TEST | GENERATED | ALL.",
            defaultValue = "MAIN") String scope;

    @Override
    public final Integer call() {
        Path database = IndexPath.resolve(index);
        try (FlowQueryService service = new FlowQueryService(database)) {
            service.select(module, scope);
            QueryEnvelope result = execute(service);
            JsonFormatter.emit(System.out, result);
            return 0;
        }
    }

    protected abstract QueryEnvelope execute(FlowQueryService service);
}
