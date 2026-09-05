package com.anatomist.cli;

import picocli.CommandLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Single registration point for public semantic operations and their typed contracts. */
final class SemanticOperationRegistry {
    private static final List<Entry> ENTRIES = entries();
    private static final Map<String, Entry> BY_ID = byId();

    private SemanticOperationRegistry() {}

    static List<Entry> entriesView() { return ENTRIES; }

    static SemanticCommand create(String id) {
        Entry entry = BY_ID.get(id);
        return entry == null ? null : entry.factory().get();
    }

    static Entry entry(String id) { return BY_ID.get(id); }

    static Entry entry(SemanticCommand command) {
        for (Entry entry : ENTRIES) {
            if (entry.commandType().equals(command.getClass())) return entry;
        }
        throw new IllegalArgumentException("unregistered semantic operation: "
                + command.getClass().getName());
    }

    static Invocation invocation(String id, SemanticCommand command, int position) {
        Entry entry = BY_ID.get(id);
        if (entry == null || !entry.commandType().equals(command.getClass())) {
            throw PipelineFailure.invalidStage(position, id,
                    "unsupported semantic pipeline command: " + id);
        }
        return new Invocation(position, id, command, command.acceptedInputRecords(),
                entry.emitted().apply(command), entry.producerOnly().test(command),
                entry.role());
    }

    static Map<String, Object> catalogEntry(Entry entry, String language,
                                             Boolean available) {
        SemanticCommand command = entry.factory().get();
        CommandLine.Model.CommandSpec spec = new CommandLine(command).getCommandSpec();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.id());
        out.put("role", entry.role());
        out.put("summary", String.join(" ", spec.usageMessage().description()));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("sources", switch (entry.role()) {
            case "producer" -> List.of("cli");
            case "hybrid" -> List.of("cli", "stream");
            default -> List.of("stream");
        });
        input.put("records", List.copyOf(command.acceptedInputRecords()));
        if (!entry.entityKinds().isEmpty()) input.put("entity_kinds", entry.entityKinds());
        out.put("input", input);
        out.put("output", Map.of("variants", outputVariants(entry, command)));
        out.put("arguments", optionContracts(spec));
        out.put("constraints", constraints(entry.id()));
        out.put("limits", entry.limits());

