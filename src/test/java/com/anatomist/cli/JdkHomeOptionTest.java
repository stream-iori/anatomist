package com.anatomist.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkHomeOptionTest {

    @Test
    void indexParsesJdkHome() {
        IndexCommand command = new IndexCommand();
        new CommandLine(command).parseArgs(".", "--jdk-home", "/opt/jdk-17");
        assertEquals(Path.of("/opt/jdk-17"), command.jdkHome);
    }

    @Test
    void watchParsesJdkHome() {
        WatchCommand command = new WatchCommand();
        new CommandLine(command).parseArgs(".", "--jdk-home", "/opt/jdk-17");
        assertEquals(Path.of("/opt/jdk-17"), command.jdkHome);
    }
}
