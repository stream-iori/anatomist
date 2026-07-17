package com.anatomist.cli;

import com.anatomist.query.FlowQueryService;
import com.anatomist.query.QueryEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "flow-summary", mixinStandardHelpOptions = true,
        description = "Show method input/return/exception flow summaries.")
public final class FlowSummaryCommand extends FlowQueryCommand {
    @Parameters(index = "0") String method;
    @Option(names = "--limit", defaultValue = "200") int limit;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        return service.summaries(method, limit);
    }

    @Override protected java.util.List<String> coverageAnchors() {
        return java.util.List.of(method);
    }
}
