package com.anatomist.query;

import com.anatomist.model.GraphConstants;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.anatomist.query.QueryInfra.qmarks;
import static com.anatomist.query.QueryInfra.runEdgeQuery;
import static com.anatomist.query.QueryInfra.sqlIn;

public class BranchSliceService {

    private final Connection conn;
    private final NodeResolver resolver;
    private final CallGraphService callGraph;
    private final SourceWindowService sourceWindows;

    public BranchSliceService(Connection conn,
                              NodeResolver resolver,
                              CallGraphService callGraph,
                              SourceWindowService sourceWindows) {
        this.conn = conn;
        this.resolver = resolver;
        this.callGraph = callGraph;
        this.sourceWindows = sourceWindows;
    }

    public List<BranchSlice> branchesOf(String methodRef,
                                        int depth,
                                        boolean throughCallbacks,
                                        Integer sourceWindowLines) {
        List<String> seedIds = resolver.resolveMethodIds(methodRef);
        if (seedIds.isEmpty()) return List.of();

        LinkedHashSet<String> ownerIds = new LinkedHashSet<>(seedIds);
        List<EdgeRow> chain = callGraph.calleesOf(methodRef, Math.max(1, depth), throughCallbacks);
        for (EdgeRow edge : chain) {
            if (edge.source != null) ownerIds.add(edge.source);
            if (!edge.isExternal && edge.target != null) ownerIds.add(edge.target);
        }

        List<EdgeRow> rows = branchEdges(new ArrayList<>(ownerIds));
        Map<String, BranchSlice> grouped = new LinkedHashMap<>();
        for (EdgeRow row : rows) {
            if (!ContextFilter.isBranchContext(row.context)) continue;
            NodeRow owner = resolver.readNodeById(row.source);
            String key = row.source + "\n" + row.context;
            BranchSlice slice = grouped.computeIfAbsent(key, ignored -> newSlice(row, owner, sourceWindowLines));
            addEdge(slice, row);
        }
        return new ArrayList<>(grouped.values());
    }

    private List<EdgeRow> branchEdges(List<String> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) return List.of();
        String ph = qmarks(ownerIds.size());
        String sql = "SELECT " + RowMappers.edgeColsFlat("1")
                + RowMappers.EDGE_FROM_JOINS
                + " WHERE e.source_id IN (" + ph + ") "
                + "   AND e.relation IN (" + sqlIn(List.of(
                        GraphConstants.Relation.CALLS,
                        GraphConstants.Relation.READS,
                        GraphConstants.Relation.WRITES)) + ") "
                + "   AND e.context IS NOT NULL "
                + " ORDER BY e.source_id, e.context, e.relation, e.source_location";
        return runEdgeQuery(conn, sql, new ArrayList<>(ownerIds));
    }

    private BranchSlice newSlice(EdgeRow row, NodeRow owner, Integer sourceWindowLines) {
        BranchSegment segment = branchSegment(row.context);
        BranchSlice slice = new BranchSlice();
        slice.owner = row.source;
        slice.ownerLabel = owner != null ? owner.label : row.sourceLabel;
        slice.context = row.context;
        slice.branchKind = segment.kind();
        slice.branchLine = segment.line() > 0 ? segment.line() : null;
        slice.sourceFile = row.sourceFile != null ? row.sourceFile : (owner != null ? owner.sourceFile : null);
        if (sourceWindowLines != null && slice.branchLine != null) {
            slice.sourceWindow = sourceWindows.window(slice.sourceFile, slice.branchLine, Math.max(0, sourceWindowLines));
        }
        return slice;
    }

    private static void addEdge(BranchSlice slice, EdgeRow row) {
        switch (row.relation) {
            case GraphConstants.Relation.CALLS -> slice.calls.add(row);
            case GraphConstants.Relation.READS -> slice.reads.add(row);
            case GraphConstants.Relation.WRITES -> slice.writes.add(row);
            default -> { }
        }
    }

    private static BranchSegment branchSegment(String context) {
        if (context == null || context.isBlank()) return new BranchSegment(null, -1);
        BranchSegment latest = new BranchSegment(null, -1);
        for (String raw : context.split(">")) {
            int at = raw.indexOf('@');
            String kind = at >= 0 ? raw.substring(0, at) : raw;
            if (!ContextFilter.isBranchContext(kind)) {
                continue;
            }
            latest = new BranchSegment(kind, parseLine(raw));
        }
        return latest;
    }

    private static int parseLine(String segment) {
        int l = segment.indexOf("@L");
        if (l < 0) return -1;
        int i = l + 2;
        int start = i;
        while (i < segment.length() && Character.isDigit(segment.charAt(i))) i++;
        if (i == start) return -1;
        try {
            return Integer.parseInt(segment.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private record BranchSegment(String kind, int line) {}
}
