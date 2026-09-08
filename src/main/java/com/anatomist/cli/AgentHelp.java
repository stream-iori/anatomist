package com.anatomist.cli;

import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Renders help from the same command models and record contracts used by execution. */
public final class AgentHelp implements CommandLine.IModelTransformer {
    static final Map<String, List<String>> COMMAND_GROUPS = groups();
    private static final Set<String> SHARED = Set.of("--index", "--ref", "--snapshot",
            "--project", "--module", "--scope", "--language", "--provider", "--format", "--view",
            "--on-unsupported", "--accept-unframed", "--help", "--version");

    @Override public CommandSpec transform(CommandSpec spec) {
        configure(spec);
        return spec;
    }

    static void configure(CommandSpec spec) {
        spec.subcommands().values().forEach(child -> configure(child.getCommandSpec()));
        var usage = spec.usageMessage();
        usage.abbreviateSynopsis(true);
        usage.optionListHeading("");
        usage.sectionMap().put("optionList", AgentHelp::options);
        // Preserve executable example lines rather than wrapping them into invalid shell commands.
        usage.sectionMap().put("footer", help -> String.join(System.lineSeparator(),
                help.commandSpec().usageMessage().footer()).replace("%n", System.lineSeparator())
                + System.lineSeparator());
        if (spec.userObject() instanceof AnatomistCli) {
            usage.commandListHeading("");
            usage.sectionMap().put("commandList", AgentHelp::commands);
        }
        if (spec.userObject() instanceof SemanticCommand) {
            var keys = new ArrayList<>(usage.sectionKeys());
            if (!keys.contains("semanticContract")) {
                keys.add(keys.indexOf("description") + 1, "semanticContract");
            }
            usage.sectionKeys(keys);
            // Defer until rendering: command defaults and the registry are then initialized.
            usage.sectionMap().put("semanticContract", help -> contract(
                    (SemanticCommand) help.commandSpec().userObject()));
        }
    }

    static String contract(SemanticCommand command) {
        var entry = SemanticOperationRegistry.entry(command);
        String accepts = command.acceptedInputRecords().stream().sorted()
                .collect(Collectors.joining(" | "));
        if (!entry.entityKinds().isEmpty()) {
            accepts += " (entity kinds: " + String.join(", ", entry.entityKinds()) + ")";
        }
        if ("producer".equals(entry.role())) accepts = "CLI selector";
        else if ("hybrid".equals(entry.role())) accepts += "; or one CLI selector";
        var lines = new ArrayList<String>();
        lines.add("");
        lines.add("Accepts: " + accepts);
        for (var variant : SemanticOperationRegistry.outputVariants(entry, command)) {
            @SuppressWarnings("unchecked")
            var records = (List<String>) variant.get("records");
            String condition = "";
            if (variant.get("when") instanceof Map<?, ?> when) {
                condition = " [" + when.get("option") + "=" + when.get("equals") + "]";
            }
            lines.add("Emits: " + records.stream().sorted().collect(Collectors.joining(" | "))
                    + " + evidence" + condition);
        }
        lines.add("Operation: " + entry.id() + "; contract/support: anatomist operations " + entry.id());
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static String options(Help help) {
        var options = help.commandSpec().options().stream().filter(o -> !o.hidden()).toList();
        var primary = options.stream().filter(o -> !shared(o)).toList();
        var shared = options.stream().filter(AgentHelp::shared).toList();
        return optionGroup(help, "Arguments", primary) + optionGroup(help, "Selection and output", shared);
    }

    private static boolean shared(OptionSpec option) {
        return List.of(option.names()).stream().anyMatch(SHARED::contains);
    }

    private static String optionGroup(Help help, String title, List<OptionSpec> options) {
        if (options.isEmpty()) return "";
        return System.lineSeparator() + title + ":" + System.lineSeparator()
                + help.optionListExcludingGroups(options);
    }

    private static String commands(Help help) {
        var out = new StringBuilder();
        COMMAND_GROUPS.forEach((title, names) -> {
            var selected = new LinkedHashMap<String, Help>();
            for (String name : names) {
                Help child = help.subcommands().get(name);
                if (child != null) selected.put(name, child);
            }
            if (!selected.isEmpty()) out.append(System.lineSeparator()).append(title)
                    .append(':').append(System.lineSeparator()).append(help.commandList(selected));
        });
        return out.toString();
    }

    private static Map<String, List<String>> groups() {
        var groups = new LinkedHashMap<String, List<String>>();
        groups.put("Locate and read", List.of("search", "resolve", "declarations-of", "source",
                "describe", "members", "overview", "related-docs"));
        groups.put("Calls and relations", List.of("calls", "dispatch", "trace", "type-relations",
                "runtime-implementations", "callable-relations", "references", "accesses", "regions", "sites-in"));
        groups.put("Annotations and configuration", List.of("annotations", "bindings", "annotate"));
        groups.put("Index and versions", List.of("index", "index-docs", "diff", "snapshots"));
        groups.put("Guidance and inspection", List.of("skill", "pipeline", "operations", "doctor", "help"));
        return java.util.Collections.unmodifiableMap(groups);
    }
}
