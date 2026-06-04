package com.anatomist.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "anatomist",
        mixinStandardHelpOptions = true,
        version = "anatomist 0.1.0",
        description = "JavaParser + SymbolSolver based Java code intelligence for Agent LLMs",
        subcommands = {
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
                ExportCommand.class,
                AnnotateCommand.class
        }
)
public class AnatomistCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AnatomistCli()).execute(args);
        System.exit(exitCode);
    }
}
