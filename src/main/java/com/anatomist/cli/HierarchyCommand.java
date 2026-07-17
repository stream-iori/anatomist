package com.anatomist.cli;

import com.anatomist.query.HierarchyResult;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryCoverageService;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "hierarchy",
        mixinStandardHelpOptions = true,
        description = "Show extends chain + direct implements for a type.")
public class HierarchyCommand extends QueryCommand {

    @Parameters(index = "0", description = "Type FQN or short label.")
    String type;

    @Override
    protected QueryCoverageService.Capability coverageCapability() {
        return QueryCoverageService.Capability.TYPE_OUTGOING;
    }

    @Override
    protected List<String> coverageAnchors() {
        return List.of(type);
    }

    @Override
    protected QueryEnvelope execute(QueryService q) {
        HierarchyResult r = q.hierarchy(type);
        QueryEnvelope env = new QueryEnvelope("hierarchy " + type, List.of(r));
        env.stats.clear();
        env.stats.putAll(r.toStats());
        env.stats.put("found", !r.extendsChain.isEmpty() || !r.implementsList.isEmpty());
        return env;
    }

    @Override
    protected boolean hasPositiveEvidence(QueryEnvelope env) {
        return Boolean.TRUE.equals(env.stats.get("found"));
    }
}
