package com.anatomist.query;

import java.util.List;

/** Result rows plus the bounds that constrained a graph traversal. */
public record TraversalResult<T>(
        List<T> items,
        int requestedDepth,
        int effectiveDepth,
        int reachedDepth,
        boolean depthTruncated,
        int frontierCount,
        boolean limitTruncated
) {
    public TraversalResult {
        items = List.copyOf(items);
    }
}
