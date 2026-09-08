package com.anatomist.query;

import com.anatomist.json.Json;
import com.anatomist.version.*;
import com.anatomist.store.FileCacheService;
import com.anatomist.store.SnapshotCatalog;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Compares independently validated snapshots; never feeds one revision into another pipeline. */
public final class VersionDiffService {
    public static final String CONTRACT="anatomist-diff/v1";
    public record Result(Map<String,Object> header,List<Map<String,Object>> changes,Map<String,Object> evidence) {
        public Map<String,Object> json() { return Map.of("contract",CONTRACT,"comparison",header,"changes",changes,"evidence",evidence); }
    }
    public Result compare(SnapshotAccess service,SnapshotCatalog.Entry base,SnapshotCatalog.Entry target,
                          String scope,String module,boolean impact,int depth) {
        try(QueryService left=new QueryService(service.database(base.id()));
            QueryService right=new QueryService(service.database(target.id()))) {
            Map<String,Object> header=new LinkedHashMap<>();
            header.put("contract",CONTRACT); header.put("record","diff_header");
            Map<String,Object> baseIdentity=new LinkedHashMap<>(base.json()),targetIdentity=new LinkedHashMap<>(target.json());
            baseIdentity.put("index_revision_id",com.anatomist.query.semantic.SemanticIdentity.read(left.connection()).indexRevisionId());
            targetIdentity.put("index_revision_id",com.anatomist.query.semantic.SemanticIdentity.read(right.connection()).indexRevisionId());
            header.put("base",baseIdentity); header.put("target",targetIdentity);
            boolean profileChanged=!base.profile().equals(target.profile());
            header.put("environment_changed",profileChanged);
            header.put("environment_differences",environment(left.connection(),right.connection()));
            List<Map<String,Object>> changes=new ArrayList<>();
            fileChanges(service,base,target,changes);
            Map<String,String> oldFiles=files(service,base.id()),newFiles=files(service,target.id());
            Set<String> changedFiles=new HashSet<>(oldFiles.keySet());changedFiles.addAll(newFiles.keySet());
            changedFiles.removeIf(file->Objects.equals(oldFiles.get(file),newFiles.get(file)));
            Map<String,Declaration> before=declarations(left.connection(),scope,module,changedFiles);
            Map<String,Declaration> after=declarations(right.connection(),scope,module,changedFiles);
            Set<String> changed=new TreeSet<>();
            Set<String> ids=new TreeSet<>(before.keySet()); ids.addAll(after.keySet());
            for(String id:ids) {
                Declaration a=before.get(id),b=after.get(id);
                if(a!=null && b!=null && a.content().equals(b.content()) && a.signature().equals(b.signature())) continue;
                Map<String,Object> change=new LinkedHashMap<>();
                change.put("record","declaration_change"); change.put("change",a==null?"added":b==null?"deleted":"modified");
                change.put("entity",id);
                if(a!=null) change.put("before",a.json()); if(b!=null) change.put("after",b.json());
                change.put("signature_changed",a==null||b==null||!a.signature().equals(b.signature()));
                change.put("content_changed",a==null||b==null||!a.content().equals(b.content()));
                changes.add(change); changed.add(id);
            }
            var oldRelations=VersionRelationships.read(left.connection(),scope,module);
            var newRelations=VersionRelationships.read(right.connection(),scope,module);
            VersionRelationships.changes(oldRelations,newRelations,changes,changed);
            boolean truncated=false;
            if(impact) {
                truncated=VersionRelationships.impacts("base",oldRelations,changed,depth,changes);
                truncated|=VersionRelationships.impacts("target",newRelations,changed,depth,changes);
            }
            Map<String,Object> evidence=new LinkedHashMap<>();
            evidence.put("record","evidence"); evidence.put("scope","comparison");
            boolean complete=complete(left.connection()) && complete(right.connection());
            evidence.put("complete",complete && !truncated); evidence.put("truncated",truncated);
            evidence.put("negative_conclusion_safe",complete && !profileChanged && !truncated);
            evidence.put("emitted",changes.size());
            return new Result(header,changes,evidence);
        } catch(Exception failure) {
            if(failure instanceof SnapshotException snapshot) throw snapshot;
            throw new SnapshotException("DIFF_FAILED",failure.getMessage(),failure);
        }
    }
    private static Map<String,Object> environment(Connection a,Connection b) throws SQLException {
        Map<String,String> before=metadata(a),after=metadata(b); Map<String,Object> changes=new TreeMap<>();
        for(String key:List.of("java_version","classpath_mode","snapshot_artifacts","spring_xml",
                "semantic_profile_inputs","extension_fingerprint","scan_policy_hash")) {
            if(!Objects.equals(before.get(key),after.get(key))) changes.put(key,Map.of(
                    "before",before.getOrDefault(key,""),"after",after.getOrDefault(key,"")));
        }
        return changes;
    }
    private static Map<String,String> metadata(Connection c) throws SQLException {
        Map<String,String> out=new HashMap<>();
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT key,value FROM project_meta")) {
            while(r.next()) out.put(r.getString(1),r.getString(2));
        } return out;
    }
    private static boolean complete(Connection c) throws SQLException {
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT "
                + "(SELECT count(*) FROM analysis_coverage WHERE status<>'complete') + "
                + "(SELECT count(*) FROM index_diagnostics WHERE severity IN ('warning','error'))")) {
            return r.next() && r.getInt(1)==0;
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,String> files(SnapshotAccess service,String id) throws Exception {
        return (Map<String,String>)Json.parseTree(Files.readString(service.snapshotDirectory(id).resolve("files.json")));
    }
    private static void fileChanges(SnapshotAccess service,SnapshotCatalog.Entry a,SnapshotCatalog.Entry b,
                                    List<Map<String,Object>> changes) throws Exception {
        Map<String,String> before=files(service,a.id()),after=files(service,b.id());
        Set<String> paths=new TreeSet<>(before.keySet()); paths.addAll(after.keySet());
        if(a.checkout().isEmpty() && b.checkout().isEmpty()) {
            String raw=new String(GitRepository.bytes(service.git().root(),"diff","--name-status","-z","-M",
                    a.commit(),b.commit(),"--",service.git().projectRelative().toString().isEmpty()?".":service.git().projectRelative().toString()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] records=raw.split("\0");
            for(int i=0;i<records.length;) {
                String status=records[i++]; if(i>=records.length) break;
                String old=records[i++];
                if(status.startsWith("R") && i<records.length) {
                    String now=records[i++];
                    String prefix=service.git().projectRelative().toString().replace('\\','/');
                    if(!prefix.isEmpty()) {
                        if(!old.startsWith(prefix+"/") || !now.startsWith(prefix+"/")) continue;
                        old=old.substring(prefix.length()+1); now=now.substring(prefix.length()+1);
                    }
                    if(before.containsKey(old) && after.containsKey(now)) {
                        paths.remove(old); paths.remove(now);
                        changes.add(Map.of("record","file_change","change","renamed","before_path",old,"path",now,
                                "similarity",Integer.parseInt(status.substring(1)),"origin","git"));
                    }
                }
            }
        }
        for(String path:paths) {
            String old=before.get(path),now=after.get(path); if(Objects.equals(old,now)) continue;
            Map<String,Object> change=new LinkedHashMap<>();
            change.put("record","file_change"); change.put("path",path);
            change.put("change",old==null?"added":now==null?"deleted":"modified");
            if(old!=null) change.put("before_hash",old); if(now!=null) change.put("after_hash",now);
            changes.add(change);
        }
    }
    private record Declaration(String id,String symbol,String kind,String file,int beginLine,int beginColumn,
                               int endLine,int endColumn,String signature,String content) {
        Map<String,Object> json() {
            return Map.of("id",id,"symbol",symbol,"kind",kind,"file",file,
                    "start_line",beginLine,"start_column",beginColumn,"end_line",endLine,"end_column",endColumn);
        }
    }
    private static Map<String,Declaration> declarations(Connection c,String scope,String module,Set<String> changedFiles) throws Exception {
        Map<String,Declaration> out=new TreeMap<>(); Map<String,CompilationUnit> parsed=new HashMap<>();
        String sql="SELECT *,coalesce(declaration_begin_line,begin_line) bl,coalesce(declaration_begin_column,begin_column) bc,"
                + "coalesce(declaration_end_line,end_line) el,coalesce(declaration_end_column,end_column) ec FROM nodes "
                + "WHERE (declaration_kind IS NOT NULL OR kind='FIELD') AND (?='ALL' OR scope=?) AND (? IS NULL OR module=?)";
        try(PreparedStatement s=c.prepareStatement(sql)) {
            s.setString(1,scope); s.setString(2,scope); s.setString(3,module); s.setString(4,module);
            try(ResultSet r=s.executeQuery()) { while(r.next()) {
                String file=r.getString("source_file"); int bl=r.getInt("bl"),bc=r.getInt("bc"),el=r.getInt("el"),ec=r.getInt("ec");
                String structure=String.join("|",r.getString("symbol_id"),r.getString("kind"),
                        Objects.toString(r.getString("modifiers"),""),Objects.toString(r.getString("visibility"),""));
                String content=structure,signature=structure;
                if(changedFiles.contains(file) && bl>0 && bc>0 && el>0 && ec>0 && file.endsWith(".java")) {
                    CompilationUnit unit=parsed.get(file);
                    if(unit==null) {
                        Path path=SnapshotSource.path(c,file);
                        if(path==null) throw new SnapshotException("SNAPSHOT_SOURCE_MISSING","No frozen source for " + file);
                        String expected;
                        try(PreparedStatement hash=c.prepareStatement("SELECT hash FROM file_cache WHERE source_file=?")) {
                            hash.setString(1,file); try(ResultSet rows=hash.executeQuery()) { expected=rows.next()?rows.getString(1):null; }
                        }
                        if(expected==null || !expected.equals(FileCacheService.sha256(path)))
                            throw new SnapshotException("SNAPSHOT_SOURCE_CORRUPT","Frozen source changed: " + file);
                        var result=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)).parse(path);
                        unit=result.getResult().orElseThrow(()->new SnapshotException("SNAPSHOT_PARSE_FAILED","Cannot parse " + file));
                        parsed.put(file,unit);
                    }
                    Range range=new Range(new Position(bl,bc),new Position(el,ec));
                    StringBuilder tokens=new StringBuilder();
                    for(JavaToken token:unit.getTokenRange().orElseThrow()) {
                        if(!token.getCategory().isWhitespaceOrComment() && token.getRange().map(range::contains).orElse(false))
                            tokens.append(token.getText()).append('\0');
                    }
                    content=FileCacheService.sha256OfString(tokens.toString());
                    Optional<Node> declaration=unit.findAll(Node.class).stream()
                            .filter(n->n instanceof BodyDeclaration<?> || n instanceof VariableDeclarator)
                            .filter(n->n.getRange().map(range::equals).orElse(false)).findFirst();
                    if(declaration.isPresent()) {
                        Node copy=declaration.get().clone();
                        copy.getAllContainedComments().forEach(com.github.javaparser.ast.comments.Comment::remove);
                        copy.walk(n->n.setComment(null));
                        if(copy instanceof TypeDeclaration<?> type) type.getMembers().clear();
                        copy.findAll(MethodDeclaration.class).forEach(m->m.setBody(null));
                        copy.findAll(ConstructorDeclaration.class).forEach(m->m.setBody(new com.github.javaparser.ast.stmt.BlockStmt()));
                        copy.findAll(VariableDeclarator.class).forEach(VariableDeclarator::removeInitializer);
                        signature=structure+"|"+copy;
                    }
                }
                String id=r.getString("id");
                out.put(id,new Declaration(id,r.getString("symbol_id"),r.getString("kind"),file,bl,bc,el,ec,signature,content));
            }}
        } return out;
    }
}
