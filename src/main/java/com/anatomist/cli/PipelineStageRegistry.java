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
        SemanticOperationRegistry.Invocation invocation =
                SemanticOperationRegistry.invocation(name, command, position);
        return new Stage(invocation.position(), invocation.id(), invocation.command(),
                invocation.accepted(), invocation.emitted(), invocation.producerOnly());
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
