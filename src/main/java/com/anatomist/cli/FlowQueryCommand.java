package com.anatomist.cli;

import com.anatomist.query.FlowQueryService;
import com.anatomist.query.FlowCoverageException;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryCoverageService;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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
            try {
                QueryEnvelope result = execute(service);
                result.evidence.putAll(new QueryCoverageService(service.connection()).assess(
                        QueryCoverageService.Capability.FLOW,
                        coverageAnchors(), module, scope,
                        result.stats.get("total") instanceof Number total
                                && total.longValue() > 0,
                        false).toMap());
                JsonFormatter.emit(System.out, result);
                return 0;
            } catch (FlowCoverageException coverage) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("status", "error");
                error.put("code", coverage.code());
                error.put("message", coverage.getMessage());
                System.out.println(JsonFormatter.toJson(error));
                return 2;
            }
        }
    }

    protected abstract QueryEnvelope execute(FlowQueryService service);

    protected java.util.List<String> coverageAnchors() {
        return java.util.List.of();
    }
}
