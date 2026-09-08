package com.anatomist.query;

import com.anatomist.store.FileCacheService;
import com.anatomist.store.IndexStateStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static com.anatomist.query.QueryInfra.rethrow;

/** Verifies that a source file still represents the committed index snapshot. */
public final class IndexedSourceVerifier {
    public enum Status { CURRENT, STALE, ERROR }

    public record Verification(Status status, Path path, String expectedHash,
                               String code, String message) {
        public boolean current() { return status == Status.CURRENT; }
    }

    private final Connection connection;
    private final Path index;

    public IndexedSourceVerifier(Connection connection, Path index) {
        this.connection = connection;
        this.index = index;
    }

    public Verification verify(String sourceFile) {
        IndexStateStore.Snapshot state = IndexStateStore.read(index);
        if (!state.fresh()) {
            return stale(null, null, "INDEX_STALE",
                    "index state is " + state.state().name().toLowerCase());
        }
        if (sourceFile == null || sourceFile.isBlank()) {
            return error(null, null, "SOURCE_FILE_UNAVAILABLE", "node has no source file");
        }
        try {
            String expectedHash = scalar("SELECT hash FROM file_cache WHERE source_file=?", sourceFile);
            if (expectedHash == null) {
                return error(null, null, "FILE_NOT_INDEXED",
                        "file is not present in the committed index: " + sourceFile);
            }
            Path snapshotSource = SnapshotSource.path(connection,sourceFile);
            if (snapshotSource != null) {
                if (!Files.isRegularFile(snapshotSource) || !expectedHash.equals(FileCacheService.sha256(snapshotSource)))
                    return stale(snapshotSource,expectedHash,"SNAPSHOT_SOURCE_CORRUPT","Frozen source is missing or changed: " + sourceFile);
                return new Verification(Status.CURRENT,snapshotSource,expectedHash,null,null);
            }
            String rootValue = scalar("SELECT value FROM project_meta WHERE key='source_root'");
            if (rootValue == null || rootValue.isBlank()) {
                return error(null, expectedHash, "SOURCE_PROFILE_INCOMPLETE",
                        "index is missing project_meta.source_root");
            }
            Path projectRoot = Path.of(rootValue).toAbsolutePath().normalize();
            Path raw = Path.of(sourceFile);
            Path candidate = (raw.isAbsolute() ? raw : projectRoot.resolve(raw)).toAbsolutePath().normalize();
            List<Path> allowedRoots;
            try {
                allowedRoots = sourceRoots(projectRoot,
                        scalar("SELECT value FROM project_meta WHERE key='source_layout'"));
            } catch (SourceProfileException failure) {
                return error(null, expectedHash, "SOURCE_PROFILE_INCOMPLETE", failure.getMessage());
            }
            if (allowedRoots.stream().noneMatch(candidate::startsWith)) {
                return error(candidate, expectedHash, "SOURCE_PATH_INVALID",
                        "indexed source path is outside committed source roots: " + sourceFile);
            }
            if (!Files.isRegularFile(candidate)) {
                return stale(candidate, expectedHash, "INDEX_STALE",
                        "indexed source file is missing: " + sourceFile);
            }
            Path real = candidate.toRealPath();
            boolean insideRealRoot = false;
            for (Path allowed : allowedRoots) {
                if (Files.exists(allowed) && real.startsWith(allowed.toRealPath())) {
                    insideRealRoot = true;
                    break;
                }
            }
            if (!insideRealRoot) {
                return error(candidate, expectedHash, "SOURCE_PATH_INVALID",
                        "indexed source path escapes committed source roots: " + sourceFile);
            }
            if (!expectedHash.equals(FileCacheService.sha256(real))) {
                return stale(real, expectedHash, "INDEX_STALE",
                        "indexed source file has changed: " + sourceFile);
            }
            return new Verification(Status.CURRENT, real, expectedHash, null, null);
        } catch (InvalidPathException failure) {
            return error(null, null, "SOURCE_PATH_INVALID", failure.getMessage());
        } catch (IOException failure) {
            return error(null, null, "SOURCE_READ_FAILED", failure.getMessage());
        } catch (SQLException failure) {
            throw rethrow(failure);
        } catch (RuntimeException failure) {
            return error(null, null, "SOURCE_READ_FAILED", failure.getMessage());
        }
    }

    private static List<Path> sourceRoots(Path projectRoot, String layout) {
        if (layout == null || layout.isBlank()) {
            throw new SourceProfileException("index is missing project_meta.source_layout");
        }
        List<Path> roots = new ArrayList<>();
        for (String raw : layout.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int at = line.indexOf('@');
            int equals = line.indexOf('=', at + 1);
            if (at <= 0 || equals <= at + 1 || equals == line.length() - 1) {
                throw malformedLayout();
            }
            try {
                com.anatomist.core.SourceScope.valueOf(line.substring(at + 1, equals));
                Path value = Path.of(line.substring(equals + 1));
                roots.add((value.isAbsolute() ? value : projectRoot.resolve(value))
                        .toAbsolutePath().normalize());
            } catch (IllegalArgumentException failure) {
                throw malformedLayout();
            }
        }
        if (roots.isEmpty()) throw malformedLayout();
        return List.copyOf(roots);
    }

    private static SourceProfileException malformedLayout() {
        return new SourceProfileException(
                "index has malformed project_meta.source_layout; rebuild the structural index");
    }

    private String scalar(String sql, String arg) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, arg);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private String scalar(String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static Verification stale(Path path, String hash, String code, String message) {
        return new Verification(Status.STALE, path, hash, code, message);
    }

    private static Verification error(Path path, String hash, String code, String message) {
        return new Verification(Status.ERROR, path, hash, code, message);
    }

    private static final class SourceProfileException extends RuntimeException {
        private SourceProfileException(String message) {
            super(message);
        }
    }
}
