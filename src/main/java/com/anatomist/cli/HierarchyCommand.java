package com.anatomist.cli;

import com.anatomist.query.HierarchyResult;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "hierarchy",
        description = "Show extends chain + direct implements for a type.")
public class HierarchyCommand extends QueryCommand {

    @Parameters(index = "0", description = "Type FQN or short label.")
    String type;

    @Override
    protected QueryEnvelope execute(QueryService q) {
        HierarchyResult r = q.hierarchy(type);
        QueryEnvelope env = new QueryEnvelope("hierarchy " + type, List.of(r));
        env.stats.clear();
        env.stats.putAll(r.toStats());
        return env;
    }
}
