package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import com.anatomist.query.semantic.SemanticCursor;

import static com.anatomist.query.QueryInfra.qmarks;

/** Query adapter for first-class source call sites. */
final class CallSiteService {
    private final Connection connection;
    private final NodeResolver resolver;

    CallSiteService(Connection connection, NodeResolver resolver) {
        this.connection = connection;
        this.resolver = resolver;
    }

    List<CallSiteRow> calls(String methodRef, String direction) {
        List<CallSiteRow> out = new ArrayList<>();
        try (SemanticCursor<CallSiteRow> cursor = cursor(methodRef, direction)) {
            while (cursor.hasNext()) out.add(cursor.next());
        }
        return out;
    }

    SemanticCursor<CallSiteRow> cursor(String methodRef, String direction) {
        List<String> ids = resolver.resolveMethod(methodRef).requireFamilyOrExactIds();
        String predicate = switch (direction) {
            case "outgoing" -> "cso.caller_id IN (" + qmarks(ids.size()) + ")";
            case "incoming" -> "EXISTS (SELECT 1 FROM call_site_targets hit "
                    + "WHERE hit.call_site_pk=cs.site_pk AND hit.target_id IN ("
                    + qmarks(ids.size()) + "))";
            default -> throw new IllegalArgumentException(
                    "--direction must be outgoing or incoming; got " + direction);
        };
        String sql = "SELECT 'callsite:sha256:'||lower(hex(cs.stable_hash)),cso.caller_id,"
                + "cso.source_file,cs.begin_line,cs.begin_column,"
                + "cs.end_line,cs.end_column,cs.ordinal,cs.context,cs.syntax_target,cs.receiver_static_type,"
                + "cs.dispatch_kind,cs.metadata,cs.origin,cs.resolution_status,cs.producer_id,"
                + "cst.target_id,cst.external_target_fqn,cst.resolution_status,cst.confidence,"
                + "cst.producer_id,tgt.qualified_name FROM call_sites cs "
                + "JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk "
                + "JOIN call_site_targets cst ON cst.call_site_pk=cs.site_pk "
                + "LEFT JOIN nodes tgt ON tgt.id=cst.target_id WHERE " + predicate
                + " ORDER BY cso.source_file,cs.begin_line,cs.begin_column,cs.ordinal,cs.stable_hash,"
                + "cst.target_id,cst.external_target_fqn";
        try {
            PreparedStatement statement = connection.prepareStatement(sql);
            for (int i = 0; i < ids.size(); i++) statement.setString(i + 1, ids.get(i));
            return new Cursor(statement, statement.executeQuery());
        } catch (SQLException failure) {
            throw new RuntimeException("failed to query call sites", failure);
        }
    }

    private static CallSiteRow mapSite(ResultSet rows) {
        try {
            CallSiteRow site = new CallSiteRow();
            site.id = rows.getString(1); site.callerId = rows.getString(2);
            site.sourceFile = rows.getString(3); site.beginLine = rows.getInt(4);
            site.beginColumn = rows.getInt(5); site.endLine = rows.getInt(6);
            site.endColumn = rows.getInt(7); site.ordinal = rows.getInt(8);
            site.context = rows.getString(9); site.syntaxTarget = rows.getString(10);
            site.receiverStaticType = rows.getString(11); site.dispatchKind = rows.getString(12);
            site.metadata = rows.getString(13); site.origin = rows.getString(14);
            site.resolutionStatus = rows.getString(15); site.producerId = rows.getString(16);
            return site;
        } catch (SQLException failure) {
            throw new RuntimeException(failure);
        }
    }

    private static final class Cursor implements SemanticCursor<CallSiteRow> {
        private final PreparedStatement statement;
        private final ResultSet rows;
        private boolean positioned;
        private boolean closed;

        private Cursor(PreparedStatement statement, ResultSet rows) throws SQLException {
            this.statement = statement;
            this.rows = rows;
            this.positioned = rows.next();
        }

        @Override public boolean hasNext() { return positioned && !closed; }

        @Override public CallSiteRow next() {
            if (!hasNext()) throw new NoSuchElementException();
            try {
                CallSiteRow site = mapSite(rows);
                String siteId = site.id;
                do {
                    String internal = rows.getString(17);
                    String external = rows.getString(18);
                    site.targets.add(new CallSiteRow.Target(internal == null ? external : internal,
                            rows.getString(22), external != null, rows.getString(19),
                            rows.getString(20), rows.getString(21)));
                    positioned = rows.next();
                } while (positioned && siteId.equals(rows.getString(1)));
                return site;
            } catch (SQLException failure) {
                close();
                throw new RuntimeException("failed to read call-site cursor", failure);
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { rows.close(); } catch (SQLException ignored) {}
            try { statement.close(); } catch (SQLException ignored) {}
        }
    }
}
