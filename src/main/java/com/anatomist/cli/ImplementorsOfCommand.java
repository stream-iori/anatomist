package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "implementors-of",
        description = "Find classes that implement (or extend) the given interface/type.")
public class ImplementorsOfCommand extends QueryCommand {

    @Parameters(index = "0", description = "Interface or super-type FQN / short label.")
    String type;

    @Override
    protected QueryEnvelope execute(QueryService q) {
        List<NodeRow> rows = q.implementorsOf(type);
        return new QueryEnvelope("implementors-of " + type, rows);
    }
}
