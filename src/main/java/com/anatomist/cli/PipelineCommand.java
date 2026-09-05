package com.anatomist.cli;

import com.anatomist.json.Json;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticStreamWriter.BrokenPipeException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "pipeline", mixinStandardHelpOptions = true,
        description = {
                "Run a linear semantic-stream pipeline in one process and SQLite connection.",
                "Prefer this command when every stage uses the same index, module, scope and format.",
                "Use a Shell pipeline for external tools, branching, or distinct global options.",
                "Use --explain for static composition and --check for read-only index preflight."
        },
        footer = "%nRules:%n"
                + "  Put --index/--module/--scope/--format before --; do not repeat them in stages.%n"
                + "  Read each stage's --help for its Accepts/Emits contract.%n"
                + "  Success ends with evidence(scope=stream); do not trust partial stdout without it.%n"
                + "  Specification, composition, limit, or stage failures exit 5 with one-line JSON on stderr.%n"
                + "  --explain/--check emit anatomist-pipeline-plan/v1 JSON and never execute a query.%n"
                + "%nInline:%n"
                + "  anatomist pipeline --index index.db -- resolve 'p.Service#run()' --kind callable --exact --unique --then calls --then dispatch%n"
                + "%nFile (recommended for automation):%n"
                + "  anatomist pipeline --index index.db --file pipeline.json%n"
                + "  {\"stages\":[[\"resolve\",\"p.Service#run()\",\"--kind\",\"callable\",\"--exact\",\"--unique\"],[\"calls\"],[\"dispatch\"]]}")
public final class PipelineCommand implements Callable<Integer> {
    static final int MAX_STAGES = 16;
    static final int MAX_ARGS_PER_STAGE = 256;
    static final long MAX_SPEC_BYTES = SemanticRecords.MAX_LINE_BYTES;

    @Option(names = "--index", description = "Path to index.db.") Path index;
    @Option(names = "--module", description = "Restrict all stages to one module.") String module;
    @Option(names = "--scope", defaultValue = "MAIN",
            description = "Source scope for all stages: MAIN | TEST | GENERATED | ALL.")
    String scope;
    @Option(names = "--format", defaultValue = "ndjson",
            description = "Final output: ndjson | json | table.")
    String format;
    @Option(names = "--file", description = "JSON pipeline file using {\"stages\":[[...]]}.")
    Path file;
    @Option(names = "--explain",
            description = "Validate and describe composition without opening an index.")
    boolean explain;
    @Option(names = "--check",
            description = "Read-only validation of composition, index, and operation availability.")
    boolean check;
    @Parameters(arity = "0..*", paramLabel = "STAGE_ARGS",
            description = "Inline stages after --, separated by --then.")
    List<String> inline = new ArrayList<>();

    @Override public Integer call() {
        try {
            if (explain && check) throw PipelineFailure.invalid("PIPELINE_INVALID_SPEC",
                    "--explain and --check are mutually exclusive");
            scope = CliValidation.scope(scope, true);
            format = CliValidation.choice("--format", format, "ndjson", "json", "table");
            List<List<String>> spec = file == null ? inlineSpec() : fileSpec();
            List<PipelineStageRegistry.Stage> stages = new ArrayList<>();
            for (int i = 0; i < spec.size(); i++) {
                stages.add(PipelineStageRegistry.parse(spec.get(i), i + 1,
                        index, module, scope));
            }
            PipelineStageRegistry.validateComposition(stages);
            if (explain) {
                System.out.println(Json.writeCompact(plan("explain", "valid", stages, null)));
                return 0;
            }
            Path db;
            try {
                db = IndexPath.resolve(index);
            } catch (RuntimeException failure) {
                throw PipelineFailure.stage(1, stages.getFirst().name(), failure);
            }
            try (SemanticExecutionContext context =
                         SemanticExecutionContext.open(db, module, scope)) {
                if (check) {
                    for (PipelineStageRegistry.Stage stage : stages) {
                        if (!context.capabilities().supports(stage.name())) {
                            throw PipelineFailure.check(stage.position(), stage.name(),
                                    "operation is unavailable in the selected index: "
                                            + stage.name(), "UNSUPPORTED_CAPABILITY");
                        }
                    }
                    System.out.println(Json.writeCompact(plan("check", "ready", stages,
                            context)));
                    return 0;
                }
                new SemanticPipelineExecutor(stages, context, System.out, format).execute(System.in);
            } catch (BrokenPipeException failure) {
                throw failure;
            } catch (PipelineFailure failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw PipelineFailure.stage(1, stages.getFirst().name(), failure);
            }
            return 0;
        } catch (BrokenPipeException ignored) {
            return 0;
        } catch (PipelineFailure failure) {
            System.err.println(failure.json());
            return 5;
        } catch (RuntimeException failure) {
            PipelineFailure wrapped = PipelineFailure.invalid(
                    "PIPELINE_INVALID_SPEC", failure.getMessage() == null
                            ? failure.getClass().getSimpleName() : failure.getMessage());
            System.err.println(wrapped.json());
            return 5;
        }
    }

