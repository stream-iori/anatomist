package com.anatomist.query;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Single source of truth for the {@link NodeRow} / {@link EdgeRow} SELECT column
 * lists and their {@link ResultSet} mapping.
 *
 * <p>Previously every query in {@link QueryService} hand-copied the 13-column
 * edge projection and its positional mapping; threading one new column
 * ({@code e.context}) meant editing 6+ sites. Centralizing here makes a column
 * add/remove a one-line change.</p>
 */
final class RowMappers {

    private RowMappers() {}

    /** Node projection (alias {@code n}). Mapped by column name, so the alias is cosmetic. */
    static final String NODE_COLS =
            "n.id, n.symbol_id, n.label, n.kind, n.qualified_name, n.source_file, "
          + "n.source_location, n.module, n.scope, n.javadoc";

    /** {@code FROM edges e} + the two LEFT JOINs onto src/tgt nodes used by flat edge queries. */
    static final String EDGE_FROM_JOINS =
            " FROM edges e "
          + " LEFT JOIN nodes src ON e.source_id = src.id "
          + " LEFT JOIN nodes tgt ON e.target_id = tgt.id ";

    /** Columns shared by a recursive {@code chain} CTE's anchor and recursive members
     *  (everything except the trailing depth term). Append {@code , 1 AS depth} for the
     *  anchor and {@code , c.depth + 1} for the recursive member. */
    static final String CHAIN_CTE_COLS =
            "e.source_id, e.target_id, e.external_target_fqn, e.relation,"
          + " e.call_kind, e.confidence, e.is_external, e.source_file, e.source_location, e.context, e.metadata";

    /** Final projection off a {@code chain} CTE aliased {@code c}, joined to src/tgt nodes.
     *  Column order matches {@link #mapEdge}. */
    static final String EDGE_COLS_CHAIN =
            "c.source_id, c.target_id, c.external_target_fqn, c.relation, c.call_kind, c.confidence,"
          + " c.is_external, c.source_file, c.source_location, c.depth,"
          + " src.label AS src_label, tgt.label AS tgt_label, tgt.qualified_name AS tgt_q, c.context, c.metadata,"
          + " src.symbol_id, src.module, src.scope, tgt.symbol_id, tgt.module, tgt.scope";

    /** Flat (non-recursive) edge projection. {@code depthExpr} is a literal such as
     *  {@code "1"} or a bind placeholder {@code "?"}. Column order matches {@link #mapEdge}. */
    static String edgeColsFlat(String depthExpr) {
        return "e.source_id, e.target_id, e.external_target_fqn, e.relation, e.call_kind,"
             + " e.confidence, e.is_external, e.source_file, e.source_location, " + depthExpr + " AS depth,"
             + " src.label, tgt.label, tgt.qualified_name, e.context, e.metadata,"
             + " src.symbol_id, src.module, src.scope, tgt.symbol_id, tgt.module, tgt.scope";
    }

    static NodeRow mapNode(ResultSet rs) throws SQLException {
        NodeRow n = new NodeRow();
        n.id = rs.getString("id");
        n.symbolId = rs.getString("symbol_id");
        n.label = rs.getString("label");
        n.kind = rs.getString("kind");
        n.qualifiedName = rs.getString("qualified_name");
        n.sourceFile = rs.getString("source_file");
        n.sourceLocation = rs.getString("source_location");
        n.module = rs.getString("module");
        n.scope = rs.getString("scope");
        n.javadoc = rs.getString("javadoc");
        return n;
    }

    static EdgeRow mapEdge(ResultSet rs) throws SQLException {
        EdgeRow r = new EdgeRow();
        r.source = rs.getString(1);
        r.target = rs.getString(2);
        r.externalTargetFqn = rs.getString(3);
        r.relation = rs.getString(4);
        r.callKind = rs.getString(5);
        r.confidence = rs.getString(6);
        r.isExternal = rs.getInt(7) == 1;
        r.sourceFile = rs.getString(8);
        r.sourceLocation = rs.getString(9);
        r.depth = rs.getInt(10);
        r.sourceLabel = rs.getString(11);
        r.targetLabel = rs.getString(12);
        r.targetQualifiedName = rs.getString(13);
        r.context = rs.getString(14);
        r.metadata = rs.getString(15);
        r.sourceSymbolId = rs.getString(16);
        r.sourceModule = rs.getString(17);
        r.sourceScope = rs.getString(18);
        r.targetSymbolId = rs.getString(19);
        r.targetModule = rs.getString(20);
        r.targetScope = rs.getString(21);
        return r;
    }
}
