package com.anatomist.query;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static com.anatomist.query.QueryInfra.rethrow;

/** Bounded, cycle-safe annotation queries over direct facts and persisted meta edges. */
final class AnnotationQueryService {
    static final int MAX_META_DEPTH = 16;

    private final Connection connection;

    AnnotationQueryService(Connection connection) {
        this.connection = connection;
    }

    List<AnnotationRow> annotations(String entity, boolean includeMeta) {
        String sql = includeMeta ? expandedSql() : directSql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entity);
            if (includeMeta) statement.setInt(2, MAX_META_DEPTH);
            try (ResultSet rows = statement.executeQuery()) {
                List<AnnotationRow> out = new ArrayList<>();
                while (rows.next()) out.add(map(rows));
                return List.copyOf(out);
            }
        } catch (SQLException failure) {
            throw rethrow(failure);
        }
    }

    private static String directSql() {
        return "SELECT a.node_id,COALESCE(a.annotation_fqn,a.raw_name),a.raw_name,a.attributes,"
                + "a.target_kind,a.target_path,a.language,a.mechanism,a.resolution_status,a.source_file,"
                + "a.source_location,a.begin_line,a.begin_column,a.end_line,a.end_column,a.producer_id,"
                + "1 AS direct,0 AS meta_depth,COALESCE(a.annotation_fqn,a.raw_name) AS via "
                + "FROM annotations a WHERE a.node_id=? ORDER BY a.begin_line,a.begin_column,a.id";
    }

    private static String expandedSql() {
        return "WITH RECURSIVE expanded(annotation_id,node_id,name,raw_name,attributes,target_kind,target_path,"
                + "language,mechanism,resolution_status,source_file,source_location,begin_line,begin_column,"
                + "end_line,end_column,producer_id,depth,via,seen) AS ("
                + "SELECT a.id,a.node_id,COALESCE(a.annotation_fqn,a.raw_name),a.raw_name,a.attributes,a.target_kind,"
                + "a.target_path,a.language,a.mechanism,a.resolution_status,a.source_file,a.source_location,"
                + "a.begin_line,a.begin_column,a.end_line,a.end_column,a.producer_id,0,"
                + "COALESCE(a.annotation_fqn,a.raw_name),'>'||COALESCE(a.annotation_fqn,a.raw_name)||'>' "
                + "FROM annotations a WHERE a.node_id=? UNION ALL "
                + "SELECT x.annotation_id,x.node_id,m.meta_annotation_fqn,m.raw_name,NULL,x.target_kind,x.target_path,"
                + "m.language,m.mechanism,m.resolution_status,x.source_file,x.source_location,x.begin_line,x.begin_column,"
                + "x.end_line,x.end_column,m.producer_id,x.depth+1,x.via||'>'||m.meta_annotation_fqn,"
                + "x.seen||m.meta_annotation_fqn||'>' FROM expanded x JOIN (SELECT annotation_fqn,"
                + "meta_annotation_fqn,min(raw_name) AS raw_name,min(language) AS language,"
                + "min(mechanism) AS mechanism,min(resolution_status) AS resolution_status,"
                + "min(producer_id) AS producer_id FROM annotation_meta_relations GROUP BY annotation_fqn,"
                + "meta_annotation_fqn) m "
                + "ON m.annotation_fqn=x.name WHERE x.depth<? AND m.meta_annotation_fqn IS NOT NULL "
                + "AND instr(x.seen,'>'||m.meta_annotation_fqn||'>')=0) "
                + "SELECT node_id,name,raw_name,attributes,target_kind,target_path,language,mechanism,"
                + "resolution_status,source_file,source_location,begin_line,begin_column,end_line,end_column,"
                + "producer_id,CASE WHEN depth=0 THEN 1 ELSE 0 END AS direct,depth AS meta_depth,via "
                + "FROM expanded ORDER BY begin_line,begin_column,annotation_id,depth,name";
    }

    private static AnnotationRow map(ResultSet row) throws SQLException {
        return new AnnotationRow(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), row.getString(6), row.getString(7), row.getString(8), row.getString(9),
                row.getString(10), row.getString(11), nullable(row, 12), nullable(row, 13),
                nullable(row, 14), nullable(row, 15), row.getString(16), row.getInt(17) != 0,
                row.getInt(18), row.getString(19));
    }

    private static Integer nullable(ResultSet row, int index) throws SQLException {
        int value = row.getInt(index);
        return row.wasNull() ? null : value;
    }
}
