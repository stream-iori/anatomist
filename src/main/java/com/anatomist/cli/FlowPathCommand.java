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
    @Option(names = "--from-slot", description = "Source slot: arg:N, return, or throw")
    String sourceSlot;
    @Option(names = "--to-slot", description = "Target slot: arg:N, return, or throw")
    String targetSlot;
    @Option(names = "--include-control", description = "Also traverse control and guard edges")
    boolean includeControl;
    @Option(names = "--include-exception", description = "Also traverse exception edges")
    boolean includeException;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        return service.path(source, target, depth, new FlowQueryService.PathOptions(
                sourceSlot, targetSlot, includeControl, includeException, false));
    }

    @Override protected java.util.List<String> coverageAnchors() {
        return java.util.List.of(source, target);
    }
}
