package com.anatomist.cli;

import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class PipelineStageRegistry {
    private PipelineStageRegistry() {}

    static Stage parse(List<String> argv, int position, Path index,
                       String module, String scope) {
        if (argv.isEmpty()) throw PipelineFailure.invalid(
                "PIPELINE_INVALID_SPEC", "pipeline stage must not be empty");
        String name = argv.getFirst();
        rejectCommonOptions(argv, position, name);
        Object raw = AnatomistCli.directCommand(name);
        if (!(raw instanceof SemanticCommand command)) {
            throw PipelineFailure.invalidStage(position, name,
                    "pipeline only accepts read-only semantic-stream commands; got " + name);
        }
        try {
            new CommandLine(command).parseArgs(argv.subList(1, argv.size()).toArray(String[]::new));
        } catch (CommandLine.ParameterException failure) {
            throw PipelineFailure.invalidStage(position, name, failure.getMessage());
        }
        command.index = index;
        command.module = module;
        command.scope = scope;
        command.format = "ndjson";
        return describe(name, command, position);
    }

    static void validateComposition(List<Stage> stages) {
        for (int index = 1; index < stages.size(); index++) {
            Stage previous = stages.get(index - 1);
            Stage current = stages.get(index);
            if (current.producerOnly()) {
                throw incompatible(current.position(), current.name(),
                        "producer command is only valid as the first stage");
            }
            if (!current.accepted().containsAll(previous.emitted())) {
                throw incompatible(current.position(), current.name(),
                        previous.name() + " emits " + previous.emitted()
                                + " but " + current.name() + " accepts " + current.accepted());
            }
        }
    }

    private static Stage describe(String name, SemanticCommand command, int position) {
        Set<String> emitted;
        boolean producer = false;
        if (command instanceof SearchCommand search) {
            emitted = Set.of(search.count ? "result_count" : "entity_candidate");
            producer = true;
        } else if (command instanceof ResolveCommand resolve) {
            emitted = Set.of("entity");
            producer = resolve.selector != null && !resolve.selector.isBlank();
        } else if (command instanceof CallsCommand) emitted = Set.of("call_site");
        else if (command instanceof TypeRelationsCommand) emitted = Set.of("type_relation");
        else if (command instanceof RuntimeImplementationsCommand) emitted = Set.of("entity");
        else if (command instanceof CallableRelationsCommand) emitted = Set.of("callable_relation");
        else if (command instanceof DispatchCommand) emitted = Set.of("dispatch_target");
        else if (command instanceof DescribeCommand) emitted = Set.of("declaration");
        else if (command instanceof MembersCommand) emitted = Set.of("entity");
        else if (command instanceof BindingsCommand) emitted = Set.of("binding_relation");
        else if (command instanceof AnnotationsCommand) emitted = Set.of("annotation");
        else if (command instanceof RelatedDocsCommand) emitted = Set.of("document_relation");
        else if (command instanceof ReferencesCommand) emitted = Set.of("reference_site");
        else if (command instanceof AccessesCommand) emitted = Set.of("access_site");
        else if (command instanceof RegionsCommand) emitted = Set.of("control_region");
        else if (command instanceof SitesInCommand sites) {
            emitted = switch (sites.recordType) {
                case "call_site" -> Set.of("call_site");
                case "access_site" -> Set.of("access_site");
                default -> Set.of("call_site", "access_site");
            };
        } else if (command instanceof TraceCommand) emitted = Set.of("trace");
        else if (command instanceof SourceCommand) emitted = Set.of("source_slice");
        else if (command instanceof DeclarationsOfCommand) {
            emitted = Set.of("entity"); producer = true;
        } else if (command instanceof OverviewCommand) {
            emitted = Set.of("project_summary", "package_summary", "package_dependency");
            producer = true;
        } else {
            throw PipelineFailure.invalidStage(position, name,
                    "unsupported semantic pipeline command: " + name);
        }
        return new Stage(position, name, command, command.acceptedInputRecords(),
                emitted, producer);
    }

    private static void rejectCommonOptions(List<String> argv, int position, String command) {
        for (String token : argv) {
            if (token.equals("--index") || token.startsWith("--index=")
                    || token.equals("--module") || token.startsWith("--module=")
                    || token.equals("--scope") || token.startsWith("--scope=")
                    || token.equals("--format") || token.startsWith("--format=")
                    || token.equals("--help") || token.equals("-h")
                    || token.equals("--version") || token.equals("-V")) {
                throw PipelineFailure.invalidStage(position, command,
                        token + " is a pipeline-level option or unsupported inside a stage");
            }
        }
    }

    private static PipelineFailure incompatible(int stage, String command, String message) {
        return PipelineFailure.invalidStageWithCode("PIPELINE_INCOMPATIBLE_STAGES",
                stage, command, message);
    }

    record Stage(int position, String name, SemanticCommand command,
                 Set<String> accepted, Set<String> emitted, boolean producerOnly) {}

}
