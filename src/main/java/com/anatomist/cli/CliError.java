package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.query.SymbolResolutionException;
import com.anatomist.query.DeclarationQueryService.DeclarationQueryException;
import com.anatomist.query.semantic.SemanticCapabilityRegistry.UnsupportedCapabilityException;
import com.anatomist.query.semantic.SemanticStreamReader.SemanticStreamException;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable machine-readable error envelope for Agent-facing read operations. */
final class CliError {
    static final String CONTRACT = "anatomist-error/v1";

    private CliError() {}

    static Map<String, Object> of(String operation, RuntimeException failure, int exit) {
        Map<String, Object> out = base(code(failure), category(failure), exit,
                safeMessage(failure), operation);
        Map<String, Object> details = details(failure);
        if (!details.isEmpty()) out.put("details", details);
        if (operation != null) {
            out.put("inspect", List.of(operation.equals("pipeline")
                    ? List.of("anatomist", "pipeline", "--help")
                    : List.of("anatomist", "operations", operation, "--format", "json")));
        }
        return out;
    }

    static Map<String, Object> base(String code, String category, int exit,
                                    String message, String operation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        out.put("code", code);
        out.put("category", category);
        out.put("exit", exit);
        out.put("message", message);
        if (operation != null) out.put("operation", operation);
        return out;
    }

    static void emit(Map<String, Object> error) {
        emit(System.err, error);
    }

    static void emit(PrintStream stream, Map<String, Object> error) {
        stream.println(Json.writeCompact(error));
    }

    static void emit(PrintWriter writer, Map<String, Object> error) {
        writer.println(Json.writeCompact(error));
        writer.flush();
    }

    static int exit(RuntimeException failure) {
        if (failure instanceof SemanticStreamException semantic) {
            return streamConflict(semantic.code()) ? 4 : 2;
        }
        if (failure instanceof UnsupportedCapabilityException) return 3;
        if (failure instanceof DeclarationQueryException
                || failure instanceof IndexPath.IndexMissingException) return 3;
        if (failure instanceof SymbolResolutionException
                || failure instanceof IllegalArgumentException) return 2;
        if (failure instanceof IllegalStateException) return 3;
        return 1;
    }

    static String code(RuntimeException failure) {
        if (failure instanceof SemanticStreamException semantic) return semantic.code();
        if (failure instanceof SymbolResolutionException resolution) return resolution.code();
        if (failure instanceof UnsupportedCapabilityException) return "UNSUPPORTED_CAPABILITY";
        if (failure instanceof DeclarationQueryException declaration) return declaration.code();
        if (failure instanceof IndexPath.IndexMissingException) return "INDEX_MISSING";
        if (failure instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        if (failure instanceof IllegalStateException) return knownStateCode(failure.getMessage());
        return "RUNTIME_FAILURE";
    }

    static String category(RuntimeException failure) {
        if (failure instanceof SemanticStreamException semantic) {
            return inputCode(semantic.code()) ? "input" : "stream";
        }
        if (failure instanceof SymbolResolutionException) return "resolution";
        if (failure instanceof UnsupportedCapabilityException) return "capability";
        if (failure instanceof DeclarationQueryException
                || failure instanceof IndexPath.IndexMissingException) return "index";
        if (failure instanceof IllegalArgumentException) return "argument";
        if (failure instanceof IllegalStateException) return "index";
        return "internal";
    }

    static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static Map<String, Object> details(RuntimeException failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (failure instanceof SymbolResolutionException resolution) {
            details.put("selector", resolution.resolution().input());
            details.put("selector_kind",
                    resolution.resolution().targetKind().name().toLowerCase());
            details.put("candidate_ids", resolution.resolution().ids());
        } else if (failure instanceof UnsupportedCapabilityException unsupported) {
            details.put("operation", unsupported.operation());
            details.put("language", unsupported.language());
            details.put("support", "supported");
            details.put("availability", "unavailable");
        } else if (failure instanceof IndexPath.IndexMissingException missing) {
            details.put("index_path", missing.path().toString());
        }
        return details;
    }

    private static boolean streamConflict(String code) {
        return code.contains("EVIDENCE") || code.contains("PIPELINE")
                || code.contains("PROFILE") || code.contains("SNAPSHOT")
                || code.equals("RECORD_AFTER_STREAM_EVIDENCE");
    }

    private static boolean inputCode(String code) {
        return code.startsWith("INPUT_") || code.startsWith("MALFORMED_")
                || code.startsWith("INVALID_") || code.startsWith("UNSUPPORTED_RECORD")
                || code.startsWith("UNKNOWN_RECORD");
    }

    private static String knownStateCode(String message) {
        if (message == null) return "INDEX_QUERY_FAILED";
        for (String code : List.of("SCHEMA_MISMATCH", "GRAPH_SEMANTICS_MISMATCH",
                "INDEX_STALE", "SOURCE_SNAPSHOT_STALE", "INDEX_QUERY_FAILED")) {
            if (message.startsWith(code + ":")) return code;
        }
        return "INDEX_QUERY_FAILED";
    }
}
