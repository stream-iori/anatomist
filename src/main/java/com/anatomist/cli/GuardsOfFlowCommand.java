package com.anatomist.cli;

import com.anatomist.query.FlowQueryService;
import com.anatomist.query.QueryEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "guards-of", mixinStandardHelpOptions = true,
        description = "Show true/false guard and condition dependencies.")
public final class GuardsOfFlowCommand extends FlowQueryCommand {
    @Parameters(index = "0") String method;
    @Option(names = "--limit", defaultValue = "200") int limit;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        return service.guardsOf(method, limit);
    }
}
