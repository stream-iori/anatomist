package com.anatomist.query.semantic;

import com.anatomist.json.Json;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Writes semantic records as NDJSON, a terminal JSON array, or a compact table. */
public final class SemanticStreamWriter implements AutoCloseable {
    public enum Format { NDJSON, JSON, TABLE }

    private final PrintStream out;
    private final Format format;
    private final List<Map<String, Object>> buffered;
    private boolean tableHeader;

    public SemanticStreamWriter(PrintStream out, String requestedFormat) {
        this.out = out;
        this.format = switch (requestedFormat == null ? "ndjson" : requestedFormat.toLowerCase()) {
            case "ndjson" -> Format.NDJSON;
            case "json" -> Format.JSON;
            case "table" -> Format.TABLE;
            default -> throw new IllegalArgumentException(
                    "--format must be ndjson, json, or table; got " + requestedFormat);
        };
        this.buffered = format == Format.JSON ? new ArrayList<>() : null;
    }

    public void write(Map<String, Object> record) {
        switch (format) {
            case NDJSON -> {
                out.println(Json.writeCompact(record));
                ensureWritable();
            }
            case JSON -> buffered.add(record);
            case TABLE -> writeTable(record);
        }
    }

    private void writeTable(Map<String, Object> record) {
        if (!tableHeader) {
            out.println("RECORD\tID\tQUALIFIED_NAME\tSTATUS");
            tableHeader = true;
        }
        out.printf("%s\t%s\t%s\t%s%n", value(record, "record"), value(record, "id"),
                value(record, "qualified_name"), value(record, "status"));
        ensureWritable();
    }

    private static String value(Map<String, Object> record, String key) {
        Object value = record.get(key);
        return value == null ? "" : String.valueOf(value).replace('\t', ' ');
    }

    @Override public void close() {
        if (format == Format.JSON) out.println(Json.writePretty(buffered));
        out.flush();
        ensureWritable();
    }

    private void ensureWritable() {
        if (out.checkError()) throw new BrokenPipeException();
    }

    public static final class BrokenPipeException extends RuntimeException {
        public BrokenPipeException() { super("semantic output consumer closed the pipe"); }
    }
}
