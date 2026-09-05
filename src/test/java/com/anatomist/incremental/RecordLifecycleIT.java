package com.anatomist.incremental;

import com.anatomist.cli.IndexCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RecordLifecycleIT {
    @Test
    void recordStaysJavaCoreAndContractChangeRealignsCrossFileCaller(@TempDir Path tmp) throws Exception {
        Path source = Path.of(System.getProperty("user.dir")).resolve("fixtures/extension-lifecycle");
        Path project = tmp.resolve("project");
        copy(source, project);
        Path db = tmp.resolve("index.db");
        assertEquals(0, run(project, db, false));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertEquals(1, scalar(statement,
                    "SELECT count(*) FROM nodes WHERE symbol_id='example.Account' "
                            + "AND kind='RECORD' AND producer_id='java-core'"));
            assertEquals(1, scalar(statement,
                    "SELECT count(*) FROM nodes WHERE symbol_id='example.Account#id()' "
                            + "AND producer_id='java-core'"));
            assertEquals(1, scalar(statement,
                    "SELECT count(*) FROM call_site_targets cst JOIN nodes t ON t.id=cst.target_id "
                            + "WHERE t.symbol_id='example.Account#id()' "
                            + "AND cst.producer_id='java-core'"));
        }

        String readerIndexedAt;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT last_indexed FROM file_cache "
                     + "WHERE source_file LIKE '%/AccountReader.java'")) {
            assertTrue(rows.next());
            readerIndexedAt = rows.getString(1);
        }
        Path record = project.resolve("src/main/java/example/Account.java");
        Files.writeString(record, Files.readString(record).replace(
                "String id, String owner", "String key, String owner"));
        assertEquals(0, run(project, db, true));

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT last_indexed FROM file_cache "
                     + "WHERE source_file LIKE '%/AccountReader.java'")) {
            assertTrue(rows.next());
            assertNotEquals(readerIndexedAt, rows.getString(1),
                    "record contract change should realign its cross-file caller");
        }
    }

    private static int run(Path project, Path db, boolean incremental) {
        IndexCommand command = new IndexCommand();
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                project.toString(), "--project-source", project.resolve("src/main/java").toString(),
                "--no-classpath", "--spring-xml", "--java-version", "25",
                "--output", db.toString()));
        if (incremental) args.add("--incremental");
        new CommandLine(command).parseArgs(args.toArray(String[]::new));
        return command.call();
    }

    private static int scalar(java.sql.Statement statement, String sql) throws Exception {
        try (var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }

    private static void copy(Path source, Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
