package com.anatomist.query;

import com.anatomist.query.semantic.RelationshipIdentity;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Sorted normalized relation groups; SQLite sorts rows without retaining the graph in Java. */
final class VersionRelationCursor implements AutoCloseable {
    private static final List<String> FIELDS=List.of("source","target","relation","semantic","mechanism","call_kind","resolution","confidence","provider_id","producer_id");
    private final PreparedStatement statement;
    private final ResultSet rows;
    private boolean available;
    VersionRelationCursor(Connection connection,String scope,String module) throws SQLException {
        String filter=" WHERE (?='ALL' OR n.scope=?) AND (? IS NULL OR n.module=?)";
        String raw="SELECT e.source_id source,coalesce(e.target_id,e.external_target_fqn) target,e.relation,e.semantic,e.mechanism,e.call_kind,e.resolution,e.confidence,e.provider_id,e.producer_id,e.source_file file,e.begin_line line,e.begin_column col FROM edges e JOIN nodes n ON n.id=e.source_id"+filter
                +" UNION ALL SELECT o.caller_id,coalesce(t.target_id,t.external_target_fqn),'CALLS',cs.receiver_static_type,cs.syntax_target,cs.dispatch_kind,t.resolution_status,t.confidence,cs.provider_id,cs.producer_id,o.source_file,cs.begin_line,cs.begin_column FROM call_sites cs JOIN call_site_owners o ON o.owner_pk=cs.owner_pk JOIN call_site_targets t ON t.call_site_pk=cs.site_pk JOIN nodes n ON n.id=o.caller_id"+filter
                +" UNION ALL SELECT a.node_id,coalesce(a.annotation_fqn,a.raw_name),'ANNOTATION',a.attributes,a.mechanism,a.target_kind,a.resolution_status,a.target_path,a.provider_id,a.producer_id,a.source_file,a.begin_line,a.begin_column FROM annotations a JOIN nodes n ON n.id=a.node_id"+filter;
        String normalized=String.join(",",FIELDS.stream().map(f->"coalesce("+f+",'') AS "+f).toList());
        statement=connection.prepareStatement("WITH raw AS ("+raw+") SELECT "+normalized+",coalesce(file,'') file,coalesce(line,0) line,coalesce(col,0) col FROM raw ORDER BY "+String.join(",",FIELDS)+",file,line,col");
        try {
            for(int start=1;start<=9;start+=4) { statement.setString(start,scope);statement.setString(start+1,scope);statement.setString(start+2,module);statement.setString(start+3,module); }
            rows=statement.executeQuery();available=rows.next();
        } catch(SQLException failure) { statement.close();throw failure; }
    }
    private Map<String,Object> fields() throws SQLException {
        Map<String,Object> result=new TreeMap<>();for(String field:FIELDS) result.put(field,rows.getString(field));return result;
    }
    VersionRelationships.Relation next() throws SQLException {
        if(!available) return null;
        Map<String,Object> fields=fields();int count=0;List<Map<String,Object>> sites=new ArrayList<>();
        do {
            count++;
            if(sites.size()<3) sites.add(Map.of("file",rows.getString("file"),"line",rows.getInt("line"),"column",rows.getInt("col")));
            available=rows.next();
        } while(available && fields.equals(fields()));
        return new VersionRelationships.Relation(fields,count,List.copyOf(sites));
    }
    private static int compare(VersionRelationships.Relation a,VersionRelationships.Relation b) {
        for(String field:FIELDS) {
            int order=Arrays.compareUnsigned(a.fields().get(field).toString().getBytes(StandardCharsets.UTF_8),b.fields().get(field).toString().getBytes(StandardCharsets.UTF_8));
            if(order!=0) return order;
        }
        return 0;
    }
    static void changes(Connection left,Connection right,String scope,String module,List<Map<String,Object>> output,Set<String> seeds) throws SQLException {
        // Only deltas are retained for the historical stable identity ordering.
        TreeMap<String,Integer> orderById=new TreeMap<>();
        if(!(output instanceof DiffRows owned)) throw new IllegalArgumentException("Relation comparison requires owned result storage");
        try(var deltas=new DiffRows(owned.directory());var a=new VersionRelationCursor(left,scope,module);var b=new VersionRelationCursor(right,scope,module)) {
            var before=a.next();var after=b.next();
            while(before!=null || after!=null) {
                int order=before==null?1:after==null?-1:compare(before,after);
                var old=order<=0?before:null;var now=order>=0?after:null;
                if(old==null || now==null || old.count()!=now.count()) {
                    String id=RelationshipIdentity.of("version-relation",(now==null?old:now).fields());
                    List<Map<String,Object>> row=new ArrayList<>(1);
                    VersionRelationships.changes(old==null?Map.of():Map.of(id,old),now==null?Map.of():Map.of(id,now),row,seeds);
                    orderById.put(id,deltas.size());deltas.add(row.getFirst());
                }
                if(order<=0) before=a.next();if(order>=0) after=b.next();
            }
            for(int index:orderById.values()) output.add(deltas.get(index));
        }
    }
    @Override public void close() throws SQLException { try { rows.close(); } finally { statement.close(); } }
}
