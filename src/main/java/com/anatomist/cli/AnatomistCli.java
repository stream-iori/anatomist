package com.anatomist.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

@Command(modelTransformer = AgentHelp.class,
        name = "anatomist",
        mixinStandardHelpOptions = true,
        versionProvider = BuildVersionProvider.class,
        description = "Locate Java source, inspect relationships and compare versions.",
        header = {
                "",
                "@|bold anatomist|@ — Java source evidence for agents",
                ""
        },
        footer = {
                "",
                "Read a method:",
                "  anatomist pipeline -- resolve 'p.Service#run()' --kind callable --exact --unique --then source",
                "",
                "Start: anatomist skill core       Task routes: anatomist skill topics",
                "Arguments: <command> --help       Contracts: operations <command>",
                "Index setup/recovery: skill maintenance; version questions: skill versions."
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
                AgentHelp.configure(line.getCommandSpec());
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

    @Command(modelTransformer = AgentHelp.class, name = "anatomist", mixinStandardHelpOptions = true,
            versionProvider = BuildVersionProvider.class,
            description = "Locate Java source, inspect relationships and compare versions.")
    private static final class RuntimeRoot implements Runnable {
        @Override public void run() { }
    }
}
