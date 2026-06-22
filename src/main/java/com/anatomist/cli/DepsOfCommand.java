package com.anatomist.cli;

import com.anatomist.query.ContextFilter;
import com.anatomist.query.EdgeRow;
import com.anatomist.query.JsonFormatter;
import com.anatomist.query.PagedResult;
import com.anatomist.query.QueryEnvelope;
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
            if (inLoop || inBranch) {
                List<EdgeRow> rows = ContextFilter.apply(q.depsOf(type), inLoop, inBranch);
                QueryEnvelope env = new QueryEnvelope("deps-of " + type, rows);
                JsonFormatter.emit(System.out, env);
            } else {
                PagedResult<EdgeRow> paged = q.depsOfPaged(type, limit, offset, filter);
                QueryEnvelope env = new QueryEnvelope("deps-of " + type, paged.items());
                env.stats.put("total", paged.total());
                env.stats.put("offset", paged.offset());
                env.stats.put("truncated", paged.truncated());
                JsonFormatter.emit(System.out, env);
            }
            return 0;
        }
    }
}
