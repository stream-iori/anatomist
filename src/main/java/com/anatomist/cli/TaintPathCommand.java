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
    @Option(names = "--depth", defaultValue = "30",
            description = "Max traversal depth (1..100, default 30); an empty path may be depth-truncated.") int depth;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        QueryEnvelope env = service.path(source, sink, depth, true);
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "taint-path", source, sink, "--depth", String.valueOf(depth)));
        Disclosure.addOption(args, "--module", module);
        Disclosure.addOption(args, "--scope", scope);
        env.query = Disclosure.renderCommand(args);
        Integer nextDepth = Disclosure.nextDepth(env, FlowQueryService.MAX_PATH_DEPTH);
        if (nextDepth != null) {
            java.util.List<String> next = Disclosure.withOption(args, "--depth", nextDepth);
            Disclosure.addOption(next, "--index", IndexPath.resolve(index));
            Disclosure.addNextQuery(env, Disclosure.renderCommand(next));
        }
        return env;
    }

    @Override protected java.util.List<String> coverageAnchors() {
        return java.util.List.of(source, sink);
    }
}
