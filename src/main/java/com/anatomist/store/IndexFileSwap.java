package com.anatomist.store;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

/** Atomically promotes a complete replacement SQLite database where supported. */
public final class IndexFileSwap {
    private IndexFileSwap() {}

    public static void promote(Path temporary, Path live) throws IOException {
        checkpoint(temporary);
        String token = ".swap-backup-" + UUID.randomUUID();
        Path backup = live.resolveSibling(live.getFileName() + token);
        Path backupWal = backup.resolveSibling(backup.getFileName() + "-wal");
        Path backupShm = backup.resolveSibling(backup.getFileName() + "-shm");
        Path backupJournal = backup.resolveSibling(backup.getFileName() + "-journal");
        moveIfPresent(live.resolveSibling(live.getFileName() + "-wal"), backupWal);
        moveIfPresent(live.resolveSibling(live.getFileName() + "-shm"), backupShm);
        moveIfPresent(live.resolveSibling(live.getFileName() + "-journal"), backupJournal);
        try {
            Files.move(temporary, live, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            boolean hadLive = Files.exists(live);
            if (hadLive) Files.move(live, backup, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, live, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                if (hadLive && Files.exists(backup)) {
                    Files.move(backup, live, StandardCopyOption.REPLACE_EXISTING);
                }
                restoreSidecars(live, backupWal, backupShm, backupJournal);
                throw failure;
            }
        } catch (IOException failure) {
            restoreSidecars(live, backupWal, backupShm, backupJournal);
            throw failure;
        }
        Files.deleteIfExists(backup);
        Files.deleteIfExists(backupWal);
        Files.deleteIfExists(backupShm);
        Files.deleteIfExists(backupJournal);
        Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + "-wal"));
        Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + "-shm"));
        Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + "-journal"));
    }

    private static void checkpoint(Path database) throws IOException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException failure) {
            throw new IOException("failed to checkpoint replacement index", failure);
        }
    }

    private static void moveIfPresent(Path source, Path target) throws IOException {
        if (Files.exists(source)) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void restoreSidecars(Path live, Path wal, Path shm, Path journal) throws IOException {
        moveIfPresent(wal, live.resolveSibling(live.getFileName() + "-wal"));
        moveIfPresent(shm, live.resolveSibling(live.getFileName() + "-shm"));
        moveIfPresent(journal, live.resolveSibling(live.getFileName() + "-journal"));
    }
}
