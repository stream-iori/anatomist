package com.anatomist.cli;

import com.anatomist.query.ContextFilter;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.PagedResult;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryCoverageService;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "deps-of",
        mixinStandardHelpOptions = true,
        description = "Outgoing CALLS + REFERENCES from a type (and its methods).")
public class DepsOfCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Type FQN or short label.")
    String type;

    @Option(names = "--index") Path index;
    @Option(names = "--module") String module;
    @Option(names = "--scope", defaultValue = "MAIN") String scope;

    @Option(names = "--limit", description = "Max results per page (default 50).") int limit = 50;
    @Option(names = "--offset", description = "Skip N results (for pagination).") int offset = 0;
    @Option(names = "--filter", description = "Filter results by substring match on target label/FQN.") String filter;

    @Option(names = "--in-loop", description = "Keep only edges occurring inside a loop (for/foreach/while/do).")
    boolean inLoop;

    @Option(names = "--in-branch", description = "Keep only edges occurring inside a branch (if/else/case/catch/ternary).")
    boolean inBranch;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            q.selectNodes(module, scope);
            PagedResult<EdgeRow> paged = Disclosure.filterAndPage(
                    q.depsOf(type), inLoop, inBranch, filter, limit, offset);
            List<String> base = new java.util.ArrayList<>(List.of("deps-of", type));
            Disclosure.addFlag(base, inLoop, "--in-loop");
            Disclosure.addFlag(base, inBranch, "--in-branch");
            Disclosure.addOption(base, "--filter", filter);
            Disclosure.addOption(base, "--module", module);
            Disclosure.addOption(base, "--scope", scope);
            QueryEnvelope env = new QueryEnvelope(Disclosure.renderCommand(base), paged.items());
            Disclosure.putPaging(env, paged.total(), limit, paged.offset());
            Disclosure.putBudget(env, "edges", paged.items().size(), paged.total());
            if (paged.truncated()) {
                List<String> next = new java.util.ArrayList<>(base);
                Disclosure.addOption(next, "--index", db);
                Disclosure.addOption(next, "--limit", limit > 0 ? limit : 50);
                Disclosure.addOption(next, "--offset", env.stats.get("next_offset"));
                env.nextQueries = List.of(Disclosure.renderCommand(next));
            }
            env.evidence.putAll(new QueryCoverageService(q.connection()).assess(
                    QueryCoverageService.Capability.REFERENCE_OUTGOING,
                    List.of(type), module, scope, paged.total() > 0, false).toMap());
            JsonFormatter.emit(System.out, env);
            return 0;
        }
    }
}
