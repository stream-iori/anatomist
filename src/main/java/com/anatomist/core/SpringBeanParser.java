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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Deque;

/**
 * Pure SAX reader for Spring bean XML ({@code <beans>} config). Extracts each
 * concrete {@code <bean>}'s name + class and its P0/P1 config tree:
 * property/constructor-arg/map/list/entry/ref/value/null/idref.
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

    /** A parsed {@code <bean>}: its name, concrete class FQN, and config children. */
    public record ParsedBean(String name, String className, int line, List<XmlConfigNode> children) {}

    public static final class XmlConfigNode {
        public final String kind;
        public String name;
        public String key;
        public Integer index;
        public String bean;
        public String value;
        public final int line;
        public final int column;
        public final List<XmlConfigNode> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();

        XmlConfigNode(String kind, int line, int column) {
            this.kind = kind;
            this.line = line;
            this.column = column;
        }

        void appendText(char[] ch, int start, int length) {
            text.append(ch, start, length);
        }

        void finishTextValue() {
            if (value == null) {
                String rendered = text.toString().trim();
                if (!rendered.isEmpty()) value = rendered;
            }
        }
    }

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
        private int depth;

        // State for the bean currently being assembled.
        private String curName;
        private String curClass;
        private int curLine;
        private int curBeanDepth;
        private int constructorOrdinal;
        private List<XmlConfigNode> curChildren;
        private final Deque<XmlConfigNode> stack = new ArrayDeque<>();

        @Override public void setDocumentLocator(org.xml.sax.Locator l) { this.locator = l; }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes a) {
            if (!seenRoot) {
                seenRoot = true;
                depth = 1;
                rootIsBeans = isBeansElement(uri, localName, qName);
                if (!rootIsBeans) {
                    throw new StopParse();
                }
                return;
            }
            if (!rootIsBeans) return;
            depth++;
            String tag = local(localName, qName);
            if ("bean".equals(tag)) {
                if (curClass == null) {
                    curClass = a.getValue("class");
                    if (curClass == null) { curName = null; curChildren = null; return; }
                    String id = a.getValue("id");
                    String name = a.getValue("name");
                    curName = id != null ? id : (name != null ? name : curClass);
                    curLine = locator != null ? locator.getLineNumber() : 0;
                    curBeanDepth = depth;
                    constructorOrdinal = 0;
                    curChildren = new ArrayList<>();
                    stack.clear();
                }
                return;
            }
            if (curClass == null || depth <= curBeanDepth) return;
            XmlConfigNode node = nodeFor(tag, a);
            if (node == null) return;
            addNode(node);
            stack.push(node);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!rootIsBeans) return;
            String tag = local(localName, qName);
            if ("bean".equals(tag) && curClass != null && depth == curBeanDepth) {
                beans.add(new ParsedBean(curName, curClass, curLine, List.copyOf(curChildren)));
                curName = null; curClass = null;
                curChildren = null; curBeanDepth = 0; stack.clear();
                depth--;
                return;
            }
            if (!stack.isEmpty() && stack.peek().kind.equals(kindOf(tag))) {
                XmlConfigNode n = stack.pop();
                if ("value".equals(n.kind)) n.finishTextValue();
            }
            if (depth > 0) depth--;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (!stack.isEmpty() && "value".equals(stack.peek().kind)) {
                stack.peek().appendText(ch, start, length);
            }
        }

        private XmlConfigNode nodeFor(String tag, Attributes a) {
            int line = locator != null ? locator.getLineNumber() : 0;
            int col = locator != null ? locator.getColumnNumber() : 0;
            XmlConfigNode n = switch (tag) {
                case "property" -> new XmlConfigNode("property", line, col);
                case "constructor-arg" -> new XmlConfigNode("constructor-arg", line, col);
                case "map" -> new XmlConfigNode("map", line, col);
                case "list" -> new XmlConfigNode("list", line, col);
                case "entry" -> new XmlConfigNode("entry", line, col);
                case "ref" -> new XmlConfigNode("ref", line, col);
                case "idref" -> new XmlConfigNode("idref", line, col);
                case "value" -> new XmlConfigNode("value", line, col);
                case "null" -> new XmlConfigNode("null", line, col);
                default -> null;
            };
            if (n == null) return null;
            switch (n.kind) {
                case "property" -> {
                    n.name = a.getValue("name");
                    addAttrRefOrValue(n, a);
                }
                case "constructor-arg" -> {
                    String idx = a.getValue("index");
                    n.index = idx != null ? parseInt(idx, constructorOrdinal) : constructorOrdinal;
                    constructorOrdinal++;
                    addAttrRefOrValue(n, a);
                }
                case "entry" -> {
                    n.key = firstNonNull(a.getValue("key"), a.getValue("key-ref"));
                    addAttrRefOrValue(n, a);
                }
                case "ref" -> n.bean = refName(a);
                case "idref" -> n.bean = refName(a);
                case "value" -> n.value = a.getValue("value");
                default -> { }
            }
            return n;
        }

        private void addAttrRefOrValue(XmlConfigNode parent, Attributes a) {
            String ref = firstNonNull(a.getValue("ref"), a.getValue("value-ref"));
            if (ref != null) {
                XmlConfigNode child = new XmlConfigNode("ref", parent.line, parent.column);
                child.bean = ref;
                parent.children.add(child);
            }
            String value = a.getValue("value");
            if (value != null) {
                XmlConfigNode child = new XmlConfigNode("value", parent.line, parent.column);
                child.value = value;
                parent.children.add(child);
            }
        }

        private void addNode(XmlConfigNode node) {
            XmlConfigNode parent = stack.peek();
            if (parent == null) {
                curChildren.add(node);
            } else {
                if ("list".equals(parent.kind) && node.index == null) node.index = parent.children.size();
                parent.children.add(node);
            }
        }

        private static String kindOf(String tag) {
            return switch (tag) {
                case "constructor-arg" -> "constructor-arg";
                case "property", "map", "list", "entry", "ref", "idref", "value", "null" -> tag;
                default -> "";
            };
        }

        private static String refName(Attributes a) {
            return firstNonNull(a.getValue("bean"), a.getValue("local"), a.getValue("parent"));
        }

        private static String firstNonNull(String... values) {
            for (String v : values) if (v != null) return v;
            return null;
        }

        private static int parseInt(String value, int fallback) {
            try { return Integer.parseInt(value); }
            catch (RuntimeException e) { return fallback; }
        }

        /** Thrown to abort SAX early once we know the root is not {@code <beans>}. */
        private static final class StopParse extends RuntimeException {
            StopParse() { super(null, null, false, false); }
        }
    }
}
