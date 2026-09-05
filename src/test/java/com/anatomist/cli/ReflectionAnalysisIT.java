package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
import com.anatomist.test.CliTestSupport.RunResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionAnalysisIT {
    @Test void reflectionCallsAreCanonicalCallSites(@TempDir Path tmp) throws Exception {
        Fixture fixture=createProject(tmp); index(fixture,false);
        assertEquals(1,scalar(fixture.db(),"""
                SELECT count(*) FROM call_sites cs
                JOIN call_site_targets cst ON cst.call_site_pk=cs.site_pk
                JOIN nodes target ON target.id=cst.target_id
                WHERE cs.dispatch_kind='REFLECTION'
                  AND target.symbol_id='p.Target#echo(java.lang.String)'
                  AND cs.metadata LIKE '%"operation":"METHOD_INVOKE"%'
                """), () -> "reflection graph=" + uncheckedGraph(fixture.db()));
        assertTrue(scalar(fixture.db(),"SELECT count(*) FROM call_sites WHERE dispatch_kind='REFLECTION' AND metadata LIKE '%\"operation\":\"CONSTRUCTOR_NEW_INSTANCE\"%'")>=1);
        assertEquals(0,scalar(fixture.db(),"SELECT count(*) FROM edges WHERE relation='CALLS'"));
    }

    @Test void incrementalReplacementMatchesFreshGraph(@TempDir Path tmp) throws Exception {
        Fixture fixture=createProject(tmp); index(fixture,false);
        Files.writeString(fixture.caller(),Files.readString(fixture.caller(),StandardCharsets.UTF_8).replace("\"echo\"","\"alternate\""),StandardCharsets.UTF_8);
        index(fixture,true);
        assertEquals(0,reflectionTargetCount(fixture.db(),"p.Target#echo(java.lang.String)"));
        assertEquals(1,reflectionTargetCount(fixture.db(),"p.Target#alternate(java.lang.String)"));
        Path fresh=tmp.resolve("fresh.db"); Fixture freshFixture=new Fixture(fixture.project(),fixture.sourceRoot(),fixture.caller(),fresh);
        index(freshFixture,false); assertEquals(canonicalReflectionGraph(fresh),canonicalReflectionGraph(fixture.db()));
    }

    private static int reflectionTargetCount(Path db,String symbol)throws Exception{return scalar(db,"SELECT count(*) FROM call_sites cs JOIN call_site_targets cst ON cst.call_site_pk=cs.site_pk JOIN nodes target ON target.id=cst.target_id WHERE cs.dispatch_kind='REFLECTION' AND target.symbol_id='"+symbol+"' AND cs.metadata LIKE '%\"operation\":\"METHOD_INVOKE\"%'");}
    private static Fixture createProject(Path tmp)throws Exception{
        Path project=tmp.resolve("reflection-project"),sourceRoot=project.resolve("src/main/java"),pkg=sourceRoot.resolve("p");Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Target.java"),"package p; public class Target { public String echo(String value){return Helper.done(value);} public String alternate(String value){return Helper.done(value);} }",StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("Helper.java"),"package p; public class Helper { public static String done(String value){return value;} }",StandardCharsets.UTF_8);
        Path caller=pkg.resolve("Caller.java");Files.writeString(caller,"""
                package p; import java.lang.reflect.Constructor; import java.lang.reflect.Method;
                public class Caller { public void run() throws Exception {
                  String className="p."+"Target"; Class<?> type=Class.forName(className);
                  Method method=type.getMethod("echo",String.class); method.invoke(new Target(),"value");
                  Constructor<?> constructor=type.getConstructor(); constructor.newInstance(); } }
                """,StandardCharsets.UTF_8);
        return new Fixture(project,sourceRoot,caller,tmp.resolve("reflection.db"));
    }
    private static void index(Fixture fixture,boolean incremental)throws Exception{List<String> args=new ArrayList<>(List.of("--project-source",fixture.sourceRoot().toString(),"--no-classpath","--java-version","17","--output",fixture.db().toString()));if(incremental)args.add("--incremental");RunResult indexed=CliTestSupport.runIndex(fixture.project(),args.toArray(String[]::new));assertEquals(0,indexed.exitCode(),indexed.stderr());}
    private static int scalar(Path db,String sql)throws Exception{try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+db);Statement statement=connection.createStatement();ResultSet result=statement.executeQuery(sql)){assertTrue(result.next());return result.getInt(1);}}
    private static List<String> canonicalReflectionGraph(Path db)throws Exception{try(Connection connection=DriverManager.getConnection("jdbc:sqlite:"+db);Statement statement=connection.createStatement();ResultSet result=statement.executeQuery("SELECT source.symbol_id||'|CALLS|'||COALESCE(target.symbol_id,cst.external_target_fqn)||'|'||cs.dispatch_kind||'|'||cs.metadata FROM call_sites cs JOIN call_site_owners cso ON cso.owner_pk=cs.owner_pk JOIN call_site_targets cst ON cst.call_site_pk=cs.site_pk JOIN nodes source ON source.id=cso.caller_id LEFT JOIN nodes target ON target.id=cst.target_id WHERE cs.metadata LIKE '%\"via\":\"reflection\"%' ORDER BY 1")){List<String> rows=new ArrayList<>();while(result.next())rows.add(result.getString(1));return rows;}}
    private static List<String> uncheckedGraph(Path db){try{return canonicalReflectionGraph(db);}catch(Exception e){return List.of(e.toString());}}
    private record Fixture(Path project,Path sourceRoot,Path caller,Path db){}
}
