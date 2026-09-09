package com.anatomist.query;

import com.anatomist.test.CliTestSupport;
import com.anatomist.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("system-properties")
class VersionRelationCursorTest {
    @TempDir Path root;
    @Test void streamingGroupsAndLazyCallsMatchRecordedGraph() throws Exception {
        String previous=System.getProperty("user.home");System.setProperty("user.home",root.resolve("home").toString());
        try {
            Path project=CliTestSupport.createSimpleMavenProject(root,false),before=root.resolve("before.db"),after=root.resolve("after.db");
            Path source=project.resolve("src/main/java/p/A.java");
            String original="package p; @Deprecated class A { int one(){return 1;} int two(){return 2;} int call(){return one()+one();} }";
            Files.writeString(source,original);CliTestSupport.assertIndexOk(project,"--no-classpath","--java-version","25","--output",before.toString());
            Files.writeString(source,original.replace("@Deprecated ","").replace("one()+one()","one()+two()"));
            CliTestSupport.assertIndexOk(project,"--no-classpath","--java-version","25","--output",after.toString());
            try(var a=new QueryService(before);var b=new QueryService(after)) {
                for(String scope:List.of("ALL","MAIN","TEST")) {
                    var old=VersionRelationships.read(a.connection(),scope,null);var now=VersionRelationships.read(b.connection(),scope,null);
                    var expected=new ArrayList<Map<String,Object>>();Set<String> expectedSeeds=new TreeSet<>(),actualSeeds=new TreeSet<>();
                    VersionRelationships.changes(old,now,expected,expectedSeeds);
                    try(var actual=new DiffRows(root.resolve("results"),8)) {
                        VersionRelationCursor.changes(a.connection(),b.connection(),scope,null,actual,actualSeeds);
                        assertEquals(Json.writeCompact(expected),Json.writeCompact(actual));assertEquals(expectedSeeds,actualSeeds);
                    }
                    var lazy=new VersionCallGraph(b,false);var eager=new VersionCallGraph(b,now,false);
                    if(scope.equals("ALL")) for(var node:DiffNavigation.read(b.connection()).values())
                        assertEquals(eager.apply(node.id()),lazy.apply(node.id()));
                }
            }
        } finally { System.setProperty("user.home",previous); }
    }
}
