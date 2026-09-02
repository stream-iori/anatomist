package com.anatomist.config;

import com.anatomist.core.SourceScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void defaultsAreMainAndGenerated() {
        ProjectConfig config = new ProjectConfig();
        assertFalse(config.hasJavaVersion());
        assertEquals(List.of(SourceScope.MAIN, SourceScope.GENERATED), config.scanScopes());
        assertEquals(List.of("**"), config.scanIncludes());
        assertEquals(List.of(), config.scanExcludes());
    }

    @Test
    void parsesIndexScanAndExternalSections(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, """
                [index]
                java_version = 17
                spring_xml = true
                vm_classpath = false
                dataflow_mode = "scoped"
                dataflow_scopes = ["package:com.example.**"]

                [scan]
                scopes = ["MAIN", "TEST"]
                include = ["src/**"]
                exclude = ["**/generated/**", "**/*IT.java"]

                [external]
                exclude_patterns = ["java.lang.*", "com.google.**"]

                [extensions.lombok]
                mode = "ast"
                strict = true
                """);

        ProjectConfig config = new ProjectConfig();
        ConfigLoader.applyToml(config, file);

        assertEquals(17, config.javaVersion());
        assertTrue(config.springXml());
        assertFalse(config.vmClasspath());
        assertEquals(com.anatomist.framework.lombok.LombokMode.AST, config.lombokMode());
        assertTrue(config.lombokStrict());
        assertEquals(List.of(SourceScope.MAIN, SourceScope.TEST), config.scanScopes());
        assertEquals(List.of("src/**"), config.scanIncludes());
        assertEquals(List.of("**/generated/**", "**/*IT.java"), config.scanExcludes());
        assertEquals(List.of("java.lang.*", "com.google.**"), config.externalExcludePatterns());
    }

    @Test
    void projectConfigReplacesUserConfigInsteadOfMerging(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project/.anatomist")).getParent();
        Path home = Files.createDirectories(tmp.resolve("home/.anatomist")).getParent();
        Files.writeString(home.resolve(".anatomist/config.toml"), """
                [index]
                java_version = 21
                spring_xml = true
                [scan]
                scopes = ["MAIN", "TEST"]
                """);
        Files.writeString(project.resolve(".anatomist/config.toml"), """
                [index]
                java_version = 17
                """);

        LoadedConfig loaded = ConfigLoader.loadResolved(project, home);

        assertEquals(LoadedConfig.Source.PROJECT, loaded.source());
        assertEquals(17, loaded.config().javaVersion());
        assertFalse(loaded.config().springXml(), "missing project keys use built-in defaults");
        assertEquals(List.of(SourceScope.MAIN, SourceScope.GENERATED),
                loaded.config().scanScopes(), "user scan config must not leak into project config");
    }

    @Test
    void fallsBackFromProjectToUserThenDefaults(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path home = Files.createDirectories(tmp.resolve("home/.anatomist")).getParent();
        Files.writeString(home.resolve(".anatomist/config.toml"), """
                [scan]
                scopes = ["TEST"]
                """);

        LoadedConfig user = ConfigLoader.loadResolved(project, home);
        assertEquals(LoadedConfig.Source.USER, user.source());
        assertEquals(List.of(SourceScope.TEST), user.config().scanScopes());

        Files.delete(home.resolve(".anatomist/config.toml"));
        LoadedConfig defaults = ConfigLoader.loadResolved(project, home);
        assertEquals(LoadedConfig.Source.DEFAULT, defaults.source());
        assertNull(defaults.path());
    }

    @Test
    void parsesExplicitSourceRoots(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("config.toml");
        Files.writeString(file, """
                [scan]
                source_roots = ["app@MAIN=app/src/main/java", "it@TEST=it/src/test/java"]
                """);
        ProjectConfig config = new ProjectConfig();
        ConfigLoader.applyToml(config, file);
        assertEquals(List.of("app@MAIN=app/src/main/java", "it@TEST=it/src/test/java"),
                config.sourceRootSpecs());
    }

    @Test
    void rejectsLegacyUnknownMalformedAndConflictingConfig(@TempDir Path tmp) throws Exception {
        assertConfigError(tmp, "[index]\ninclude_tests = true\n", "removed key index.include_tests");
        assertConfigError(tmp, "[index]\nexclude = [\"target\"]\n", "removed key index.exclude");
        assertConfigError(tmp, "[scan]\nunknown = true\n", "unknown key scan.unknown");
        assertConfigError(tmp, "[index]\nspring_xml = yes\n", "expected true or false");
        assertConfigError(tmp, "[extensions.lombok]\nmode = \"bytecode\"\n", "off or ast");
        assertConfigError(tmp, "[extensions.lombok]\nunknown = true\n",
                "unknown key extensions.lombok.unknown");
        assertConfigError(tmp, "[scan]\ninclude = [\"../outside/**\"]\n", "cannot contain '..'");
        assertConfigError(tmp, "[scan]\nscopes = [\"MAIN\"]\nsource_roots = [\"x@MAIN=src\"]\n",
                "mutually exclusive");
    }

    private static void assertConfigError(Path tmp, String content, String message) throws Exception {
        Path project = Files.createDirectories(tmp.resolve(
                "project-" + Math.abs(content.hashCode()) + "/.anatomist")).getParent();
        Path file = project.resolve(".anatomist/config.toml");
        Files.writeString(file, content);
        ConfigException error = assertThrows(ConfigException.class,
                () -> ConfigLoader.loadResolved(project, tmp.resolve("unused-home")));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    @Test
    void externalPatternsStillWork() {
        ProjectConfig config = new ProjectConfig();
        assertTrue(config.isExternalExcluded("java.lang.String"));
        assertFalse(config.isExternalExcluded("com.example.Service"));
        config.addExternalExcludePatterns(List.of("org.apache.**"));
        assertTrue(config.isExternalExcluded("org.apache.commons.Foo"));
    }
}
