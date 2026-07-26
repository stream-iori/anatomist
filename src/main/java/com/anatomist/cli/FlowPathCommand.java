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
    @Option(names = "--depth", defaultValue = "20",
            description = "Max traversal depth (1..100, default 20); an empty path may be depth-truncated.") int depth;
    @Option(names = "--from-slot", description = "Source slot: arg:N, return, or throw")
    String sourceSlot;
    @Option(names = "--to-slot", description = "Target slot: arg:N, return, or throw")
    String targetSlot;
    @Option(names = "--include-control", description = "Also traverse control and guard edges")
    boolean includeControl;
    @Option(names = "--include-exception", description = "Also traverse exception edges")
    boolean includeException;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        QueryEnvelope env = service.path(source, target, depth, new FlowQueryService.PathOptions(
                sourceSlot, targetSlot, includeControl, includeException, false));
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "flow-path", source, target, "--depth", String.valueOf(depth)));
        Disclosure.addOption(args, "--from-slot", sourceSlot);
        Disclosure.addOption(args, "--to-slot", targetSlot);
        Disclosure.addFlag(args, includeControl, "--include-control");
        Disclosure.addFlag(args, includeException, "--include-exception");
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
        return java.util.List.of(source, target);
    }
}
