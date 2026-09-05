package com.anatomist.cli;

import com.anatomist.test.CliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class AnnotationSpringContractIT {

    @Test
    void expandsMetaAnnotationsWithoutSimpleNameSpringFalsePositive(@TempDir Path tmp)
            throws Exception {
        Path project = project(tmp);
        write(project, "src/main/java/org/springframework/stereotype/Component.java", """
                package org.springframework.stereotype;
                public @interface Component {}
                """);
        write(project, "src/main/java/p/Composed.java", """
                package p;
                import org.springframework.stereotype.Component;
                @Component public @interface Composed {}
                """);
        write(project, "src/main/java/p/Target.java", """
                package p;
                @Composed public class Target {}
                """);
        write(project, "src/main/java/p/Service.java", """
                package p;
                public @interface Service {}
                """);
        write(project, "src/main/java/p/Fake.java", """
                package p;
                @Service public class Fake {}
                """);
        Path db = tmp.resolve("annotations.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--output", db.toString());

        CliTestSupport.RunResult annotations = run("pipeline", "--index", db.toString(), "--",
                "resolve", "p.Target", "--kind", "type", "--exact", "--unique",
                "--then", "annotations", "--include-meta");
        assertEquals(0, annotations.exitCode(), annotations.stderr());
        assertTrue(annotations.stdout().contains("org.springframework.stereotype.Component"),
                annotations.stdout());
        assertTrue(annotations.stdout().contains("\"direct\":false"), annotations.stdout());

        CliTestSupport.RunResult search = run("search", "org.springframework.stereotype.Component",
                "--by-annotation", "--include-meta", "--kind", "type",
                "--index", db.toString());
        assertEquals(0, search.exitCode(), search.stderr());
        assertTrue(search.stdout().contains("p.Target"), search.stdout());

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertTrue(text(statement, "SELECT source_file FROM annotation_meta_relations "
                    + "WHERE annotation_fqn='p.Composed' "
                    + "AND meta_annotation_fqn='org.springframework.stereotype.Component'")
                    .endsWith("Composed.java"));
            assertEquals(1, scalar(statement, "SELECT count(*) FROM nodes WHERE kind='BEAN' "
                    + "AND source_file LIKE '%Target.java'"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM nodes WHERE kind='BEAN' "
                    + "AND source_file LIKE '%Fake.java'"));
        }

        write(project, "src/main/java/p/Composed.java", """
                package p;
                public @interface Composed {}
                """);
        CliTestSupport.assertIndexOk(project, "--incremental", "--no-classpath",
                "--output", db.toString());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertEquals(0, scalar(statement, "SELECT count(*) FROM annotation_meta_relations "
                    + "WHERE annotation_fqn='p.Composed' "
                    + "AND meta_annotation_fqn='org.springframework.stereotype.Component'"));
            assertEquals(0, scalar(statement, "SELECT count(*) FROM nodes WHERE kind='BEAN' "
                    + "AND source_file LIKE '%Target.java'"));
        }
        CliTestSupport.RunResult afterRemoval = run("search",
                "org.springframework.stereotype.Component", "--by-annotation", "--include-meta",
                "--kind", "type", "--index", db.toString());
        assertEquals(0, afterRemoval.exitCode(), afterRemoval.stderr());
        assertFalse(afterRemoval.stdout().contains("p.Target"), afterRemoval.stdout());
    }

    @Test
    void bindsXmlFactorySetterConstructorAndLifecycleWithAmbiguity(@TempDir Path tmp)
            throws Exception {
        Path project = project(tmp);
        write(project, "src/main/java/p/Product.java", """
                package p;
                public class Product {
                    public Product() {}
                    public Product(String value) {}
                    public void setName(String value) {}
                    public void init() {}
                    public void shutdown() {}
                }
                """);
        write(project, "src/main/java/p/Factory.java", """
                package p;
                public class Factory {
                    public static Product create() { return new Product(); }
                    public static Product create(String value) { return new Product(value); }
                    public static Product create(Integer value) { return new Product(); }
                }
                """);
        write(project, "src/main/resources/beans.xml", """
                <beans xmlns="http://www.springframework.org/schema/beans">
                  <bean id="made" class="p.Factory" factory-method="create"/>
                  <bean id="ambiguous" class="p.Factory" factory-method="create">
                    <constructor-arg value="x"/>
                  </bean>
                  <bean id="plain" class="p.Product" init-method="init" destroy-method="shutdown">
                    <property name="name" value="x"/>
                  </bean>
                </beans>
                """);
        Path db = tmp.resolve("spring.db");
        CliTestSupport.assertIndexOk(project, "--no-classpath", "--spring-xml",
                "--output", db.toString());

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            assertEquals(1, scalar(statement, "SELECT count(*) FROM edges e JOIN nodes t ON t.id=e.target_id "
                    + "WHERE e.relation='BINDS_TO' AND t.symbol_id='p.Factory#create()'"));
            assertEquals(2, scalar(statement, "SELECT count(*) FROM edges e JOIN nodes s ON s.id=e.source_id "
                    + "WHERE e.relation='BINDS_TO' AND s.symbol_id LIKE 'bean:ambiguous@%/callable-ref:factory' "
                    + "AND e.confidence='AMBIGUOUS'"));
            assertTrue(text(statement, "SELECT metadata FROM nodes WHERE symbol_id LIKE "
                    + "'bean:ambiguous@%/callable-ref:factory'").contains("constructor-arg"));
            for (String method : new String[]{"p.Product#setName(java.lang.String)",
                    "p.Product#init()", "p.Product#shutdown()", "p.Product#Product()"}) {
                assertEquals(1, scalar(statement, "SELECT count(*) FROM edges e JOIN nodes t ON t.id=e.target_id "
                        + "WHERE e.relation='BINDS_TO' AND t.symbol_id='" + method + "'"), method);
            }
            String beanId = text(statement, "SELECT id FROM nodes WHERE kind='BEAN' AND label='plain'");
            try (com.anatomist.query.QueryService query = new com.anatomist.query.QueryService(db)) {
                assertEquals(4, query.semanticBindings(beanId, "outgoing", "member", 50).size());
            }
        }

        CliTestSupport.RunResult bindings = run("pipeline", "--index", db.toString(), "--",
                "search", "--name", "plain", "--kind", "component",
                "--then", "resolve", "--unique",
                "--then", "bindings", "--semantic", "member");
        assertEquals(0, bindings.exitCode(), bindings.stderr());
        assertTrue(bindings.stdout().contains("\"role\":\"setter\""),
                bindings.stdout());
        assertTrue(bindings.stdout().contains("\"symbol_ref\""), bindings.stdout());
    }

    private static Path project(Path tmp) throws Exception {
        Path project = tmp.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>p</groupId>
                <artifactId>contract</artifactId><version>1</version></project>
                """, StandardCharsets.UTF_8);
        return project;
    }

    private static void write(Path project, String relative, String content) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static CliTestSupport.RunResult run(String... args) throws Exception {
        return CliTestSupport.capture(() -> new CommandLine(new AnatomistCli()).execute(args));
    }

    private static int scalar(java.sql.Statement statement, String sql) throws Exception {
        try (var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static String text(java.sql.Statement statement, String sql) throws Exception {
        try (var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
