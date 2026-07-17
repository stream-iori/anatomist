package com.anatomist.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void javaVersionIsAbsentUntilExplicitlyConfigured() {
        ProjectConfig config = new ProjectConfig();
        assertFalse(config.hasJavaVersion());
        assertNull(config.javaVersion());
    }

    @Test
    void defaultExcludePatternsBlockJavaLang() {
        ProjectConfig config = new ProjectConfig();
        assertTrue(config.isExternalExcluded("java.lang.String"));
        assertTrue(config.isExternalExcluded("java.lang.Object"));
        assertTrue(config.isExternalExcluded("java.io.InputStream"));
        assertTrue(config.isExternalExcluded("sun.misc.Unsafe"));
        assertFalse(config.isExternalExcluded("com.example.MyService"));
        assertFalse(config.isExternalExcluded("org.springframework.stereotype.Service"));
    }

    @Test
    void matchesPatternExact() {
        assertTrue(ProjectConfig.matchesPattern("com.example.Foo", "com.example.Foo"));
        assertFalse(ProjectConfig.matchesPattern("com.example.Foo", "com.example.FooBar"));
    }

    @Test
    void matchesPatternSingleWildcard() {
        assertTrue(ProjectConfig.matchesPattern("java.lang.*", "java.lang.String"));
        assertTrue(ProjectConfig.matchesPattern("java.lang.*", "java.lang.reflect.Method"));
        assertFalse(ProjectConfig.matchesPattern("java.lang.*", "java.util.List"));
    }

    @Test
    void matchesPatternDoubleWildcard() {
        assertTrue(ProjectConfig.matchesPattern("org.apache.**", "org.apache.commons.lang.StringUtils"));
        assertFalse(ProjectConfig.matchesPattern("org.apache.**", "org.springframework.Foo"));
    }

    @Test
    void addExternalExcludePatternsNoDuplicates() {
        ProjectConfig config = new ProjectConfig();
        int before = config.externalExcludePatterns().size();
        config.addExternalExcludePatterns(List.of("java.lang.*", "org.apache.**"));
        assertEquals(before + 1, config.externalExcludePatterns().size());
        assertTrue(config.isExternalExcluded("org.apache.commons.Foo"));
    }

    @Test
    void parseTomlBasic(@TempDir Path tmp) throws IOException {
        Path toml = tmp.resolve("config.toml");
        Files.writeString(toml, """
                [index]
                java_version = 17
                include_tests = true
                spring_xml = true
                dataflow_mode = "scoped"
                dataflow_scopes = ["package:com.example.**", "source:service/**"]
                exclude = ["generated", "test-output"]

                [external]
                exclude_patterns = ["java.lang.*", "com.google.**"]
                """);

        ProjectConfig config = new ProjectConfig();
        ConfigLoader.applyToml(config, toml);

        assertEquals(17, config.javaVersion());
        assertTrue(config.includeTests());
        assertTrue(config.springXml());
        assertEquals("scoped", config.dataflowMode());
        assertEquals(List.of("package:com.example.**", "source:service/**"),
                config.dataflowScopes());
        assertEquals(List.of("generated", "test-output"), config.exclude());
        assertEquals(List.of("java.lang.*", "com.google.**"), config.externalExcludePatterns());
    }

    @Test
    void parseTomlIgnoresComments(@TempDir Path tmp) throws IOException {
        Path toml = tmp.resolve("config.toml");
        Files.writeString(toml, """
                # This is a comment
                [external]
                # Another comment
                exclude_patterns = ["org.slf4j.*"]
                """);

        ProjectConfig config = new ProjectConfig();
        ConfigLoader.applyToml(config, toml);
        assertEquals(List.of("org.slf4j.*"), config.externalExcludePatterns());
    }

    @Test
    void loadMergesProjectLocalOverUserWide(@TempDir Path tmp) throws IOException {
        Path projectRoot = tmp.resolve("project");
        Files.createDirectories(projectRoot.resolve(".anatomist"));
        Files.writeString(projectRoot.resolve(".anatomist/config.toml"), """
                [external]
                exclude_patterns = ["com.custom.*"]
                """);

        ProjectConfig config = ConfigLoader.load(projectRoot);
        assertEquals(List.of("com.custom.*"), config.externalExcludePatterns());
    }

    @Test
    void parseStringArraySingleValue() {
        List<String> result = ConfigLoader.parseStringArray("\"hello\"");
        assertEquals(List.of("hello"), result);
    }

    @Test
    void parseStringArrayMultipleValues() {
        List<String> result = ConfigLoader.parseStringArray("[\"a\", \"b\", \"c\"]");
        assertEquals(List.of("a", "b", "c"), result);
    }

    @Test
    void unquoteStripsDoubleQuotes() {
        assertEquals("hello", ConfigLoader.unquote("\"hello\""));
        assertEquals("noquotes", ConfigLoader.unquote("noquotes"));
    }
}
