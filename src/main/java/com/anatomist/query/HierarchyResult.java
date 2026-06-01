package com.anatomist.query;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HierarchyResult {
    public List<Entry> extendsChain = new ArrayList<>();
    public List<Entry> implementsList = new ArrayList<>();

    public Map<String, Object> toStats() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("extends_depth", extendsChain.isEmpty() ? 0 : extendsChain.size() - 1);
        s.put("implements", implementsList.size());
        return s;
    }

    public static class Entry {
        public String id;
        public String label;
        public String qualifiedName;
        public String role; // "self" | "extends" | "implements"
        public int depth;
        public Boolean isExternal;
        public String externalTargetFqn;
    }
}
