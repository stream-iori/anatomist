package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.query.SymbolResolutionException;
import com.anatomist.query.semantic.SemanticCapabilityRegistry.UnsupportedCapabilityException;
import com.anatomist.query.semantic.SemanticStreamReader.SemanticStreamException;

import java.util.LinkedHashMap;
import java.util.Map;

final class PipelineFailure extends RuntimeException {
    private final String code;
    private final Integer stage;
    private final String command;
    private final Integer causeExit;
    private final String causeCode;

    private PipelineFailure(String code, String message, Integer stage, String command,
                            Integer causeExit, String causeCode, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.stage = stage;
        this.command = command;
        this.causeExit = causeExit;
        this.causeCode = causeCode;
    }

    static PipelineFailure invalid(String code, String message) {
        return new PipelineFailure(code, message, null, null, null, null, null);
    }

    static PipelineFailure invalidStage(int stage, String command, String message) {
        return invalidStageWithCode("PIPELINE_INVALID_SPEC", stage, command, message);
    }

    static PipelineFailure invalidStageWithCode(String code, int stage, String command,
                                                String message) {
        return new PipelineFailure(code, message, stage, command,
                2, "INVALID_ARGUMENT", null);
    }

    static PipelineFailure check(int stage, String command, String message,
                                 String causeCode) {
        return new PipelineFailure("PIPELINE_CHECK_FAILED", message, stage, command,
                3, causeCode, null);
    }

    static PipelineFailure stage(int stage, String command, RuntimeException cause) {
        int exit = CliError.exit(cause);
        String causeCode = CliError.code(cause);
        String pipelineCode = "PIPELINE_STAGE_FAILED";
        if (cause instanceof SemanticStreamException semantic) {
            if (semantic.code().contains("LIMIT")) {
                pipelineCode = "PIPELINE_LIMIT_EXCEEDED";
            }
        }
        return new PipelineFailure(pipelineCode, safeMessage(cause), stage,
                command, exit, causeCode, cause);
    }

    String json() {
        return Json.writeCompact(error());
    }

    Map<String, Object> error() {
        Map<String, Object> out = CliError.base(code, "pipeline", 5,
                getMessage(), "pipeline");
        out.put("inspect", java.util.List.of(
                java.util.List.of("anatomist", "pipeline", "--help")));
        if (stage != null) {
            Map<String, Object> stageValue = new LinkedHashMap<>();
            stageValue.put("position", stage);
            if (command != null) stageValue.put("operation", command);
            out.put("stage", stageValue);
        }
        if (causeExit != null || causeCode != null) {
            Map<String, Object> nested = CliError.base(
                    causeCode == null ? "RUNTIME_FAILURE" : causeCode,
                    causeCategory(causeExit, causeCode), causeExit == null ? 1 : causeExit,
                    getMessage(), command);
            if (command != null) nested.put("inspect", java.util.List.of(
                    java.util.List.of("anatomist", "operations", command,
                            "--format", "json")));
            out.put("cause", nested);
        }
        return out;
    }

    private static String causeCategory(Integer exit, String code) {
        if (code != null && code.startsWith("SYMBOL_")) return "resolution";
        if (code != null && (code.startsWith("INPUT_") || code.startsWith("MALFORMED_")
                || code.startsWith("UNSUPPORTED_RECORD") || code.startsWith("UNKNOWN_RECORD"))) {
            return "input";
        }
        if ("UNSUPPORTED_CAPABILITY".equals(code)) return "capability";
        if (code != null && (code.contains("INDEX") || code.contains("SCHEMA")
                || code.contains("SEMANTICS") || code.contains("SOURCE_PROFILE"))) {
            return "index";
        }
        return switch (exit == null ? 1 : exit) {
            case 2 -> "argument";
            case 3 -> "index";
            case 4 -> "stream";
            default -> "internal";
        };
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
