package com.anatomist.query;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BlockResult {
    public String name;
    public String role;
    public List<String> methods = new ArrayList<>();
    public Set<String> owningTypes = new LinkedHashSet<>();
    public List<EdgeRow> internalEdges = new ArrayList<>();
    public List<EdgeRow> inboundEdges = new ArrayList<>();
    public List<EdgeRow> outboundEdges = new ArrayList<>();
    public List<EdgeRow> fieldsRead = new ArrayList<>();
    public List<EdgeRow> fieldsWritten = new ArrayList<>();
    public List<String> annotations = new ArrayList<>();
    public int[] depthRange = {Integer.MAX_VALUE, Integer.MIN_VALUE};
    public List<String> controlFlowContext = new ArrayList<>();

    public Map<String, Object> toStats() {
        return Map.of(
                "methods", methods.size(),
                "owning_types", owningTypes.size(),
                "internal_edges", internalEdges.size(),
                "fields_read", fieldsRead.size(),
                "fields_written", fieldsWritten.size());
    }
}
