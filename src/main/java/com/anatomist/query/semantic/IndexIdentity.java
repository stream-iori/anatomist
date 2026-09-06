package com.anatomist.query.semantic;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable identity envelope used to pin every read to one committed index. */
public final class IndexIdentity {
    public static final String CONTRACT = "anatomist-index-identity/v1";

    private IndexIdentity() {}

    public static Map<String, Object> map(Path index, String sourceRoot,
                                          SemanticIdentity identity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", CONTRACT);
        out.put("index_path", index.toAbsolutePath().normalize().toString());
        out.put("index_revision_id", identity.indexRevisionId());
        out.put("source_snapshot_id", identity.sourceSnapshotId());
        out.put("semantic_profile_id", identity.semanticProfileId());
        if (sourceRoot != null && !sourceRoot.isBlank()) out.put("source_root", sourceRoot);
        return out;
    }
}
