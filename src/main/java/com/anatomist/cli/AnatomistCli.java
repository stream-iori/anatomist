package com.anatomist.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;

@Command(
        name = "anatomist",
        mixinStandardHelpOptions = true,
        version = "anatomist 0.1.0",
        description = "Java code intelligence tool — indexes source into SQLite for structural/semantic queries.",
        header = {
                "",
                "@|bold anatomist|@ — Java code intelligence for Agent LLMs",
                ""
        },
        footer = {
                "",
                "@|bold Quick Start:|@",
                "  anatomist index /path/to/project          Index a Java project",
                "  anatomist doctor --format json            Check CLI/schema/index",
                "  anatomist index . --format json           Build index JSON summary",
                "  anatomist survey-baseline . --format json Structural baseline",
                "  anatomist search OrderService             Find nodes by name",
                "  anatomist callees-of Class#method         Show outgoing calls",
                "  anatomist callees-of Class#method --source-window=3",
                "  anatomist context com.example.MyClass     Type overview + members",
                "",
                "@|bold Workflow:|@ index → query (index is slow, queries are ms-level)",
                "@|bold Output:|@   Query commands emit JSON; mutation commands support --format json where documented.",
                ""
        },
        commandListHeading = "%n@|bold Commands:|@%n",
        subcommands = {
                HelpCommand.class,
                IndexCommand.class,
                IndexDocsCommand.class,
                WatchCommand.class,
                SearchCommand.class,
                ContextCommand.class,
                CalleesOfCommand.class,
                CallersOfCommand.class,
                HierarchyCommand.class,
                ImplementorsOfCommand.class,
                DepsOfCommand.class,
                UsedByCommand.class,
                FieldAccessCommand.class,
                CallPathCommand.class,
                OverviewCommand.class,
                SurveyBaselineCommand.class,
                ExportCommand.class,
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
        int exitCode = new CommandLine(new AnatomistCli())
                .setColorScheme(CommandLine.Help.defaultColorScheme(CommandLine.Help.Ansi.AUTO))
                .execute(args);
        System.exit(exitCode);
    }
}
