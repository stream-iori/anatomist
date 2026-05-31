package com.anatomist.query;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Composite result for the {@code context} command: a single node + its
 *  contained members + annotations + optional outgoing callees. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContextResult {
    public NodeRow node;
    public List<NodeRow> members = new ArrayList<>();
    public List<Map<String, Object>> annotations = new ArrayList<>();
    /** Only populated when {@code --with-callees[=N]} was requested. */
    public List<EdgeRow> callees;

    public Map<String, Object> toStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("members", members.size());
        s.put("annotations", annotations.size());
        if (callees != null) s.put("callees", callees.size());
        return s;
    }
}
