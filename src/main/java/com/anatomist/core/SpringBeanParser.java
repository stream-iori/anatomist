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
 * each {@code <bean>}'s identity, construction metadata, and config tree:
 * property/constructor-arg/map/list/entry/ref/value/null/idref.
 *
 * <p>Abstract, parent-based, factory-created, and nested beans are retained as
 * configuration facts even when no concrete class is declared. The root element must be a
 * Spring {@code <beans>} element (namespace- or local-name-matched) or the parse
 * yields an empty list — this lets us hand it arbitrary {@code .xml} files and
 * cheaply reject {@code pom.xml} / logback configs. Malformed XML never throws;
 * it simply returns whatever beans were seen before the failure (usually none).</p>
 *
 * <p>SAX is already a validated native-image dependency (see {@code ClasspathDetector}),
 * so this adds no reflection / reachability-metadata burden.</p>
 */
public final class SpringBeanParser {

    /** Lossless construction facts for one Spring XML {@code <bean>}. */
    public record ParsedBean(String name, String className, int line, int column,
                             int endLine, int endColumn, int ordinal,
                             boolean abstractBean, String parent, String factoryBean,
                             String factoryMethod, String initMethod, String destroyMethod,
                             String nestedIn,
                             List<XmlConfigNode> children) {
        public ParsedBean(String name, String className, int line, List<XmlConfigNode> children) {
            this(name, className, line, 1, line, 1, 0, false,
                    null, null, null, null, null, null, children);
        }
    }

    public static final class XmlConfigNode {
        public final String kind;
        public String name;
        public String key;
        public Integer index;
        public String bean;
        public String value;
        public String type;
        public final int line;
        public final int column;
        public int endLine;
        public int endColumn;
        public int ordinal;
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

        private final Deque<BeanState> beanStack = new ArrayDeque<>();
        private int beanOrdinal;

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
                int line = locator != null ? locator.getLineNumber() : 0;
                int column = locator != null ? locator.getColumnNumber() : 0;
                String cls = a.getValue("class");
                String id = firstNonNull(a.getValue("id"), a.getValue("name"));
                String name = id != null ? id : (cls != null ? cls : "$bean@L" + line + "C" + column);
                BeanState parentState = beanStack.peek();
                String nestedIn = parentState == null ? null : parentState.name;
                if (parentState != null) {
                    XmlConfigNode nested = new XmlConfigNode("bean", line, column);
                    nested.bean = name;
                    nested.value = cls;
                    parentState.addNode(nested);
                    parentState.openNodes.push(nested);
                }
                beanStack.push(new BeanState(name, cls, line, column, depth, beanOrdinal++,
                        "true".equalsIgnoreCase(a.getValue("abstract")), a.getValue("parent"),
                        a.getValue("factory-bean"), a.getValue("factory-method"),
                        a.getValue("init-method"), a.getValue("destroy-method"), nestedIn));
                return;
            }
            BeanState state = beanStack.peek();
            if (state == null || depth <= state.depth) return;
            XmlConfigNode node = nodeFor(tag, a, state);
            if (node == null) return;
            state.addNode(node);
            state.openNodes.push(node);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (!rootIsBeans) return;
            String tag = local(localName, qName);
            BeanState state = beanStack.peek();
            if ("bean".equals(tag) && state != null && depth == state.depth) {
                beanStack.pop();
                int endLine = locator != null ? locator.getLineNumber() : state.line;
                int endColumn = locator != null ? locator.getColumnNumber() : state.column;
                beans.add(new ParsedBean(state.name, state.className, state.line, state.column,
                        endLine, endColumn, state.ordinal, state.abstractBean, state.parent,
                        state.factoryBean, state.factoryMethod, state.initMethod,
                        state.destroyMethod, state.nestedIn,
                        List.copyOf(state.children)));
                BeanState parent = beanStack.peek();
                if (parent != null && !parent.openNodes.isEmpty()
                        && "bean".equals(parent.openNodes.peek().kind)) {
                    XmlConfigNode nested = parent.openNodes.pop();
                    nested.endLine = endLine;
                    nested.endColumn = endColumn;
                }
                depth--;
                return;
            }
            if (state != null && !state.openNodes.isEmpty()
                    && state.openNodes.peek().kind.equals(kindOf(tag))) {
                XmlConfigNode n = state.openNodes.pop();
                if ("value".equals(n.kind)) n.finishTextValue();
                n.endLine = locator != null ? locator.getLineNumber() : n.line;
                n.endColumn = locator != null ? locator.getColumnNumber() : n.column;
            }
            if (depth > 0) depth--;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            BeanState state = beanStack.peek();
            if (state != null && !state.openNodes.isEmpty()
                    && "value".equals(state.openNodes.peek().kind)) {
                state.openNodes.peek().appendText(ch, start, length);
            }
        }

        private XmlConfigNode nodeFor(String tag, Attributes a, BeanState state) {
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
                    n.index = idx != null ? parseInt(idx, state.constructorOrdinal) : state.constructorOrdinal;
                    state.constructorOrdinal++;
                    n.type = a.getValue("type");
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

        private static final class BeanState {
            final String name, className, parent, factoryBean, factoryMethod, initMethod,
                    destroyMethod, nestedIn;
            final int line, column, depth, ordinal;
            final boolean abstractBean;
            int constructorOrdinal;
            final List<XmlConfigNode> children = new ArrayList<>();
            final Deque<XmlConfigNode> openNodes = new ArrayDeque<>();

            BeanState(String name, String className, int line, int column, int depth,
                      int ordinal, boolean abstractBean, String parent, String factoryBean,
                      String factoryMethod, String initMethod, String destroyMethod,
                      String nestedIn) {
                this.name = name; this.className = className; this.line = line; this.column = column;
                this.depth = depth; this.ordinal = ordinal; this.abstractBean = abstractBean;
                this.parent = parent; this.factoryBean = factoryBean;
                this.factoryMethod = factoryMethod; this.initMethod = initMethod;
                this.destroyMethod = destroyMethod; this.nestedIn = nestedIn;
            }

            void addNode(XmlConfigNode node) {
                XmlConfigNode parentNode = openNodes.peek();
                List<XmlConfigNode> siblings = parentNode == null ? children : parentNode.children;
                node.ordinal = siblings.size();
                if (parentNode != null && "list".equals(parentNode.kind) && node.index == null) {
                    node.index = siblings.size();
                }
                siblings.add(node);
            }
        }

        /** Thrown to abort SAX early once we know the root is not {@code <beans>}. */
        private static final class StopParse extends RuntimeException {
            StopParse() { super(null, null, false, false); }
        }
    }
}
