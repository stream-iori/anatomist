package com.anatomist.core.nativeimage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalJdkCatalogResolverTest {

    @Test
    void buildsThenReusesCatalogForMatchingJdk(@TempDir Path tmp) throws Exception {
        Path home = Path.of(System.getProperty("java.home"));
        int release = JdkTypeCatalogBuilder.releaseOf(home);
        JdkTypeCatalog first = LocalJdkCatalogResolver.resolve(home, release, tmp);
        assertNotNull(first.find("java.lang.String"));
        long cached;
        try (var files = java.nio.file.Files.list(tmp)) {
            cached = files.filter(p -> p.getFileName().toString().endsWith(".bin")).count();
        }
        assertEquals(1, cached);
        JdkTypeCatalog second = LocalJdkCatalogResolver.resolve(home, release, tmp);
        assertEquals(first.size(), second.size());
    }

    @Test
    void rejectsConfiguredJdkThatDoesNotMatchTarget(@TempDir Path tmp) {
        Path home = Path.of(System.getProperty("java.home"));
        int release = JdkTypeCatalogBuilder.releaseOf(home);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> LocalJdkCatalogResolver.resolve(home, release + 1, tmp));
        assertTrue(error.getMessage().startsWith("JDK_HOME_RELEASE_MISMATCH"));
    }
}
