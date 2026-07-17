package com.anatomist.flow;

import com.anatomist.core.IndexDiagnostic;

import java.util.ArrayList;
import java.util.List;

public final class FlowResult {
    public final List<FlowNode> nodes = new ArrayList<>();
    public final List<FlowEdge> edges = new ArrayList<>();
    public final List<MethodFlowSummary> summaries = new ArrayList<>();
    public final List<IndexDiagnostic> diagnostics = new ArrayList<>();

    public void addAll(FlowResult other) {
        if (other == null) return;
        nodes.addAll(other.nodes);
        edges.addAll(other.edges);
        summaries.addAll(other.summaries);
        diagnostics.addAll(other.diagnostics);
    }

    public int factCount() {
        return nodes.size() + edges.size() + summaries.size();
    }
}
