package com.anatomist.cli;

import com.anatomist.query.NodeRow;
import com.anatomist.query.QueryEnvelope;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;

@Command(name = "implementors-of",
        mixinStandardHelpOptions = true,
        description = "Find classes that implement (or extend) the given interface/type.",
        footer = "%nExamples:%n  implementors-of OrderService%n  implementors-of SubscriberEventPlugin --recursive%n  implementors-of SubscriberEventPlugin --count")
public class ImplementorsOfCommand extends QueryCommand {

    @Parameters(index = "0", description = "Interface or super-type FQN / short label.")
    String type;

    @Option(names = "--recursive", description = "Include transitive implementors through intermediate abstract classes/interfaces.")
    boolean recursive;

    @Option(names = "--count", description = "Return only the count of implementors (results omitted).")
    boolean count;

    @Override
    protected QueryEnvelope execute(QueryService q) {
        String query = "implementors-of " + type
                + (recursive ? " --recursive" : "") + (count ? " --count" : "");
        if (count) {
            int n = q.countImplementorsOf(type, recursive);
            QueryEnvelope env = new QueryEnvelope(query, List.of());
            env.stats.put("total", n);
            env.stats.put("count", n);
            return env;
        }
        List<NodeRow> rows = q.implementorsOf(type, recursive);
        return new QueryEnvelope(query, rows);
    }
}
