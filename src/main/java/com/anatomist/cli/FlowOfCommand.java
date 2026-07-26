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
    @Option(names = "--reverse", description = "Traverse incoming flow edges.") boolean reverse;
    @Option(names = "--depth", defaultValue = "8",
            description = "Traversal depth (1..50, default 8); check stats.depth_truncated.") int depth;
    @Option(names = "--limit", defaultValue = "200",
            description = "Traversal edge budget (default 200, max 10000); check stats.truncated and next_queries.") int limit;

    @Override protected QueryEnvelope execute(FlowQueryService service) {
        QueryEnvelope env = service.flowOf(method, reverse, depth, limit);
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "flow-of", method, "--depth", String.valueOf(depth),
                "--limit", String.valueOf(limit)));
        Disclosure.addFlag(args, reverse, "--reverse");
        Disclosure.addOption(args, "--module", module);
        Disclosure.addOption(args, "--scope", scope);
        env.query = Disclosure.renderCommand(args);
        Integer nextDepth = Disclosure.nextDepth(env, FlowQueryService.MAX_TRAVERSAL_DEPTH);
        if (nextDepth != null) {
            java.util.List<String> next = Disclosure.withOption(args, "--depth", nextDepth);
            Disclosure.addOption(next, "--index", IndexPath.resolve(index));
            Disclosure.addNextQuery(env, Disclosure.renderCommand(next));
        }
        if (Boolean.TRUE.equals(env.stats.get("limit_truncated"))) {
            int effective = ((Number) env.stats.get("limit")).intValue();
            if (effective < FlowQueryService.MAX_LIMIT) {
                java.util.List<String> next = Disclosure.withOption(args, "--limit",
                        Math.min(FlowQueryService.MAX_LIMIT, effective * 2));
                Disclosure.addOption(next, "--index", IndexPath.resolve(index));
                Disclosure.addNextQuery(env, Disclosure.renderCommand(next));
            }
        }
        return env;
    }

    @Override protected java.util.List<String> coverageAnchors() {
        return java.util.List.of(method);
    }
}
