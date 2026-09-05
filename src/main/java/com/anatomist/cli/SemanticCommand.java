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
        Path db = IndexPath.resolve(index);
        try {
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
        } catch (SemanticStreamException failure) {
            System.err.println("ERROR: " + failure.code() + ": " + failure.getMessage());
            return streamConflict(failure.code()) ? 4 : 2;
        } catch (SymbolResolutionException failure) {
            System.err.println("ERROR: " + failure.code() + ": " + failure.getMessage());
            return 2;
        } catch (IllegalArgumentException failure) {
            return emitIllegalArgument(failure);
        } catch (UnsupportedCapabilityException failure) {
            System.err.println("ERROR: " + failure.getMessage());
            return 3;
        } catch (IllegalStateException failure) {
            return emitIllegalState(failure);
        } catch (RuntimeException failure) {
            return emitRuntimeFailure(failure);
        }
    }

    final Result executeStage(SemanticExecutionContext context, SemanticFrameSource input,
                              SemanticStreamWriter writer) {
        onUnsupported = CliValidation.choice("--on-unsupported", onUnsupported,
                "fail", "continue");
        frameSource = input;
        try {
            if (!context.capabilities().supports(requiredCapability())) {
                if ("fail".equals(onUnsupported)) {
                    throw new UnsupportedCapabilityException(requiredCapability().id());
                }
                return emitUnsupported(context.identity(), writer);
            }
            return execute(context.query(), context.identity(), writer);
        } finally {
            frameSource = null;
        }
    }

    private static boolean streamConflict(String code) {
        return code.contains("EVIDENCE") || code.contains("PIPELINE")
                || code.contains("PROFILE") || code.contains("SNAPSHOT")
                || code.equals("RECORD_AFTER_STREAM_EVIDENCE");
    }

    protected abstract Result execute(QueryService query, SemanticIdentity identity,
                                      SemanticStreamWriter writer);

    protected SemanticCapabilityRegistry.Capability requiredCapability() {
        return SemanticCapabilityRegistry.Capability.STREAM;
    }

    protected Set<String> acceptedInputRecords() { return Set.of(); }

    protected String directSeed() { return null; }

    protected int emitIllegalArgument(IllegalArgumentException failure) {
        return CliValidation.emit(failure);
    }

    protected int emitIllegalState(IllegalStateException failure) {
        System.err.println("ERROR: " + failure.getMessage());
        return 3;
    }

    protected int emitRuntimeFailure(RuntimeException failure) {
        System.err.println("ERROR: " + failure.getMessage());
        return 1;
    }

    protected final SemanticStreamReader.Summary readFrames(
            Set<String> acceptedRecords, boolean unframed, SemanticIdentity identity,
            Consumer<SemanticStreamReader.SeedFrame> consumer) {
        SemanticFrameSource source = frameSource == null
                ? SemanticFrameSource.ndjson(System.in) : frameSource;
        return source.readFrames(acceptedRecords, unframed, identity, consumer);
    }

    private Result emitUnsupported(SemanticIdentity identity, SemanticStreamWriter writer) {
        String direct = directSeed();
        if (direct != null) {
            writer.write(SemanticRecords.unsupportedEvidence(direct, null,
                    requiredCapability().id(), identity));
            return new Result(1, 0, false, false);
        }
        java.util.concurrent.atomic.AtomicInteger seeds = new java.util.concurrent.atomic.AtomicInteger();
        readFrames(acceptedInputRecords(), acceptUnframed, identity,
                frame -> {
                    writer.write(SemanticRecords.unsupportedEvidence(frame.seedId(), null,
                            requiredCapability().id(), identity));
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
