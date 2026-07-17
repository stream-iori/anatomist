package com.anatomist.cli;

import com.anatomist.query.FlowQueryService;
import com.anatomist.query.QueryEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "flow-path", mixinStandardHelpOptions = true,
        description = "Find a shortest static data-flow path.")
public final class FlowPathCommand extends FlowQueryCommand {
    @Parameters(index = "0") String source;
    @Parameters(index = "1") String target;
    @Option(names = "--depth", defaultValue = "20") int depth;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        return service.path(source, target, depth, false);
    }
}
