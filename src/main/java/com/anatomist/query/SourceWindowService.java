package com.anatomist.query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.anatomist.query.QueryInfra.rethrow;

public class SourceWindowService {

    private final Connection conn;

    public SourceWindowService(Connection conn) {
        this.conn = conn;
    }

    public void attachToEdges(List<EdgeRow> rows, int contextLines) {
        if (rows == null || rows.isEmpty() || contextLines < 0) return;
        Optional<Path> root = readSourceRoot();
        if (root.isEmpty()) return;
        Map<String, String> sourceFileCache = new HashMap<>();
        for (EdgeRow row : rows) {
            String sourceFile = row.sourceFile;
            if ((sourceFile == null || sourceFile.isBlank()) && row.source != null) {
                sourceFile = sourceFileCache.computeIfAbsent(row.source, this::readNodeSourceFile);
            }
            row.sourceWindow = window(root.get(), sourceFile, row.sourceLocation, contextLines);
        }
    }

    public SourceWindow window(String sourceFile, int line, int contextLines) {
        Optional<Path> root = readSourceRoot();
        if (root.isEmpty() || line <= 0) return null;
        return window(root.get(), sourceFile, "L" + line, contextLines);
    }

    public SourceWindow window(Path sourceRoot, String sourceFile, String sourceLocation, int contextLines) {
        if (sourceRoot == null || sourceFile == null || sourceFile.isBlank()) return null;
        int line = parseLine(sourceLocation);
        if (line <= 0) return null;

        Path sourcePath = Path.of(sourceFile);
        if (!sourcePath.isAbsolute()) {
            sourcePath = sourceRoot.resolve(sourceFile);
        }
        sourcePath = sourcePath.normalize();
        if (!Files.isRegularFile(sourcePath)) return null;

        try {
            List<String> lines = Files.readAllLines(sourcePath, StandardCharsets.UTF_8);
            if (line > lines.size()) return null;
            int start = Math.max(1, line - contextLines);
            int end = Math.min(lines.size(), line + contextLines);
            StringBuilder snippet = new StringBuilder();
            int width = String.valueOf(end).length();
            for (int i = start; i <= end; i++) {
                if (i > start) snippet.append('\n');
                snippet.append(String.format("%" + width + "d | %s", i, lines.get(i - 1)));
            }

            SourceWindow out = new SourceWindow();
            out.path = sourcePath.toString();
            out.line = line;
            out.startLine = start;
            out.endLine = end;
            out.snippet = snippet.toString();
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private Optional<Path> readSourceRoot() {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM project_meta WHERE key='source_root'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String value = rs.getString(1);
                if (value == null || value.isBlank()) return Optional.empty();
                return Optional.of(Path.of(value).toAbsolutePath().normalize());
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    private String readNodeSourceFile(String nodeId) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT source_file FROM nodes WHERE id=?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw rethrow(e);
        }
    }

    static int parseLine(String sourceLocation) {
        if (sourceLocation == null || sourceLocation.isBlank()) return -1;
        int l = sourceLocation.indexOf('L');
        if (l < 0) return -1;
        int i = l + 1;
        int start = i;
        while (i < sourceLocation.length() && Character.isDigit(sourceLocation.charAt(i))) i++;
        if (i == start) return -1;
        try {
            return Integer.parseInt(sourceLocation.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
