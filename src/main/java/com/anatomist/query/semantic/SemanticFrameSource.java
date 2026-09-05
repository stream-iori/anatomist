package com.anatomist.query.semantic;

import java.io.InputStream;
import java.util.Set;
import java.util.function.Consumer;

/** Source of already-framed semantic records for standalone or fused execution. */
@FunctionalInterface
public interface SemanticFrameSource {
    SemanticStreamReader.Summary readFrames(Set<String> acceptedRecords,
                                            boolean acceptUnframed,
                                            SemanticIdentity expected,
                                            Consumer<SemanticStreamReader.SeedFrame> consumer);

    static SemanticFrameSource ndjson(InputStream input) {
        return (accepted, unframed, identity, consumer) ->
                SemanticStreamReader.readFrames(input, accepted, unframed, identity, consumer);
    }

    static SemanticFrameSource single(SemanticStreamReader.SeedFrame frame) {
        return (accepted, unframed, identity, consumer) -> {
            for (SemanticRecord record : frame.records()) {
                SemanticStreamReader.validateTypedRecord(record, accepted, identity);
            }
            SemanticStreamReader.validateTypedRecord(frame.evidence(), Set.of("evidence"), identity);
            consumer.accept(frame);
            int records = frame.records().size() + 1;
            return new SemanticStreamReader.Summary(records, frame.records().size(), 1,
                    true, frame.evidence().complete());
        };
    }
}
