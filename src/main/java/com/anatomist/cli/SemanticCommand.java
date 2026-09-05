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
import com.anatomist.query.semantic.SemanticStreamWriter.BrokenPipeException;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.Set;

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

    @Override public final Integer call() {
        Path db = IndexPath.resolve(index);
        try {
            scope = CliValidation.scope(scope, true);
            onUnsupported = CliValidation.choice("--on-unsupported", onUnsupported,
                    "fail", "continue");
            try (QueryService query = new QueryService(db);
                 SemanticStreamWriter writer = new SemanticStreamWriter(System.out, format)) {
                query.selectNodes(module, scope);
                SemanticIdentity identity = SemanticIdentity.read(query.connection());
                SemanticCapabilityRegistry capabilities =
                        new SemanticCapabilityRegistry(query.connection());
                if (!capabilities.supports(requiredCapability())) {
                    if ("fail".equals(onUnsupported)) {
                        throw new UnsupportedCapabilityException(requiredCapability().id());
                    }
                    Result result = emitUnsupported(identity, writer);
                    writer.write(SemanticRecords.streamEvidence(result.seeds(), 0,
                            false, false, identity));
                    return 0;
                }
                Result result = execute(query, identity, writer);
                writer.write(SemanticRecords.streamEvidence(result.seeds(), result.emitted(),
                        result.complete(), result.truncated(), identity));
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
            return CliValidation.emit(failure);
        } catch (UnsupportedCapabilityException failure) {
            System.err.println("ERROR: " + failure.getMessage());
            return 3;
        } catch (IllegalStateException failure) {
            System.err.println("ERROR: " + failure.getMessage());
            return 3;
        } catch (RuntimeException failure) {
            System.err.println("ERROR: " + failure.getMessage());
            return 1;
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

    private Result emitUnsupported(SemanticIdentity identity, SemanticStreamWriter writer) {
        String direct = directSeed();
        if (direct != null) {
            writer.write(SemanticRecords.unsupportedEvidence(direct, null,
                    requiredCapability().id(), identity));
            return new Result(1, 0, false, false);
        }
        java.util.concurrent.atomic.AtomicInteger seeds = new java.util.concurrent.atomic.AtomicInteger();
        SemanticStreamReader.readFrames(System.in, acceptedInputRecords(), acceptUnframed, identity,
                frame -> {
                    writer.write(SemanticRecords.unsupportedEvidence(frame.seedId(), null,
                            requiredCapability().id(), identity));
                    seeds.incrementAndGet();
                });
        if (seeds.get() == 0) throw new IllegalArgumentException(
                "semantic operation requires an input stream");
        return new Result(seeds.get(), 0, false, false);
    }

    protected record Result(int seeds, int emitted, boolean complete, boolean truncated) {}
}
