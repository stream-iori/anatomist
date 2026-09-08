package com.anatomist.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

@Command(
        name = "anatomist",
        mixinStandardHelpOptions = true,
        versionProvider = BuildVersionProvider.class,
        description = "Provider-aware code intelligence with a semantic-stream/v1 query contract.",
        header = {
                "",
                "@|bold anatomist|@ — Java code intelligence for Agent LLMs",
                ""
        },
        footer = {
                "",
                "@|bold Quick Start:|@",
                "  anatomist index /path/to/project          Index a Java project",
                "  anatomist skill topics                   Choose Agent task guidance",
                "  anatomist doctor --format json            Check CLI/schema/index",
                "  anatomist operations calls --index index.db  Inspect one operation",
                "  anatomist index . --format json           Build index JSON summary",
                "  anatomist overview                       Structural baseline",
                "  anatomist search OrderService             Find entity candidates",
                "  anatomist pipeline --index index.db -- resolve 'Class#method()'",
                "    --kind callable --exact --unique --then calls --then source",
                "  anatomist pipeline --help                 Learn fused pipeline rules",
                "  anatomist declarations-of --file src/main/java/com/example/MyClass.java",
                "",
                "@|bold Workflow:|@ index → query (index is slow, queries are ms-level)",
                "@|bold Output:|@   Query commands emit semantic-stream/v1; NDJSON is the default.",
                "@|bold Inspect:|@  Use help, operations <operation>, doctor, explain or check when needed.",
                "@|bold Compose:|@  Prefer pipeline for linear multi-stage queries; use Shell for external tools or distinct scopes.",
                ""
        },
        commandListHeading = "%n@|bold Commands:|@%n",
        subcommands = {
                HelpCommand.class,
                SkillCommand.class,
                IndexCommand.class,
                DiffCommand.class,
                SnapshotsCommand.class,
                IndexDocsCommand.class,
                SearchCommand.class,
                ResolveCommand.class,
                CallsCommand.class,
                TypeRelationsCommand.class,
                RuntimeImplementationsCommand.class,
                CallableRelationsCommand.class,
                DispatchCommand.class,
                DescribeCommand.class,
                MembersCommand.class,
                BindingsCommand.class,
                AnnotationsCommand.class,
                RelatedDocsCommand.class,
                ReferencesCommand.class,
                AccessesCommand.class,
                RegionsCommand.class,
                SitesInCommand.class,
                TraceCommand.class,
                SourceCommand.class,
                DeclarationsOfCommand.class,
                OverviewCommand.class,
                OperationsCommand.class,
                PipelineCommand.class,
                AnnotateCommand.class,
                DoctorCommand.class
        }
)
public class AnatomistCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = commandLine(args)
                .setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.AUTO))
                .execute(args);
        System.exit(exitCode);
    }

    /**
     * A normal invocation only needs one subcommand model. Building every Picocli model
     * up front costs measurable native-image RSS, so reserve the complete model for root
     * help, {@code help <command>}, and unknown-command diagnostics.
     */
    static CommandLine commandLine(String[] args) {
        if (args.length == 1 && ("-V".equals(args[0]) || "--version".equals(args[0]))) {
            return new CommandLine(new RuntimeRoot());
        }
        if (args.length > 0) {
            Object command = directCommand(args[0]);
            if (command != null) {
                CommandLine line = new CommandLine(new RuntimeRoot())
                        .addSubcommand(args[0], command);
                if (command instanceof SemanticCommand
                        || command instanceof PipelineCommand
                        || command instanceof OperationsCommand
                        || command instanceof DiffCommand || command instanceof SnapshotsCommand) {
                    line.setParameterExceptionHandler((failure, parsed) -> {
                        String operation = failure.getCommandLine().getCommandName();
                        if (command instanceof PipelineCommand) {
                            var error = CliError.base("PIPELINE_INVALID_SPEC", "pipeline", 5,
                                    failure.getMessage(), "pipeline");
                            CliError.emit(failure.getCommandLine().getErr(), error);
                            return 5;
                        }
                        var error = CliError.base("INVALID_ARGUMENT", "argument", 2,
                                failure.getMessage(), operation);
                        error.put("details", java.util.Map.of(
                                "arguments", java.util.List.of(parsed)));
                        CliError.emit(failure.getCommandLine().getErr(), error);
                        return 2;
                    });
                }
                return line;
            }
        }
        return new CommandLine(new AnatomistCli());
    }

    static Object directCommand(String name) {
        SemanticCommand semantic = SemanticOperationRegistry.create(name);
        if (semantic != null) return semantic;
        return switch (name) {
            case "skill" -> new SkillCommand();
            case "index" -> new IndexCommand();
            case "diff" -> new DiffCommand();
            case "snapshots" -> new SnapshotsCommand();
            case "index-docs" -> new IndexDocsCommand();
            case "pipeline" -> new PipelineCommand();
            case "operations" -> new OperationsCommand();
            case "annotate" -> new AnnotateCommand();
            case "doctor" -> new DoctorCommand();
            default -> null;
        };
    }

    @Command(name = "anatomist", mixinStandardHelpOptions = true,
            versionProvider = BuildVersionProvider.class,
            description = "Provider-aware code intelligence with a semantic-stream/v1 query contract.")
    private static final class RuntimeRoot implements Runnable {
        @Override public void run() { }
    }
}
