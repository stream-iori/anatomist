package com.anatomist.core;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the pure SAX parsing of Spring bean XML: bean name/class extraction and
 * the ref edges harvested from {@code property/@ref}, {@code constructor-arg/@ref}
 * and the {@code ref} attribute form. Root-element sniffing rejects non-Spring XML;
 * malformed input yields an empty list rather than throwing.
 */
class SpringBeanParserTest {

    private static List<SpringBeanParser.ParsedBean> parse(String xml) {
        return new SpringBeanParser().parse(new StringReader(xml));
    }

    @Test
    void parsesBeanNameAndClass() {
        List<SpringBeanParser.ParsedBean> beans = parse(
                "<beans xmlns='http://www.springframework.org/schema/beans'>"
                        + "<bean id='orderSvc' class='com.example.OrderService'/>"
                        + "</beans>");
        assertEquals(1, beans.size());
        SpringBeanParser.ParsedBean b = beans.get(0);
        assertEquals("orderSvc", b.name());
        assertEquals("com.example.OrderService", b.className());
        assertTrue(b.children().isEmpty());
    }

    @Test
    void beanNameFallsBackToNameAttrThenClass() {
        List<SpringBeanParser.ParsedBean> beans = parse(
                "<beans xmlns='http://www.springframework.org/schema/beans'>"
                        + "<bean name='byName' class='com.example.A'/>"
                        + "<bean class='com.example.B'/>"
                        + "</beans>");
        assertEquals("byName", beans.get(0).name());
        // No id/name → fall back to class FQN as the bean name.
        assertEquals("com.example.B", beans.get(1).name());
    }

    @Test
    void parsesPropertyAndConstructorArgRefs() {
        List<SpringBeanParser.ParsedBean> beans = parse(
                "<beans xmlns='http://www.springframework.org/schema/beans'>"
                        + "<bean id='svc' class='com.example.OrderService'>"
                        + "  <property name='repo' ref='orderRepo'/>"
                        + "  <constructor-arg ref='clock'/>"
                        + "</bean>"
                        + "<bean id='orderRepo' class='com.example.RepoImpl'/>"
                        + "<bean id='clock' class='com.example.Clock'/>"
                        + "</beans>");
        SpringBeanParser.ParsedBean svc = beans.stream()
                .filter(b -> b.name().equals("svc")).findFirst().orElseThrow();
        assertEquals(2, svc.children().size());
        assertEquals("property", svc.children().get(0).kind);
        assertEquals("repo", svc.children().get(0).name);
        assertEquals("ref", svc.children().get(0).children.get(0).kind);
        assertEquals("orderRepo", svc.children().get(0).children.get(0).bean);
        assertEquals("constructor-arg", svc.children().get(1).kind);
        assertEquals(0, svc.children().get(1).index);
        assertEquals("clock", svc.children().get(1).children.get(0).bean);
    }

    @Test
    void parsesNestedMapListRefsWithKeyAndOrder() {
        List<SpringBeanParser.ParsedBean> beans = parse(
                "<beans xmlns='http://www.springframework.org/schema/beans'>"
                        + "<bean id='a' class='com.example.A'>"
                        + "  <property name='filters'>"
                        + "    <map><entry key='DEFAULT'><list>"
                        + "      <ref bean='first'/><ref bean='second'/>"
                        + "    </list></entry></map>"
                        + "  </property>"
                        + "</bean>"
                        + "</beans>");
        SpringBeanParser.XmlConfigNode property = beans.get(0).children().get(0);
        SpringBeanParser.XmlConfigNode map = property.children.get(0);
        SpringBeanParser.XmlConfigNode entry = map.children.get(0);
        SpringBeanParser.XmlConfigNode list = entry.children.get(0);
        assertEquals("filters", property.name);
        assertEquals("map", map.kind);
        assertEquals("DEFAULT", entry.key);
        assertEquals("list", list.kind);
        assertEquals("first", list.children.get(0).bean);
        assertEquals(0, list.children.get(0).index);
        assertEquals("second", list.children.get(1).bean);
        assertEquals(1, list.children.get(1).index);
    }

    @Test
    void parsesValueNullAndIdref() {
        List<SpringBeanParser.ParsedBean> beans = parse(
                "<beans xmlns='http://www.springframework.org/schema/beans'>"
                        + "<bean id='a' class='com.example.A'>"
                        + "  <property name='name'><value>alpha</value></property>"
                        + "  <property name='empty'><null/></property>"
                        + "  <constructor-arg><idref bean='other'/></constructor-arg>"
                        + "</bean>"
                        + "</beans>");
        assertEquals("alpha", beans.get(0).children().get(0).children.get(0).value);
        assertEquals("null", beans.get(0).children().get(1).children.get(0).kind);
        assertEquals("idref", beans.get(0).children().get(2).children.get(0).kind);
        assertEquals("other", beans.get(0).children().get(2).children.get(0).bean);
    }

    @Test
    void nonBeansRootYieldsEmpty() {
        // A pom.xml / arbitrary XML must be ignored by the sniff.
        assertTrue(parse("<project><modelVersion>4.0.0</modelVersion></project>").isEmpty());
        assertTrue(parse("<configuration><logger name='x'/></configuration>").isEmpty());
    }

    @Test
    void malformedXmlYieldsEmptyAndDoesNotThrow() {
        assertTrue(parse("<beans><bean id='x' class='C'></beans").isEmpty());
        assertTrue(parse("not xml at all").isEmpty());
    }

    @Test
    void beanWithoutClassIsSkipped() {
        // Abstract/parent beans or factory-bean refs without a concrete class
        // carry no resolvable type — drop them.
        List<SpringBeanParser.ParsedBean> beans = parse(
                "<beans xmlns='http://www.springframework.org/schema/beans'>"
                        + "<bean id='tmpl' abstract='true'/>"
                        + "<bean id='real' class='com.example.Real'/>"
                        + "</beans>");
        assertEquals(1, beans.size());
        assertEquals("real", beans.get(0).name());
    }
}
