package com.anatomist.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "anatomist",
        mixinStandardHelpOptions = true,
        version = "anatomist 0.1.0",
        description = "JavaParser + SymbolSolver based Java code intelligence for Agent LLMs",
        subcommands = {
                IndexCommand.class
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
