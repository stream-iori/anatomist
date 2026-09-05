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

    static PipelineFailure stage(int stage, String command, RuntimeException cause) {
        int exit = 1;
        String causeCode = "RUNTIME_FAILURE";
        String pipelineCode = "PIPELINE_STAGE_FAILED";
        if (cause instanceof SemanticStreamException semantic) {
            causeCode = semantic.code();
            if (semantic.code().contains("LIMIT")) {
                pipelineCode = "PIPELINE_LIMIT_EXCEEDED";
            }
            exit = semantic.code().contains("EVIDENCE")
                    || semantic.code().contains("PIPELINE")
                    || semantic.code().contains("PROFILE")
                    || semantic.code().contains("SNAPSHOT") ? 4 : 2;
        } else if (cause instanceof SymbolResolutionException resolution) {
            causeCode = resolution.code();
            exit = 2;
        } else if (cause instanceof UnsupportedCapabilityException) {
            causeCode = "UNSUPPORTED_CAPABILITY";
            exit = 3;
        } else if (cause instanceof IllegalStateException) {
            causeCode = stateCode(cause.getMessage());
            exit = 3;
        } else if (cause instanceof IllegalArgumentException) {
            causeCode = "INVALID_ARGUMENT";
            exit = 2;
        }
        return new PipelineFailure(pipelineCode, safeMessage(cause), stage,
                command, exit, causeCode, cause);
    }

    String json() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        if (stage != null) out.put("stage", stage);
        if (command != null) out.put("command", command);
        if (causeExit != null) out.put("cause_exit", causeExit);
        if (causeCode != null) out.put("cause_code", causeCode);
        out.put("message", getMessage());
        return Json.writeCompact(out);
    }

    private static String stateCode(String message) {
        if (message == null) return "INDEX_QUERY_FAILED";
        int colon = message.indexOf(':');
        String prefix = colon < 0 ? message : message.substring(0, colon);
        return prefix.matches("[A-Z][A-Z0-9_]+") ? prefix : "INDEX_QUERY_FAILED";
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }
}
