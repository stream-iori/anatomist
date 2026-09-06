package com.anatomist.query.semantic;

import com.anatomist.json.Json;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Writes the canonical semantic stream or a terminal projection. */
public final class SemanticStreamWriter implements AutoCloseable {
    public enum Format { NDJSON, JSON, TABLE }

    private static final List<String> IDENTITY_FIELDS = List.of(
            "index_revision_id", "source_snapshot_id", "semantic_profile_id");

    private final PrintStream out;
    private final Format format;
    private final List<Map<String, Object>> buffered;
    private final Consumer<Map<String, Object>> sink;
    private Map<String, Object> identity;
    private boolean headerWritten;
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
        this.sink = null;
    }

    /** Typed in-process transport used by the fused pipeline executor. */
    public SemanticStreamWriter(Consumer<Map<String, Object>> sink) {
        this.out = null;
        this.format = Format.NDJSON;
        this.buffered = null;
        this.sink = sink;
    }

    public void write(Map<String, Object> record) {
        if (sink != null) {
            Map<String, Object> compact = compactInternal(record);
            if (compact != null) sink.accept(compact);
            return;
        }
        captureIdentity(record);
        Map<String, Object> wire = wireRecord(record);
        if (wire == null) return;
        switch (format) {
            case NDJSON -> {
                writeHeader();
                out.println(Json.writeCompact(wire));
                ensureWritable();
            }
            case JSON -> buffered.add(wire);
            case TABLE -> writeTable(wire);
        }
    }

    private void captureIdentity(Map<String, Object> record) {
        Map<String, Object> next = new LinkedHashMap<>();
        for (String field : IDENTITY_FIELDS) {
            Object value = record.get(field);
            if (value == null) throw new IllegalArgumentException(
                    "semantic record is missing " + field);
            next.put(field, value);
        }
        if (identity == null) identity = Map.copyOf(next);
        else if (!identity.equals(next)) throw new IllegalArgumentException(
                "semantic output identity changed within one stream");
    }

    private static Map<String, Object> wireRecord(Map<String, Object> record) {
        String parent = string(record.get("parent_seed_id"));
        if (parent != null && "evidence".equals(record.get("record"))) return null;
        Map<String, Object> wire = new LinkedHashMap<>(record);
        wire.remove("contract");
        for (String field : IDENTITY_FIELDS) wire.remove(field);
        wire.remove("parent_seed_id");
        if (parent != null) wire.put("seed_id", parent);
        return wire;
    }

    private static Map<String, Object> compactInternal(Map<String, Object> record) {
        String parent = string(record.get("parent_seed_id"));
        if (parent != null && "evidence".equals(record.get("record"))) return null;
        if (parent == null) return record;
        Map<String, Object> compact = new LinkedHashMap<>(record);
        compact.remove("parent_seed_id");
        compact.put("seed_id", parent);
        return compact;
    }

    private void writeHeader() {
        if (headerWritten) return;
        if (identity == null) throw new IllegalStateException("semantic stream has no identity");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("record", "stream_header");
        header.put("contract", SemanticRecords.CONTRACT);
        header.put("identity", identity);
        out.println(Json.writeCompact(header));
        ensureWritable();
        headerWritten = true;
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

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @Override public void close() {
        if (sink != null) return;
        if (format == Format.JSON) {
            // A command can fail before producing its first semantic record. Closing the
            // writer must not mask the command's real validation/query error.
            if (identity == null) {
                out.flush();
                ensureWritable();
                return;
            }
            List<Map<String, Object>> results = new ArrayList<>();
            List<Map<String, Object>> seeds = new ArrayList<>();
            Map<String, Object> stream = null;
            for (Map<String, Object> record : buffered) {
                if (!"evidence".equals(record.get("record"))) results.add(record);
                else if ("stream".equals(record.get("scope"))) stream = record;
                else seeds.add(record);
            }
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("seeds", seeds);
            if (stream != null) evidence.put("stream", stream);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("contract", SemanticRecords.CONTRACT);
            envelope.put("identity", identity);
            envelope.put("results", results);
            envelope.put("evidence", evidence);
            out.println(Json.writePretty(envelope));
        }
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
