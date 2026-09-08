package com.anatomist.query;

import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VersionCallGraphTest {
    @TempDir Path temporary;
    @Test void snapshotBudgetAndCacheReportActualExhaustion() throws Exception {
        Path project=CliTestSupport.createSimpleMavenProject(temporary,false),db=temporary.resolve("graph.db");
        Files.writeString(project.resolve("src/main/java/p/A.java"),"""
                package p;
                interface I { void m(); }
                class A implements I { public void m() {} }
                class B { void b(I value) { value.m(); } }
                class C { void c(I value) { value.m(); } }
                """);
        CliTestSupport.assertIndexOk(project,"--no-classpath","--java-version","25","--output",db.toString());
        try(QueryService query=new QueryService(db)) {
            String target=DiffNavigation.read(query.connection()).values().stream().filter(d->d.symbol().equals("p.A#m()")).findFirst().orElseThrow().id();
            var recorded=VersionRelationships.read(query.connection(),"ALL",null);
            var graph=new VersionCallGraph(query,recorded,true,20,50,1000);
            assertEquals(2,graph.apply(target).size());int states=graph.states();
            assertEquals(2,graph.apply(target).size());assertEquals(states,graph.states());assertFalse(graph.truncated());
            var exact=new VersionCallGraph(query,recorded,true,20,50,states);
            assertEquals(2,exact.apply(target).size());assertFalse(exact.truncated(),exact.reasons().toString());
            var limited=new VersionCallGraph(query,recorded,true,20,50,states-1);
            limited.apply(target);assertTrue(limited.truncated());assertTrue(limited.reasons().contains("DISPATCH_STATE_LIMIT"));
            var zero=new VersionCallGraph(query,recorded,true,20,50,0);
            assertTrue(zero.apply(target).isEmpty());assertTrue(zero.truncated());
        }
    }
}
