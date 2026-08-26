package com.anatomist.cli;

import com.anatomist.query.BeanConfigService;
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
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "bean-config",
        mixinStandardHelpOptions = true,
        description = "Show structured Spring XML bean config tree for property/map/list/ref definitions.")
public class BeanConfigCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Bean name, label, or substring, e.g. FilterRegistry.")
    String target;

    @Option(names = "--property", description = "Only show one XML property.")
    String property;

    @Option(names = "--format", description = "Output format: text | json.", defaultValue = "text")
    String format;

    @Option(names = "--index", description = "Path to index.db.")
    Path index;

    @Option(names = "--module", description = "Restrict bean matches to one module.")
    String module;

    @Option(names = "--scope", description = "MAIN | TEST | GENERATED | ALL.", defaultValue = "MAIN")
    String scope;

    @Option(names = "--limit", description = "Max beans per page (default 20).", defaultValue = "20")
    int limit;

    @Option(names = "--offset", description = "Skip N matching beans.", defaultValue = "0")
    int offset;

    @Override
    public Integer call() {
        try {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("bean target is required");
            }
            format = CliValidation.choice("--format", format, "text", "json");
            scope = CliValidation.scope(scope, true);
            CliValidation.positive("--limit", limit);
            CliValidation.nonNegative("--offset", offset);
            Path db = IndexPath.resolve(index);
            try (QueryService q = new QueryService(db)) {
                q.selectNodes(module, scope);
                PagedResult<Map<String, Object>> page = new BeanConfigService(q.connection())
                        .beanConfigPaged(target, property, module, scope, limit, offset);
                List<Map<String, Object>> results = page.items();
            if ("json".equals(format)) {
                QueryEnvelope env = new QueryEnvelope(buildQueryString(offset), results);
                Disclosure.putPaging(env, page.total(), limit, page.offset());
                Disclosure.putBudget(env, "beans", results.size(), page.total());
                env.evidence = new QueryCoverageService(q.connection()).assess(
                        QueryCoverageService.Capability.WIRING,
                        List.of(target), module, scope, !results.isEmpty(), false);
                if (page.truncated()) {
                    List<String> next = buildArgs(page.offset() + limit);
                    Disclosure.addOption(next, "--index", db);
                    env.nextQueries = List.of(Disclosure.renderCommand(next));
                }
                JsonFormatter.emit(System.out, env);
            } else {
                renderText(results);
                if (page.truncated()) {
                    System.out.println("... truncated; continue with --offset " + (page.offset() + limit));
                }
            }
            return results.isEmpty() ? 2 : 0;
            }
        } catch (IllegalArgumentException failure) {
            return CliValidation.emit(failure);
        }
    }

    private void renderText(List<Map<String, Object>> results) {
        for (Map<String, Object> bean : results) {
            System.out.println(bean.get("label") + " (" + bean.get("source_file") + ")");
            Object children = bean.get("children");
            if (children instanceof Iterable<?> it) {
                for (Object child : it) renderNode(child, "", true);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void renderNode(Object raw, String prefix, boolean last) {
        if (!(raw instanceof Map<?, ?> any)) return;
        Map<String, Object> node = (Map<String, Object>) any;
        String branch = last ? "`- " : "|- ";
        System.out.println(prefix + branch + label(node));
        Object children = node.get("children");
        if (children instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                renderNode(list.get(i), prefix + (last ? "   " : "|  "), i == list.size() - 1);
            }
        }
    }

    private String label(Map<String, Object> node) {
        String kind = String.valueOf(node.get("xmlKind"));
        if ("entry".equals(kind) && node.get("key") != null) return String.valueOf(node.get("key"));
        if ("property".equals(kind) && node.get("name") != null) return String.valueOf(node.get("name"));
        if (("ref".equals(kind) || "idref".equals(kind)) && node.get("bean") != null) {
            return kind + " " + node.get("bean");
        }
        if ("value".equals(kind) && node.get("value") != null) return "value " + node.get("value");
        if (node.get("index") != null) return kind + "[" + node.get("index") + "]";
        return kind;
    }

    private String buildQueryString(int effectiveOffset) {
        return Disclosure.renderCommand(buildArgs(effectiveOffset));
    }

    private List<String> buildArgs(int effectiveOffset) {
        List<String> args = new java.util.ArrayList<>(List.of("bean-config", target));
        Disclosure.addOption(args, "--property", property);
        Disclosure.addOption(args, "--format", format);
        Disclosure.addOption(args, "--module", module);
        Disclosure.addOption(args, "--scope", scope);
        Disclosure.addOption(args, "--limit", limit);
        if (effectiveOffset > 0) Disclosure.addOption(args, "--offset", effectiveOffset);
        return args;
    }
}
