package com.anatomist.cli;

import com.anatomist.query.FlowQueryService;
import com.anatomist.query.QueryEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "exception-flow", mixinStandardHelpOptions = true,
        description = "Show throw/catch/declared-exception propagation for a method.")
public final class ExceptionFlowCommand extends FlowQueryCommand {
    @Parameters(index = "0") String method;
    @Option(names = "--limit", defaultValue = "200") int limit;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        return service.exceptionFlow(method, limit);
    }
}