        Map<String, Object> support = new LinkedHashMap<>();
        support.put("language", language);
        boolean installed = "java".equals(language);
        support.put("provider", installed ? "java-core" : "none");
        support.put("support", installed ? "supported" : "unsupported");
        support.put("availability", !installed ? "unavailable"
                : available == null ? "unchecked" : available ? "available" : "unavailable");
        if (!installed) support.put("reason", "PROVIDER_NOT_INSTALLED");
        if (installed && !entry.limitations().isEmpty()) {
            support.put("limitations", entry.limitations());
        }
        out.put("support", List.of(support));
        return out;
    }

    private static List<Map<String, Object>> outputVariants(Entry entry,
                                                             SemanticCommand command) {
        if (command instanceof SearchCommand) {
            return List.of(
                    variant(List.of("entity_candidate"), "--count", false),
                    variant(List.of("result_count"), "--count", true));
        }
        if (command instanceof SitesInCommand) {
            return List.of(
                    variant(List.of("call_site"), "--record", "call_site"),
                    variant(List.of("access_site"), "--record", "access_site"),
                    variant(List.of("call_site", "access_site"), "--record", "all"));
        }
        return List.of(Map.of("records", List.copyOf(entry.emitted().apply(command))));
    }

    private static Map<String, Object> variant(List<String> records, String option,
                                                Object equals) {
        return Map.of("records", records,
                "when", Map.of("option", option, "equals", equals));
    }

    private static List<Map<String, Object>> optionContracts(
            CommandLine.Model.CommandSpec spec) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CommandLine.Model.ArgSpec arg : spec.args()) {
            if (arg.hidden()) continue;
            Map<String, Object> out = new LinkedHashMap<>();
            if (arg instanceof CommandLine.Model.OptionSpec option) {
                if (option.usageHelp() || option.versionHelp()) continue;
                if (List.of(option.names()).stream().anyMatch(name -> Set.of(
                        "--index", "--module", "--scope", "--format",
                        "--accept-unframed", "--on-unsupported").contains(name))) continue;
                out.put("names", List.of(option.names()));
                out.put("kind", "option");
            } else if (arg instanceof CommandLine.Model.PositionalParamSpec positional) {
                out.put("name", positional.paramLabel());
                out.put("kind", "positional");
                out.put("index", positional.index().min());
            }
            out.put("value_type", valueType(arg.type()));
            out.put("required", arg.required());
            out.put("arity", arg.arity().toString());
            if (arg.defaultValue() != null) out.put("default", arg.defaultValue());
            if (arg.description().length > 0) {
                out.put("description", String.join(" ", arg.description()));
            }
            result.add(out);
        }
        return List.copyOf(result);
    }

    private static String valueType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type == int.class || type == Integer.class || type == long.class
                || type == Long.class) return "integer";
        if (java.nio.file.Path.class.isAssignableFrom(type)) return "path";
        return "string";
    }

    private static List<Map<String, Object>> constraints(String operation) {
        List<Map<String, Object>> out = new ArrayList<>();
        switch (operation) {
            case "search" -> {
                out.add(Map.of("kind", "open_enum", "option", "--kind",
                        "values", List.of("type", "callable", "value", "artifact",
                                "component", "config_entity", "entity"),
                        "also_accepts", "uppercase_storage_kind"));
                out.add(range("--limit", 1, 100_000)); out.add(range("--offset", 0, null));
            }
            case "resolve" -> out.add(choice("--kind",
                    List.of("entity", "type", "callable", "value")));
            case "members" -> {
                out.add(range("--max-depth", 1, null)); out.add(range("--limit", 1, null));
            }
            case "type-relations" -> {
                out.add(choice("--direction", List.of("outgoing", "incoming")));
                out.add(choice("--semantic", List.of("any", "subtype-of", "conforms-to")));
                out.add(range("--max-depth", 1, null)); out.add(range("--limit", 1, 100_000));
            }
            case "runtime-implementations" -> {
                out.add(choice("--instantiability", List.of("yes", "unknown", "all")));
                out.add(choice("--world", List.of("workspace-open", "workspace-closed", "classpath-open")));
                out.add(range("--max-depth", 1, null)); out.add(range("--limit", 1, null));
            }
            case "callable-relations" -> {
                out.add(choice("--direction", List.of("outgoing", "incoming")));
                out.add(range("--max-depth", 1, null)); out.add(range("--limit", 1, null));
            }
            case "calls" -> {
                out.add(choice("--direction", List.of("outgoing", "incoming")));
                out.add(range("--limit", 1, 100_000));
            }
            case "dispatch" -> {
                out.add(choice("--algorithm", List.of("auto", "exact", "cha")));
                out.add(choice("--world", List.of("workspace-open", "workspace-closed", "classpath-open")));
                out.add(range("--max-depth", 1, null)); out.add(range("--limit", 1, null));
            }
            case "bindings" -> {
                out.add(choice("--direction", List.of("outgoing", "incoming")));
                out.add(choice("--semantic", List.of("any", "realizes", "wires", "parent", "factory")));
                out.add(range("--limit", 1, null));
            }
            case "references" -> {
                out.add(choice("--direction", List.of("outgoing", "incoming")));
                out.add(range("--limit", 1, null));
            }
            case "accesses" -> {
                out.add(choice("--mode", List.of("reads", "writes", "all")));
                out.add(range("--limit", 1, null));
            }
            case "regions" -> {
                out.add(choice("--kind", List.of("branch", "loop", "all")));
                out.add(range("--limit", 1, null));
            }
            case "sites-in" -> {
                out.add(choice("--record", List.of("call_site", "access_site", "all")));
                out.add(range("--limit", 1, null));
            }
            case "trace" -> {
                out.add(choice("--dispatch", List.of("resolved", "possible")));
                out.add(range("--max-depth", 1, null));
            }
            case "source" -> {
                out.add(range("--limit", 1, 1000)); out.add(range("--offset", 0, null));
            }
            case "declarations-of" -> {
                out.add(range("--limit", 1, 1000)); out.add(range("--offset", 0, null));
                out.add(Map.of("kind", "path", "option", "--file",
                        "rules", List.of("project_relative", "normalized", "suffix:.java")));
            }
            case "overview" -> {
                out.add(range("--depth", 0, null)); out.add(range("--limit", 0, null));
                out.add(range("--offset", 0, null));
            }
            case "related-docs" -> out.add(range("--limit", 1, null));
            default -> { }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> choice(String option, List<String> values) {
        return Map.of("kind", "enum", "option", option, "values", values);
    }

    private static Map<String, Object> range(String option, Integer minimum,
                                              Integer maximum) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "range"); out.put("option", option);
        if (minimum != null) out.put("minimum", minimum);
        if (maximum != null) out.put("maximum", maximum);
        return out;
    }

    private static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(entry("search", "producer", SearchCommand.class, SearchCommand::new,
                c -> Set.of(((SearchCommand) c).count ? "result_count" : "entity_candidate"),
                c -> true, List.of(), List.of("FUZZY_RECALL")));
        entries.add(entry("resolve", "hybrid", ResolveCommand.class, ResolveCommand::new,
                c -> Set.of("entity"),
                c -> ((ResolveCommand) c).selector != null
                        && !((ResolveCommand) c).selector.isBlank(),
                List.of("entity", "type", "callable", "value"), List.of()));
        entries.add(transform("describe", DescribeCommand.class, DescribeCommand::new,
                Set.of("declaration"), List.of(), List.of()));
        entries.add(transform("members", MembersCommand.class, MembersCommand::new,
                Set.of("entity"), List.of("type", "component", "artifact"), List.of()));
        entries.add(transform("type-relations", TypeRelationsCommand.class,
                TypeRelationsCommand::new, Set.of("type_relation"), List.of("type"),
                List.of("OPEN_WORLD", "EXTERNAL_TYPE")));
        entries.add(transform("runtime-implementations", RuntimeImplementationsCommand.class,
                RuntimeImplementationsCommand::new, Set.of("entity"), List.of("type"),
                List.of("OPEN_WORLD", "DYNAMIC_LOADING")));
        entries.add(transform("callable-relations", CallableRelationsCommand.class,
                CallableRelationsCommand::new, Set.of("callable_relation"),
                List.of("callable"), List.of("EXTERNAL_TYPE")));
        entries.add(transform("calls", CallsCommand.class, CallsCommand::new,
                Set.of("call_site"), List.of("callable"),
                List.of("UNRESOLVED_CLASSPATH", "REFLECTION")));
        entries.add(transform("dispatch", DispatchCommand.class, DispatchCommand::new,
                Set.of("dispatch_target"), List.of(),
                List.of("OPEN_WORLD", "REFLECTION", "DYNAMIC_LOADING")));
        entries.add(transform("bindings", BindingsCommand.class, BindingsCommand::new,
                Set.of("binding_relation"), List.of(), List.of("RUNTIME_CONFIGURATION")));
        entries.add(transform("annotations", AnnotationsCommand.class, AnnotationsCommand::new,
                Set.of("annotation"), List.of(), List.of()));
        entries.add(transform("related-docs", RelatedDocsCommand.class, RelatedDocsCommand::new,
                Set.of("document_relation"), List.of(), List.of("HEURISTIC_ASSOCIATION")));
        entries.add(transform("references", ReferencesCommand.class, ReferencesCommand::new,
                Set.of("reference_site"), List.of(), List.of("UNRESOLVED_CLASSPATH")));
        entries.add(transform("accesses", AccessesCommand.class, AccessesCommand::new,
                Set.of("access_site"), List.of("value", "entity"), List.of()));
        entries.add(transform("regions", RegionsCommand.class, RegionsCommand::new,
                Set.of("control_region"), List.of("callable"), List.of()));
        entries.add(entry("sites-in", "transform", SitesInCommand.class, SitesInCommand::new,
                c -> switch (((SitesInCommand) c).recordType) {
                    case "call_site" -> Set.of("call_site");
                    case "access_site" -> Set.of("access_site");
                    default -> Set.of("call_site", "access_site");
                }, c -> false, List.of(), List.of()));
        entries.add(transform("trace", TraceCommand.class, TraceCommand::new,
                Set.of("trace"), List.of("callable"), List.of("BOUNDED_DEPTH", "OPEN_WORLD")));
        entries.add(transform("source", SourceCommand.class, SourceCommand::new,
                Set.of("source_slice"), List.of(), List.of("SOURCE_SNAPSHOT_STALE")));
        entries.add(entry("declarations-of", "producer", DeclarationsOfCommand.class,
                DeclarationsOfCommand::new, c -> Set.of("entity"), c -> true,
                List.of(), List.of()));
        entries.add(entry("overview", "producer", OverviewCommand.class, OverviewCommand::new,
                c -> Set.of("project_summary", "package_summary", "package_dependency"),
                c -> true, List.of(), List.of()));
        return List.copyOf(entries);
    }

    private static Entry transform(String id, Class<? extends SemanticCommand> type,
                                   Supplier<? extends SemanticCommand> factory,
                                   Set<String> emitted, List<String> kinds,
                                   List<String> limitations) {
        return entry(id, "transform", type, factory, c -> emitted, c -> false,
                kinds, limitations);
    }

    private static Entry entry(String id, String role,
                               Class<? extends SemanticCommand> commandType,
                               Supplier<? extends SemanticCommand> factory,
                               Function<SemanticCommand, Set<String>> emitted,
                               Predicate<SemanticCommand> producerOnly,
                               List<String> entityKinds, List<String> limitations) {
        return new Entry(id, role, commandType, factory, emitted, producerOnly,
                List.copyOf(entityKinds), List.copyOf(limitations),
                Map.of("records_per_stream", 1_000_000,
                        "records_per_seed", 100_000, "seeds", 100_000));
    }

    private static Map<String, Entry> byId() {
        Map<String, Entry> result = new LinkedHashMap<>();
        for (Entry entry : ENTRIES) {
            if (result.put(entry.id(), entry) != null) {
                throw new IllegalStateException("duplicate semantic operation: " + entry.id());
            }
        }
        return Map.copyOf(result);
    }

    record Entry(String id, String role, Class<? extends SemanticCommand> commandType,
                 Supplier<? extends SemanticCommand> factory,
                 Function<SemanticCommand, Set<String>> emitted,
                 Predicate<SemanticCommand> producerOnly, List<String> entityKinds,
                 List<String> limitations, Map<String, Object> limits) {}

    record Invocation(int position, String id, SemanticCommand command,
                      Set<String> accepted, Set<String> emitted,
                      boolean producerOnly, String role) {}
}
