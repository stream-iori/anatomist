package com.anatomist.query;

import com.anatomist.store.SqliteStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DiffCoverageTest {
    @TempDir Path temp;
    private Map<String,String> metadata() {
        return Map.of("source_root","/project","scan_policy","anatomist-scan-policy-v1\nscope=MAIN\n",
                "source_layout","api@MAIN=/project/api/src/main/java");
    }
    @Test void emptyQueryDoesNotProveUnindexedTestScope() throws Exception {
        try(var store=new SqliteStore(temp.resolve("index.db"))) {
            store.initSchema();var coverage=new DiffCoverage(store.connection(),metadata(),Set.of("src/test/java/Check.java"));
            assertTrue(coverage.reasons("TEST",null,"declarations").contains("SCOPE_NOT_INDEXED"));
            assertFalse(coverage.reasons("ALL",null,"declarations").isEmpty());
        }
    }
    @Test void oldMetadataIsUnknownNotComplete() throws Exception {
        try(var store=new SqliteStore(temp.resolve("index.db"))) {
            store.initSchema();var coverage=new DiffCoverage(store.connection(),Map.of(),Set.of());
            assertFalse(coverage.known());assertTrue(coverage.reasons("MAIN",null,"declarations").contains("SCAN_COVERAGE_UNKNOWN"));
        }
    }
    @Test void knownEmptyRootCanEstablishNoDeclarations() throws Exception {
        try(var store=new SqliteStore(temp.resolve("index.db"))) {
            store.initSchema();var coverage=new DiffCoverage(store.connection(),metadata(),Set.of("README.md"));
            assertTrue(coverage.known());assertTrue(coverage.hasModule("api"));assertFalse(coverage.hasModule("unknown"));
            assertTrue(coverage.reasons("MAIN","api","declarations").isEmpty());
        }
    }
    @Test void omittedSourceInSelectedRootIsDisclosed() throws Exception {
        try(var store=new SqliteStore(temp.resolve("index.db"))) {
            store.initSchema();var coverage=new DiffCoverage(store.connection(),metadata(),Set.of("api/src/main/java/A.java"));
            assertTrue(coverage.reasons("MAIN","api","declarations").contains("SOURCE_NOT_INDEXED"));
            assertTrue(coverage.reasons("MAIN","other","declarations").isEmpty());
        }
    }
    @Test void environmentChangesLimitCausalClaimsSeparately() {
        var evidence=DiffCoverage.evidence(Set.of(),true,false);
        assertEquals(true,evidence.get("complete"));assertEquals(false,evidence.get("negative_conclusion_safe"));
        assertEquals(List.of("ENVIRONMENT_CHANGED"),evidence.get("reasons"));
    }
    @Test void globalCoverageGapAppliesToEverySelection() throws Exception {
        try(var store=new SqliteStore(temp.resolve("index.db"))) {
            store.initSchema();
            try(var s=store.connection().createStatement()) {
                s.execute("INSERT INTO analysis_coverage(source_file,module,scope,capability,status,codes,code_counts) "
                        + "VALUES('*','*','*','DECLARATION','partial','[]','{}')");
            }
            var coverage=new DiffCoverage(store.connection(),metadata(),Set.of());
            assertTrue(coverage.reasons("MAIN","api","declarations").contains("INCOMPLETE_DECLARATION"));
            assertTrue(coverage.reasons("MAIN","api","relations").isEmpty());
        }
    }
}
