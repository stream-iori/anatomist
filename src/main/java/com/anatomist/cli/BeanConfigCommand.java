package com.anatomist.cli;

import com.anatomist.query.BeanConfigService;
import com.anatomist.query.JsonFormatter;
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

    @Override
    public Integer call() {
        Path db = IndexPath.resolve(index);
        try (QueryService q = new QueryService(db)) {
            List<Map<String, Object>> results = new BeanConfigService(q.connection())
                    .beanConfig(target, property);
            if ("json".equalsIgnoreCase(format)) {
                QueryEnvelope env = new QueryEnvelope(buildQueryString(), results);
                env.evidence.putAll(new QueryCoverageService(q.connection()).assess(
                        QueryCoverageService.Capability.WIRING,
                        List.of(target), null, "MAIN", !results.isEmpty(), false).toMap());
                JsonFormatter.emit(System.out, env);
            } else {
                renderText(results);
            }
            return results.isEmpty() ? 2 : 0;
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

    private String buildQueryString() {
        StringBuilder sb = new StringBuilder("bean-config ").append(target);
        if (property != null) sb.append(" --property ").append(property);
        if (format != null) sb.append(" --format ").append(format);
        return sb.toString();
    }
}