    private Map<String, Object> plan(String mode, String status,
                                     List<PipelineStageRegistry.Stage> stages,
                                     SemanticExecutionContext context) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("contract", "anatomist-pipeline-plan/v1");
        out.put("mode", mode);
        out.put("status", status);
        out.put("query_contract", "semantic-stream/v1");
        Map<String, Object> globals = new java.util.LinkedHashMap<>();
        if (index != null) globals.put("index", index.toAbsolutePath().normalize().toString());
        if (module != null) globals.put("module", module);
        globals.put("scope", scope);
        globals.put("format", format);
        out.put("globals", globals);
        List<Map<String, Object>> stagePlans = new ArrayList<>();
        for (PipelineStageRegistry.Stage stage : stages) {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("position", stage.position());
            value.put("operation", stage.name());
            value.put("role", SemanticOperationRegistry.entry(stage.name()).role());
            value.put("accepts", List.copyOf(stage.accepted()));
            value.put("emits", List.copyOf(stage.emitted()));
            value.put("availability", context == null ? "unchecked"
                    : context.capabilities().supports(stage.name())
                    ? "available" : "unavailable");
            stagePlans.add(value);
        }
        out.put("stages", List.copyOf(stagePlans));
        PipelineStageRegistry.Stage first = stages.getFirst();
        if (!first.producerOnly()) {
            out.put("deferred_checks", List.of("INPUT_RECORD_TYPE", "INPUT_LANGUAGE",
                    "INPUT_ENTITY_KIND"));
        } else {
            out.put("deferred_checks", List.of("SELECTOR_RESOLUTION"));
        }
        return out;
    }

    private List<List<String>> inlineSpec() {
        if (inline.isEmpty()) throw PipelineFailure.invalid("PIPELINE_INVALID_SPEC",
                "provide --file or inline stages after --");
        List<List<String>> stages = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : inline) {
            if ("--then".equals(token)) {
                addStage(stages, current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        addStage(stages, current);
        validateLimits(stages);
        return stages;
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> fileSpec() {
        if (!inline.isEmpty()) throw PipelineFailure.invalid("PIPELINE_INVALID_SPEC",
                "--file and inline stages are mutually exclusive");
        try {
            if (!Files.isRegularFile(file)) throw PipelineFailure.invalid(
                    "PIPELINE_INVALID_SPEC", "pipeline file not found: " + file);
            if (Files.size(file) > MAX_SPEC_BYTES) throw PipelineFailure.invalid(
                    "PIPELINE_LIMIT_EXCEEDED", "pipeline file exceeds 1 MiB");
            Object parsed = Json.parseTree(Files.readString(file, StandardCharsets.UTF_8),
                    SemanticRecords.MAX_JSON_DEPTH);
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("stages") instanceof List<?> raw)) {
                throw PipelineFailure.invalid("PIPELINE_INVALID_SPEC",
                        "pipeline file must contain a stages array");
            }
            List<List<String>> stages = new ArrayList<>();
            for (Object item : raw) {
                if (!(item instanceof List<?> values) || values.isEmpty()) {
                    throw PipelineFailure.invalid("PIPELINE_INVALID_SPEC",
                            "each pipeline stage must be a non-empty string array");
                }
                List<String> stage = new ArrayList<>();
                for (Object value : values) {
                    if (!(value instanceof String token)) throw PipelineFailure.invalid(
                            "PIPELINE_INVALID_SPEC", "pipeline argv values must be strings");
                    stage.add(token);
                }
                stages.add(stage);
            }
            validateLimits(stages);
            return stages;
        } catch (PipelineFailure failure) {
            throw failure;
        } catch (IOException | IllegalArgumentException failure) {
            throw PipelineFailure.invalid("PIPELINE_INVALID_SPEC",
                    failure.getMessage() == null ? "failed to read pipeline file"
                            : failure.getMessage());
        }
    }

    private static void addStage(List<List<String>> stages, List<String> stage) {
        if (stage.isEmpty()) throw PipelineFailure.invalid("PIPELINE_INVALID_SPEC",
                "pipeline stage must not be empty");
        stages.add(List.copyOf(stage));
    }

    private static void validateLimits(List<List<String>> stages) {
        if (stages.isEmpty()) throw PipelineFailure.invalid("PIPELINE_INVALID_SPEC",
                "pipeline must contain at least one stage");
        if (stages.size() > MAX_STAGES) throw PipelineFailure.invalid(
                "PIPELINE_LIMIT_EXCEEDED", "pipeline exceeds " + MAX_STAGES + " stages");
        for (List<String> stage : stages) {
            if (stage.size() > MAX_ARGS_PER_STAGE) throw PipelineFailure.invalid(
                    "PIPELINE_LIMIT_EXCEEDED", "stage exceeds "
                            + MAX_ARGS_PER_STAGE + " argv values");
        }
    }
}
