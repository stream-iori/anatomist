package com.anatomist.query;

import com.anatomist.model.GraphConstants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.anatomist.query.QueryInfra.rethrow;

/** Storage adapter for language-neutral containment and cross-domain relations. */
final class GenericSemanticService {
    private final Connection connection;
    private final NodeResolver resolver;

    GenericSemanticService(Connection connection, NodeResolver resolver) {
        this.connection = connection;
        this.resolver = resolver;
    }

    List<NodeRow> members(String container, boolean recursive, int maxDepth, int limit) {
        String relations = "'" + GraphConstants.Relation.CONTAINS + "','"
                + GraphConstants.Relation.CONFIGURES + "','" + GraphConstants.Relation.XML_CONTAINS + "'";
        String sql;
        if (recursive) {
            sql = "WITH RECURSIVE contained(id,depth) AS ("
                    + "SELECT target_id,1 FROM edges WHERE source_id=? AND relation IN (" + relations + ") AND is_external=0 "
                    + "UNION SELECT e.target_id,c.depth+1 FROM edges e JOIN contained c ON e.source_id=c.id "
                    + "WHERE e.relation IN (" + relations + ") AND e.is_external=0 AND c.depth<?) "
                    + "SELECT " + RowMappers.NODE_COLS + " FROM contained c JOIN nodes n ON n.id=c.id "
                    + "WHERE 1=1" + resolver.selectorClause("n") + " ORDER BY c.depth,n.source_ordinal,n.source_location,n.id LIMIT ?";
        } else {
            sql = "SELECT " + RowMappers.NODE_COLS + " FROM edges e JOIN nodes n ON n.id=e.target_id "
                    + "WHERE e.source_id=? AND e.relation IN (" + relations + ") AND e.is_external=0"
                    + resolver.selectorClause("n") + " ORDER BY n.source_ordinal,n.source_location,n.id LIMIT ?";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, container);
            if (recursive) { statement.setInt(2, maxDepth); statement.setInt(3, limit); }
            else statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<NodeRow> out = new ArrayList<>();
                while (rows.next()) out.add(RowMappers.mapNode(rows));
                return List.copyOf(out);
            }
        } catch (SQLException failure) { throw rethrow(failure); }
    }

    List<EdgeRow> bindings(String entity, String direction, String semantic, int limit) {
        String relationFilter = switch (semantic) {
            case "realizes" -> "'DEFINED_BY'";
            case "wires" -> "'WIRES','INJECTS'";
            case "parent" -> "'PARENT_BEAN'";
            case "factory" -> "'FACTORY_BEAN'";
            case "member" -> "'BINDS_TO'";
            default -> "'DEFINED_BY','BINDS_TO','WIRES','INJECTS','PARENT_BEAN','FACTORY_BEAN'";
        };
        if ("member".equals(semantic) && "outgoing".equals(direction)) {
            String sql = "WITH RECURSIVE config(id) AS (SELECT ? UNION SELECT e.target_id FROM edges e "
                    + "JOIN config c ON e.source_id=c.id WHERE e.is_external=0 AND e.relation IN "
                    + "('CONFIGURES','XML_CONTAINS')) SELECT " + RowMappers.edgeColsFlat("1")
                    + RowMappers.EDGE_FROM_JOINS
                    + " WHERE e.source_id IN (SELECT id FROM config) AND e.relation='BINDS_TO' "
                    + "ORDER BY e.source_file,e.source_ordinal,e.id LIMIT ?";
            return QueryInfra.runEdgeQuery(connection, sql, List.of(entity, limit));
        }
        String endpoint = "outgoing".equals(direction) ? "e.source_id" : "e.target_id";
        String sql = "SELECT " + RowMappers.edgeColsFlat("1") + RowMappers.EDGE_FROM_JOINS
                + " WHERE " + endpoint + "=? AND e.relation IN (" + relationFilter + ") "
                + "ORDER BY e.relation,e.source_id,e.target_id,e.external_target_fqn LIMIT ?";
        return QueryInfra.runEdgeQuery(connection, sql, List.of(entity, limit));
    }

    List<GenericSemanticRows.Site> sites(String entity, String direction,
                                          Set<String> relations, int limit) {
        List<GenericSemanticRows.Site> out = new ArrayList<>();
        if (relations.contains(GraphConstants.Relation.CALLS)) {
            out.addAll(callSites(entity, direction, limit));
        }
        Set<String> edgeRelations = new HashSet<>(relations);
        edgeRelations.remove(GraphConstants.Relation.CALLS);
        if (!edgeRelations.isEmpty()) out.addAll(edgeSites(entity, direction, edgeRelations, limit));
        out.sort(java.util.Comparator.comparing(GenericSemanticRows.Site::sourceFile,
                        java.util.Comparator.nullsLast(String::compareTo))
                .thenComparing(GenericSemanticRows.Site::beginLine,
                        java.util.Comparator.nullsLast(Integer::compareTo))
                .thenComparing(GenericSemanticRows.Site::beginColumn,
                        java.util.Comparator.nullsLast(Integer::compareTo))
                .thenComparing(GenericSemanticRows.Site::ordinal,
                        java.util.Comparator.nullsLast(Integer::compareTo)));
        return List.copyOf(out.subList(0, Math.min(limit, out.size())));
    }

    private List<GenericSemanticRows.Site> edgeSites(String entity, String direction,
                                                      Set<String> relations, int limit) {
        String endpoint = "outgoing".equals(direction) ? "source_id" : "target_id";
        String placeholders = String.join(",", java.util.Collections.nCopies(relations.size(), "?"));
        String sql = "SELECT source_id,target_id,external_target_fqn,relation,source_file,"
                + "begin_line,begin_column,end_line,end_column,source_ordinal,context,producer_id,"
                + "confidence,resolution FROM edges WHERE " + endpoint + "=? AND relation IN ("
                + placeholders + ") ORDER BY source_file,begin_line,begin_column,source_ordinal LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, entity);
            for (String relation : relations) statement.setString(index++, relation);
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<GenericSemanticRows.Site> out = new ArrayList<>();
                while (rows.next()) out.add(new GenericSemanticRows.Site(
                        rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), nullable(rows, 6), nullable(rows, 7), nullable(rows, 8),
                        nullable(rows, 9), nullable(rows, 10), rows.getString(11), rows.getString(12),
                        rows.getString(13), rows.getString(14)));
                return List.copyOf(out);
            }
        } catch (SQLException failure) { throw rethrow(failure); }
    }

    private List<GenericSemanticRows.Site> callSites(String entity, String direction, int limit) {
        String predicate = "outgoing".equals(direction) ? "cso.caller_id=?"
                : "cst.target_id=?";
        String sql = "SELECT cso.caller_id,cst.target_id,cst.external_target_fqn,'CALLS',"
                + "cso.source_file,cs.begin_line,cs.begin_column,cs.end_line,cs.end_column,"
                + "cs.ordinal,cs.context,cs.producer_id,cst.confidence,NULL FROM call_sites cs "
                + "JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "JOIN call_site_targets cst ON cst.call_site_pk=cs.site_pk WHERE " + predicate
                + " ORDER BY cso.source_file,cs.begin_line,cs.begin_column,cs.ordinal LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entity); statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<GenericSemanticRows.Site> out = new ArrayList<>();
                while (rows.next()) out.add(new GenericSemanticRows.Site(
                        rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), nullable(rows, 6), nullable(rows, 7), nullable(rows, 8),
                        nullable(rows, 9), nullable(rows, 10), rows.getString(11), rows.getString(12),
                        rows.getString(13), rows.getString(14)));
                return out;
            }
        } catch (SQLException failure) { throw rethrow(failure); }
    }

    private static Integer nullable(ResultSet rows, int column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }

    List<GenericSemanticRows.Site> resolvedCallPath(String start, String endSelector, int maxDepth) {
        String end = resolver.resolveMethod(endSelector).requireUnique().id;
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(start);
        Set<String> visited = new HashSet<>();
        visited.add(start);
        Map<String, String> parent = new LinkedHashMap<>();
        Map<String, GenericSemanticRows.Site> hops = new HashMap<>();
        int depth = 0;
        while (!frontier.isEmpty() && depth++ < maxDepth && !visited.contains(end)) {
            int width = frontier.size();
            while (width-- > 0 && !visited.contains(end)) {
                String current = frontier.removeFirst();
                for (GenericSemanticRows.Site site : sites(current, "outgoing",
                        Set.of(GraphConstants.Relation.CALLS), 100_000)) {
                    String next = site.target();
                    if (next == null || !visited.add(next)) continue;
                    parent.put(next, current);
                    hops.put(next, site);
                    frontier.addLast(next);
                    if (next.equals(end)) break;
                }
            }
        }
        if (!visited.contains(end)) return List.of();
        List<GenericSemanticRows.Site> reversed = new ArrayList<>();
        for (String current = end; !current.equals(start); current = parent.get(current)) {
            GenericSemanticRows.Site hop = hops.get(current);
            if (hop == null) return List.of();
            reversed.add(hop);
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }
}
