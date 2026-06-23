package com.anatomist.cli;

import com.anatomist.incremental.FileCacheService;
import com.anatomist.json.DtoCodecs;
import com.anatomist.json.Json;
import com.anatomist.query.NodeRow;
import com.anatomist.query.OverviewResult;
import com.anatomist.query.QueryService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "survey-baseline",
        mixinStandardHelpOptions = true,
        description = "Emit a bounded Agent map: overview, likely entries, domain candidates, repositories, events, and next queries.")
public class SurveyBaselineCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Project root for query suggestions.")
    Path projectPath;

    @Option(names = "--format", description = "Output format: json | text.", defaultValue = "json")
    String format;

    @Option(names = "--limit", description = "Max candidates per section (default 20).")
    int limit = 20;

    @Option(names = "--index", description = "Path to index.db (default: ~/.anatomist/<repo>/index.db).")
    Path index;

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            DtoCodecs.ensureRegistered();
            OverviewResult overview = q.overview();
            CandidateSection entries = collectCandidates(limit,
                    new CandidateQuery("annotation:@RestController", q.searchByAnnotation("@RestController", "CLASS", limit)),
                    new CandidateQuery("annotation:@Controller", q.searchByAnnotation("@Controller", "CLASS", limit)),
                    new CandidateQuery("role:ENTRY", q.searchByRole("ENTRY", limit)),
                    new CandidateQuery("name:*Controller", q.searchByName("*Controller", "CLASS", limit)));
            CandidateSection domains = collectCandidates(limit,
                    new CandidateQuery("role:DOMAIN_MODEL", q.searchByRole("DOMAIN_MODEL", limit)),
                    new CandidateQuery("annotation:@Entity", q.searchByAnnotation("@Entity", "CLASS", limit)),
                    new CandidateQuery("name:*Order*", q.searchByName("*Order*", "CLASS", limit)));
            CandidateSection repositories = collectCandidates(limit,
                    new CandidateQuery("role:REPOSITORY", q.searchByRole("REPOSITORY", limit)),
                    new CandidateQuery("annotation:@Repository", q.searchByAnnotation("@Repository", "CLASS", limit)),
                    new CandidateQuery("name:*Repository", q.searchByName("*Repository", null, limit)));
            CandidateSection events = collectCandidates(limit,
                    new CandidateQuery("name:*Event", q.searchByName("*Event", null, limit)),
                    new CandidateQuery("name:*Listener", q.searchByName("*Listener", null, limit)));

            Map<String, Object> candidateSources = new LinkedHashMap<>();
            candidateSources.put("entry_candidates", entries.sources());
            candidateSources.put("domain_candidates", domains.sources());
            candidateSources.put("repositories", repositories.sources());
            candidateSources.put("events", events.sources());

            List<String> warnings = new ArrayList<>();
            if (overview.archRoleCounts.isEmpty()) {
                warnings.add("arch_roles_empty_run_anatomist_annotate_auto_for_role_based_candidates");
            }
            if (entries.rows().isEmpty()) warnings.add("no_entry_candidates_found");

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("command", "survey-baseline");
            out.put("status", "ok");
            out.put("schema_version", FileCacheService.CURRENT_SCHEMA_VERSION);
            out.put("index_path", db.toString());
            if (projectPath != null) out.put("project_path", projectPath.toAbsolutePath().normalize().toString());
            out.put("overview", overview.toStats());
            out.put("entry_candidates", entries.rows());
            out.put("domain_candidates", domains.rows());
            out.put("repositories", repositories.rows());
            out.put("events", events.rows());
            out.put("smells", List.of());
            out.put("candidate_sources", candidateSources);
            out.put("budget", Map.of(
                    "mode", "sections",
                    "limit_per_section", limit,
                    "entry_candidates", entries.rows().size(),
                    "domain_candidates", domains.rows().size(),
                    "repositories", repositories.rows().size(),
                    "events", events.rows().size()));
            out.put("warnings", warnings);
            out.put("errors", List.of());
            out.put("next_queries", List.of(
                    "anatomist overview --depth 2 --format json --index " + db,
                    "anatomist search ENTRY --by-role --index " + db,
                    "anatomist context <EntryClass> --members-limit 50 --index " + db,
                    "anatomist callees-of <Entry#method> --depth 3 --limit 50 --index " + db));

            if ("json".equalsIgnoreCase(format)) {
                System.out.println(Json.writePretty(out));
            } else {
                System.out.println("survey-baseline: entries=" + entries.rows().size()
                        + " domains=" + domains.rows().size()
                        + " repositories=" + repositories.rows().size()
                        + " events=" + events.rows().size());
            }
            return 0;
        }
    }

    private static CandidateSection collectCandidates(int limit, CandidateQuery... queries) {
        Map<String, NodeRow> byId = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (CandidateQuery query : queries) {
            List<NodeRow> rows = query.rows() == null ? List.of() : query.rows();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("source", query.source());
            source.put("matches", rows.size());
            sources.add(source);
            for (NodeRow row : rows) {
                if (byId.size() >= limit) break;
                byId.putIfAbsent(row.id, row);
            }
        }
        return new CandidateSection(new ArrayList<>(byId.values()), sources);
    }

    private record CandidateQuery(String source, List<NodeRow> rows) {}
    private record CandidateSection(List<NodeRow> rows, List<Map<String, Object>> sources) {}
}
