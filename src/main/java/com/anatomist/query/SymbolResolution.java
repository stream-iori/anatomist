package com.anatomist.query;

import java.util.List;

/** Deterministic result of resolving one CLI symbol selector. */
public record SymbolResolution(String input,
                               TargetKind targetKind,
                               Status status,
                               List<NodeRow> candidates) {

    public enum Status { EXACT, FAMILY, AMBIGUOUS, NOT_FOUND }

    public enum TargetKind { NODE, TYPE, METHOD, FIELD }

    public SymbolResolution {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public List<String> ids() {
        return candidates.stream().map(node -> node.id).toList();
    }

    public boolean isUnique() {
        return candidates.size() == 1
                && (status == Status.EXACT || status == Status.FAMILY);
    }

    public NodeRow requireUnique() {
        if (status == Status.NOT_FOUND || candidates.isEmpty()) {
            throw SymbolResolutionException.notFound(this);
        }
        if (!isUnique()) {
            throw SymbolResolutionException.ambiguous(this);
        }
        return candidates.getFirst();
    }

    public NodeRow requireExact() {
        if (status == Status.NOT_FOUND || candidates.isEmpty()) {
            throw SymbolResolutionException.notFound(this);
        }
        if (status != Status.EXACT || candidates.size() != 1) {
            throw status == Status.FAMILY
                    ? SymbolResolutionException.exactRequired(this)
                    : SymbolResolutionException.ambiguous(this);
        }
        return candidates.getFirst();
    }

    public List<String> requireFamilyOrExactIds() {
        if (status == Status.NOT_FOUND || candidates.isEmpty()) {
            throw SymbolResolutionException.notFound(this);
        }
        if (status == Status.AMBIGUOUS) {
            throw SymbolResolutionException.ambiguous(this);
        }
        return ids();
    }
}
