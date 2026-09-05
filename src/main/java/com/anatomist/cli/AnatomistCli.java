package com.anatomist.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

@Command(
        name = "anatomist",
        mixinStandardHelpOptions = true,
        versionProvider = BuildVersionProvider.class,
        description = "Java code intelligence tool with a semantic-stream/v1 query contract.",
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
                "  anatomist index . --format json           Build index JSON summary",
                "  anatomist overview                       Structural baseline",
                "  anatomist search OrderService             Find entity candidates",
                "  anatomist resolve 'Class#method()' --kind callable --exact --unique |",
                "    anatomist calls | anatomist dispatch | anatomist source",
                "  anatomist declarations-of --file src/main/java/com/example/MyClass.java",
                "",
                "@|bold Workflow:|@ index → query (index is slow, queries are ms-level)",
                "@|bold Output:|@   Query commands emit semantic-stream/v1; NDJSON is the default.",
                ""
        },
        commandListHeading = "%n@|bold Commands:|@%n",
        subcommands = {
                HelpCommand.class,
                SkillCommand.class,
                IndexCommand.class,
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
    private static CommandLine commandLine(String[] args) {
        if (args.length == 1 && ("-V".equals(args[0]) || "--version".equals(args[0]))) {
            return new CommandLine(new RuntimeRoot());
        }
        if (args.length > 0) {
            Object command = directCommand(args[0]);
            if (command != null) {
                return new CommandLine(new RuntimeRoot()).addSubcommand(args[0], command);
            }
        }
        return new CommandLine(new AnatomistCli());
    }

    private static Object directCommand(String name) {
        return switch (name) {
            case "skill" -> new SkillCommand();
            case "index" -> new IndexCommand();
            case "index-docs" -> new IndexDocsCommand();
            case "search" -> new SearchCommand();
            case "resolve" -> new ResolveCommand();
            case "calls" -> new CallsCommand();
            case "type-relations" -> new TypeRelationsCommand();
            case "runtime-implementations" -> new RuntimeImplementationsCommand();
            case "callable-relations" -> new CallableRelationsCommand();
            case "dispatch" -> new DispatchCommand();
            case "describe" -> new DescribeCommand();
            case "members" -> new MembersCommand();
            case "bindings" -> new BindingsCommand();
            case "annotations" -> new AnnotationsCommand();
            case "related-docs" -> new RelatedDocsCommand();
            case "references" -> new ReferencesCommand();
            case "accesses" -> new AccessesCommand();
            case "regions" -> new RegionsCommand();
            case "sites-in" -> new SitesInCommand();
            case "trace" -> new TraceCommand();
            case "source" -> new SourceCommand();
            case "declarations-of" -> new DeclarationsOfCommand();
            case "overview" -> new OverviewCommand();
            case "annotate" -> new AnnotateCommand();
            case "doctor" -> new DoctorCommand();
            default -> null;
        };
    }

    @Command(name = "anatomist", mixinStandardHelpOptions = true,
            versionProvider = BuildVersionProvider.class,
            description = "Java code intelligence tool with a semantic-stream/v1 query contract.")
    private static final class RuntimeRoot implements Runnable {
        @Override public void run() { }
    }
}
