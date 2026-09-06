package com.anatomist.cli;

import com.anatomist.query.QueryService;
import com.anatomist.query.SymbolResolutionException;
import com.anatomist.query.semantic.SemanticIdentity;
import com.anatomist.query.semantic.SemanticCapabilityRegistry;
import com.anatomist.query.semantic.SemanticCapabilityRegistry.UnsupportedCapabilityException;
import com.anatomist.query.semantic.SemanticRecords;
import com.anatomist.query.semantic.SemanticStreamReader.SemanticStreamException;
import com.anatomist.query.semantic.SemanticStreamReader;
import com.anatomist.query.semantic.SemanticStreamWriter;
import com.anatomist.query.semantic.SemanticFrameSource;
import com.anatomist.query.semantic.SemanticStreamWriter.BrokenPipeException;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.Set;
import java.util.function.Consumer;

/** Common lifecycle and error contract for semantic-stream operations. */
abstract class SemanticCommand implements Callable<Integer> {
    @Option(names = "--index", description = "Path to index.db.") Path index;
    @Option(names = "--module", description = "Restrict lookup to one module.") String module;
    @Option(names = "--scope", defaultValue = "MAIN",
            description = "Source scope: MAIN | TEST | GENERATED | ALL (default MAIN).") String scope;
    @Option(names = "--language", defaultValue = "java",
            description = "Language ID (default java).") String language;
    @Option(names = "--provider", description = "Language provider ID (default: provider for --language).")
    String provider;
    @Option(names = "--format", defaultValue = "ndjson",
            description = "Output: ndjson | json | table (default ndjson).") String format;
    @Option(names = "--accept-unframed",
            description = "Accept data-only semantic input; resulting coverage is unknown.")
    boolean acceptUnframed;
    @Option(names = "--on-unsupported", defaultValue = "fail",
            description = "Unsupported capability policy: fail | continue (default fail).")
    String onUnsupported;
    private SemanticFrameSource frameSource;

    @Override public Integer call() {
        try {
            Path db = IndexPath.resolve(index);
            scope = CliValidation.scope(scope, true);
            try (SemanticExecutionContext context = SemanticExecutionContext.open(db, module, scope);
                 SemanticStreamWriter writer = new SemanticStreamWriter(System.out, format)) {
                Result result = executeStage(context, SemanticFrameSource.ndjson(System.in), writer);
                writer.write(SemanticRecords.streamEvidence(result.seeds(), result.emitted(),
                        result.complete(), result.truncated(), context.identity()));
            }
            // PrintStream intentionally absorbs EPIPE: an early-closing Unix consumer is success.
            return 0;
        } catch (BrokenPipeException ignored) {
            return 0;
        } catch (RuntimeException failure) {
            int exit = CliError.exit(failure);
            CliError.emit(CliError.of(SemanticOperationRegistry.entry(this).id(),
                    failure, exit));
            return exit;
        }
    }

    final Result executeStage(SemanticExecutionContext context, SemanticFrameSource input,
                              SemanticStreamWriter writer) {
        onUnsupported = CliValidation.choice("--on-unsupported", onUnsupported,
                "fail", "continue");
        frameSource = input;
        try {
            String operation = SemanticOperationRegistry.entry(this).id();
            language = language == null ? "java"
                    : language.trim().toLowerCase(java.util.Locale.ROOT);
            String selectedProvider = provider == null || provider.isBlank()
                    ? com.anatomist.query.semantic.SemanticProviders.providerForLanguage(language)
                    : provider.trim();
            if (!context.capabilities().supports(operation, language, selectedProvider)) {
                if ("fail".equals(onUnsupported)) {
                    throw new UnsupportedCapabilityException(operation, language);
                }
                return emitUnsupported(operation, language, selectedProvider,
                        context.identity(), writer);
            }
            return execute(context.query(), context.identity(), writer);
        } finally {
            frameSource = null;
        }
    }

    protected abstract Result execute(QueryService query, SemanticIdentity identity,
                                      SemanticStreamWriter writer);

    protected Set<String> acceptedInputRecords() { return Set.of(); }

    protected String directSeed() { return null; }

    protected int emitIllegalArgument(IllegalArgumentException failure) {
        int exit = 2;
        CliError.emit(CliError.of(SemanticOperationRegistry.entry(this).id(),
                failure, exit));
        return exit;
    }

    protected int emitIllegalState(IllegalStateException failure) {
        int exit = 3;
        CliError.emit(CliError.of(SemanticOperationRegistry.entry(this).id(),
                failure, exit));
        return exit;
    }

    protected int emitRuntimeFailure(RuntimeException failure) {
        int exit = 1;
        CliError.emit(CliError.of(SemanticOperationRegistry.entry(this).id(),
                failure, exit));
        return exit;
    }

    protected final SemanticStreamReader.Summary readFrames(
            Set<String> acceptedRecords, boolean unframed, SemanticIdentity identity,
            Consumer<SemanticStreamReader.SeedFrame> consumer) {
        SemanticFrameSource source = frameSource == null
                ? SemanticFrameSource.ndjson(System.in) : frameSource;
        String operation = SemanticOperationRegistry.entry(this).id();
        return source.readFrames(acceptedRecords, unframed, identity, frame -> {
            for (var record : frame.records()) {
                Object language = record.raw().get("language");
                if (language != null && !com.anatomist.query.semantic.SemanticProviders
                        .supports(String.valueOf(language), operation)) {
                    throw new UnsupportedCapabilityException(operation,
                            String.valueOf(language));
                }
                Object providerId = record.raw().get("provider_id");
                if (providerId != null && !com.anatomist.query.semantic.SemanticProviders
                        .supportsProvider(String.valueOf(providerId), operation)) {
                    throw new UnsupportedCapabilityException(operation,
                            language == null ? "unknown" : String.valueOf(language));
                }
            }
            consumer.accept(frame);
        });
    }

    private Result emitUnsupported(String operation, String language, String providerId,
                                   SemanticIdentity identity,
                                   SemanticStreamWriter writer) {
        String direct = directSeed();
        if (direct != null) {
            writer.write(SemanticRecords.unsupportedEvidence(direct, null,
                    operation, language, providerId, identity));
            return new Result(1, 0, false, false);
        }
        java.util.concurrent.atomic.AtomicInteger seeds = new java.util.concurrent.atomic.AtomicInteger();
        readFrames(acceptedInputRecords(), acceptUnframed, identity,
                frame -> {
                    writer.write(SemanticRecords.unsupportedEvidence(frame.seedId(), null,
                            operation, language, providerId, identity));
                    seeds.incrementAndGet();
                });
        if (seeds.get() == 0) throw new IllegalArgumentException(
                "semantic operation requires an input stream");
        return new Result(seeds.get(), 0, false, false);
    }

    protected record Result(int seeds, int emitted, boolean complete, boolean truncated) {
        Result merge(Result other) {
            return new Result(seeds + other.seeds, emitted + other.emitted,
                    complete && other.complete, truncated || other.truncated);
        }
    }
}
