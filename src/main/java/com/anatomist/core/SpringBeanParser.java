package com.anatomist.core;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure SAX reader for Spring bean XML ({@code <beans>} config). Extracts each
 * {@code <bean>}'s name + class and the bean names it references
 * ({@code property/@ref}, {@code constructor-arg/@ref}, nested {@code <ref bean=>}).
 *
 * <p>Deliberately conservative: only concrete {@code class}-bearing beans are
 * emitted; abstract/parent/factory beans are skipped. The root element must be a
 * Spring {@code <beans>} element (namespace- or local-name-matched) or the parse
 * yields an empty list — this lets us hand it arbitrary {@code .xml} files and
 * cheaply reject {@code pom.xml} / logback configs. Malformed XML never throws;
 * it simply returns whatever beans were seen before the failure (usually none).</p>
 *
 * <p>SAX is already a validated native-image dependency (see {@code ClasspathDetector}),
 * so this adds no reflection / reachability-metadata burden.</p>
 */
public final class SpringBeanParser {

    /** A parsed {@code <bean>}: its name, concrete class FQN, and referenced bean names. */
    public record ParsedBean(String name, String className, int line, List<String> refs) {}

    private static final String SPRING_BEANS_NS = "http://www.springframework.org/schema/beans";

    /** Sniff whether a file is a Spring {@code <beans>} config (root-element match only). */
    public static boolean isSpringBeansFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) return false;
        String n = file.getFileName().toString();
        if (!n.endsWith(".xml") || "pom.xml".equals(n)) return false;
        return rootIsBeans(file);
    }

    public List<ParsedBean> parse(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            return parse(r);
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<ParsedBean> parse(Reader reader) {
        Handler h = new Handler();
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            SAXParser parser = spf.newSAXParser();
            parser.parse(new InputSource(reader), h);
        } catch (Exception e) {
            // Malformed / non-beans XML: return whatever was collected (usually none).
            if (!h.rootIsBeans) return List.of();
        }
        return h.rootIsBeans ? h.beans : List.of();
    }

    private static boolean rootIsBeans(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            Handler h = new Handler();
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(true);
            spf.newSAXParser().parse(new InputSource(r), h);
            return h.rootIsBeans;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBeansElement(String uri, String localName, String qName) {
        if (SPRING_BEANS_NS.equals(uri)) return "beans".equals(localName);
        // Namespace-unaware fallback: match by (possibly prefixed) tag name.
        String tag = localName != null && !localName.isEmpty() ? localName : strip(qName);
        return "beans".equals(tag);
    }

    private static String local(String localName, String qName) {
        return localName != null && !localName.isEmpty() ? localName : strip(qName);
    }

    private static String strip(String qName) {
        if (qName == null) return "";
        int c = qName.indexOf(':');
        return c >= 0 ? qName.substring(c + 1) : qName;
    }

    private static final class Handler extends DefaultHandler {
        final List<ParsedBean> beans = new ArrayList<>();
        boolean rootIsBeans = false;
        private boolean seenRoot = false;
        private org.xml.sax.Locator locator;

        // State for the bean currently being assembled.
        private String curName;
        private String curClass;
        private int curLine;
        private List<String> curRefs;

        @Override public void setDocumentLocator(org.xml.sax.Locator l) { this.locator = l; }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes a) {
            if (!seenRoot) {
                seenRoot = true;
                rootIsBeans = isBeansElement(uri, localName, qName);
                if (!rootIsBeans) {
                    throw new StopParse();
                }
                return;
            }
            if (!rootIsBeans) return;
            String tag = local(localName, qName);
            switch (tag) {
                case "bean" -> {
                    curClass = a.getValue("class");
                    if (curClass == null) { curName = null; curRefs = null; return; }
                    String id = a.getValue("id");
                    String name = a.getValue("name");
                    curName = id != null ? id : (name != null ? name : curClass);
                    curLine = locator != null ? locator.getLineNumber() : 0;
                    curRefs = new ArrayList<>();
                }
                case "property", "constructor-arg" -> {
                    if (curRefs == null) return;
                    String ref = a.getValue("ref");
                    if (ref != null) curRefs.add(ref);
                }
                case "ref" -> {
                    if (curRefs == null) return;
                    String bean = a.getValue("bean");
                    if (bean == null) bean = a.getValue("parent");
                    if (bean != null) curRefs.add(bean);
                }
                default -> { /* ignore */ }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!rootIsBeans) return;
            if ("bean".equals(local(localName, qName)) && curClass != null) {
                beans.add(new ParsedBean(curName, curClass, curLine, List.copyOf(curRefs)));
                curName = null; curClass = null; curRefs = null;
            }
        }

        /** Thrown to abort SAX early once we know the root is not {@code <beans>}. */
        private static final class StopParse extends RuntimeException {
            StopParse() { super(null, null, false, false); }
        }
    }
}
