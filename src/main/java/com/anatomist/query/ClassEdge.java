package com.anatomist.query;

/**
 * One aggregated class-to-class internal edge, with both endpoints' kind /
 * abstractness so the HTML export can colour types and split inheritance
 * (solid) from dependency (dashed) edges. Produced by
 * {@link QueryService#classDepsInternal(int)}; serialized by the {@code CLASS_EDGE}
 * codec in {@code DtoCodecs} (snake_case keys consumed by {@code template.html}).
 *
 * <p>{@code isInherit} is true when any of the merged underlying edges is an
 * IMPLEMENTS / INHERITS relation; otherwise the pair is purely a dependency
 * (CALLS / REFERENCES / WIRES). {@code edgeCount} is the number of underlying
 * edges folded into this pair.</p>
 */
public record ClassEdge(
        String source,
        String sourceLabel,
        String sourcePackage,
        String sourceKind,
        boolean sourceAbstract,
        String target,
        String targetLabel,
        String targetPackage,
        String targetKind,
        boolean targetAbstract,
        boolean isInherit,
        int edgeCount) {
}
