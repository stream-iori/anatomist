package com.anatomist.query;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;

import static com.anatomist.query.QueryInfra.rethrow;

/** Reads one source-backed declaration without mixing index and checkout snapshots. */
public final class SourceContextService {
    private final Connection connection;
    private final IndexedSourceVerifier verifier;

    public SourceContextService(Connection connection, Path index) {
        this.connection = connection;
        this.verifier = new IndexedSourceVerifier(connection, index);
    }

    public SourceContext read(NodeRow node, SourceRequest request) {
        DeclarationRange declaration = declaration(node);
        if (declaration == null || declaration.synthetic || !declaration.complete()) {
            return warning("unavailable", node == null ? null : node.sourceFile, null,
                    "SOURCE_RANGE_UNAVAILABLE", "target has no source-backed declaration range");
        }
        String renderedRange = declaration.render();
        IndexedSourceVerifier.Verification verification = verifier.verify(declaration.sourceFile);
        if (verification.status() == IndexedSourceVerifier.Status.STALE) {
            return warning("stale", declaration.sourceFile, renderedRange,
                    verification.code(), verification.message());
        }
        if (!verification.current()) {
            return warning("error", declaration.sourceFile, renderedRange,
                    verification.code(), verification.message());
        }
        try {
            byte[] bytes = Files.readAllBytes(verification.path());
            if (!verification.expectedHash().equals(sha256(bytes))) {
                return warning("stale", declaration.sourceFile, renderedRange,
                        "INDEX_STALE", "indexed source file changed while it was being read: "
                                + declaration.sourceFile);
            }
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            return page(declaration, renderedRange, lines, request);
        } catch (IOException failure) {
            return warning("error", declaration.sourceFile, renderedRange,
                    "SOURCE_READ_FAILED", failure.getMessage());
        }
    }

    private DeclarationRange declaration(NodeRow node) {
        if (node == null || node.symbolId == null) return null;
        String sql = "SELECT source_file,begin_line,begin_column,end_line,end_column,synthetic "
                + "FROM declarations WHERE symbol_id=? AND module=? AND scope=? AND source_file=? "
                + "AND producer_id=? LIMIT 2";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, node.symbolId);
            statement.setString(2, node.module);
            statement.setString(3, node.scope);
            statement.setString(4, node.sourceFile);
            statement.setString(5, node.producerId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                DeclarationRange range = new DeclarationRange(rows.getString(1), integer(rows, 2), integer(rows, 3),
                        integer(rows, 4), integer(rows, 5), rows.getInt(6) != 0);
                return rows.next() ? null : range;
            }
        } catch (SQLException failure) {
            throw rethrow(failure);
        }
    }

    private static SourceContext page(DeclarationRange declaration, String renderedRange,
                                      List<String> lines, SourceRequest request) {
        if (!declaration.validFor(lines)) {
            return warning("error", declaration.sourceFile, renderedRange,
                    "SOURCE_RANGE_INVALID", "indexed declaration range is outside the source file");
        }
        int total = declaration.endLine - declaration.beginLine + 1;
        int offset = Math.min(request.offset(), total);
        int count = Math.min(request.limit(), total - offset);
        SourceContext out = new SourceContext();
        out.status = "ok";
        out.sourceFile = declaration.sourceFile;
        out.sourceRange = renderedRange;
        out.totalLines = total;
        out.offset = offset;
        out.limit = request.limit();
        out.truncated = offset + count < total;
        if (count == 0) {
            out.snippet = "";
            return out;
        }
        int start = declaration.beginLine + offset;
        int end = start + count - 1;
        out.startLine = start;
        out.endLine = end;
        int width = String.valueOf(end).length();
        StringBuilder snippet = new StringBuilder();
        for (int line = start; line <= end; line++) {
            String text = lines.get(line - 1);
            int from = line == declaration.beginLine ? declaration.beginColumn - 1 : 0;
            int to = line == declaration.endLine ? declaration.endColumn : text.length();
            if (from < 0 || from > text.length() || to < from || to > text.length()) {
                return warning("error", declaration.sourceFile, renderedRange,
                        "SOURCE_RANGE_INVALID", "indexed declaration columns are outside the source line");
            }
            if (line > start) snippet.append('\n');
            snippet.append(String.format("%" + width + "d | %s", line, text.substring(from, to)));
        }
        out.snippet = snippet.toString();
        return out;
    }

    private static SourceContext warning(String status, String sourceFile, String sourceRange,
                                         String code, String message) {
        SourceContext out = new SourceContext();
        out.status = status;
        out.sourceFile = sourceFile;
        out.sourceRange = sourceRange;
        out.warningCode = code;
        out.warningMessage = message;
        return out;
    }

    private static Integer integer(ResultSet rows, int column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record DeclarationRange(String sourceFile, Integer beginLine, Integer beginColumn,
                                    Integer endLine, Integer endColumn, boolean synthetic) {
        boolean complete() {
            return beginLine != null && beginColumn != null && endLine != null && endColumn != null;
        }

        boolean validFor(List<String> lines) {
            if (!complete() || beginLine <= 0 || endLine < beginLine || endLine > lines.size()) return false;
            if (beginColumn <= 0 || endColumn <= 0) return false;
            return endLine > beginLine || endColumn >= beginColumn;
        }

        String render() {
            return "L" + beginLine + ":C" + beginColumn + "-L" + endLine + ":C" + endColumn;
        }
    }
}
