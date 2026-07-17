package com.anatomist.cli;

import com.anatomist.query.FlowQueryService;
import com.anatomist.query.QueryEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "flow-of", mixinStandardHelpOptions = true,
        description = "Trace CFG/def-use/interprocedural flow from a method.")
public final class FlowOfCommand extends FlowQueryCommand {
    @Parameters(index = "0") String method;
    @Option(names = "--reverse") boolean reverse;
    @Option(names = "--depth", defaultValue = "8") int depth;
    @Option(names = "--limit", defaultValue = "200") int limit;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        return service.flowOf(method, reverse, depth, limit);
    }
}
