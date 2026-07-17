package com.anatomist.cli;

import com.anatomist.query.FlowQueryService;
import com.anatomist.query.QueryEnvelope;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "taint-path", mixinStandardHelpOptions = true,
        description = "Find a configured source-to-sink path; sanitizers stop traversal.")
public final class TaintPathCommand extends FlowQueryCommand {
    @Parameters(index = "0", defaultValue = "*") String source;
    @Parameters(index = "1", defaultValue = "*") String sink;
    @Option(names = "--depth", defaultValue = "30") int depth;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        return service.path(source, sink, depth, true);
    }

    @Override protected java.util.List<String> coverageAnchors() {
        return java.util.List.of(source, sink);
    }
}
