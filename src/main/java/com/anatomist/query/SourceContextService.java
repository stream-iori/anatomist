package com.anatomist.query;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.ArrayList;
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
            return readVerifiedPage(verification, declaration, renderedRange, request);
        } catch (IOException failure) {
            return warning("error", declaration.sourceFile, renderedRange,
                    "SOURCE_READ_FAILED", failure.getMessage());
        }
    }

    public SourceContext readRange(String sourceFile, int beginLine, int beginColumn,
                                   int endLine, int endColumn, SourceRequest request) {
        DeclarationRange range = new DeclarationRange(sourceFile, beginLine, beginColumn,
                endLine, endColumn, false);
        if (!range.complete()) return warning("unavailable", sourceFile, null,
                "SOURCE_RANGE_UNAVAILABLE", "site has no complete source range");
        String renderedRange = range.render();
        IndexedSourceVerifier.Verification verification = verifier.verify(sourceFile);
        if (verification.status() == IndexedSourceVerifier.Status.STALE) {
            return warning("stale", sourceFile, renderedRange,
                    verification.code(), verification.message());
        }
        if (!verification.current()) return warning("error", sourceFile, renderedRange,
                verification.code(), verification.message());
        try {
            return readVerifiedPage(verification, range, renderedRange, request);
        } catch (IOException failure) {
            return warning("error", sourceFile, renderedRange,
                    "SOURCE_READ_FAILED", failure.getMessage());
        }
    }

    private DeclarationRange declaration(NodeRow node) {
        if (node == null || node.id == null) return null;
        String sql = "SELECT source_file,declaration_begin_line,declaration_begin_column,"
                + "declaration_end_line,declaration_end_column,synthetic "
                + "FROM nodes WHERE id=? AND producer_id=? AND declaration_kind IS NOT NULL LIMIT 2";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, node.id);
            statement.setString(2, node.producerId);
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

    private static SourceContext readVerifiedPage(IndexedSourceVerifier.Verification verification,
                                                  DeclarationRange declaration,
                                                  String renderedRange,
                                                  SourceRequest request) throws IOException {
        int total = declaration.endLine - declaration.beginLine + 1;
        int offset = Math.min(request.offset(), total);
        int count = Math.min(request.limit(), total - offset);
        int start = declaration.beginLine + offset;
        int end = count == 0 ? start - 1 : start + count - 1;
        List<String> selected = new ArrayList<>(count);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        int fileLines = 0;
        try (DigestInputStream bytes = new DigestInputStream(
                Files.newInputStream(verification.path()), digest);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(bytes, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fileLines++;
                if (fileLines >= start && fileLines <= end) selected.add(line);
            }
        }
        if (!verification.expectedHash().equals(HexFormat.of().formatHex(digest.digest()))) {
            return warning("stale", declaration.sourceFile, renderedRange,
                    "INDEX_STALE", "indexed source file changed while it was being read: "
                            + declaration.sourceFile);
        }
        if (!declaration.validFor(fileLines) || selected.size() != count) {
            return warning("error", declaration.sourceFile, renderedRange,
                    "SOURCE_RANGE_INVALID", "indexed declaration range is outside the source file");
        }
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
        out.startLine = start;
        out.endLine = end;
        int width = String.valueOf(end).length();
        StringBuilder snippet = new StringBuilder();
        for (int line = start; line <= end; line++) {
            String text = selected.get(line - start);
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

    private record DeclarationRange(String sourceFile, Integer beginLine, Integer beginColumn,
                                    Integer endLine, Integer endColumn, boolean synthetic) {
        boolean complete() {
            return beginLine != null && beginColumn != null && endLine != null && endColumn != null;
        }

        boolean validFor(int fileLines) {
            if (!complete() || beginLine <= 0 || endLine < beginLine || endLine > fileLines) return false;
            if (beginColumn <= 0 || endColumn <= 0) return false;
            return endLine > beginLine || endColumn >= beginColumn;
        }

        String render() {
            return "L" + beginLine + ":C" + beginColumn + "-L" + endLine + ":C" + endColumn;
        }
    }
}
