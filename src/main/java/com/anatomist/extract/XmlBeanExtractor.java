package com.anatomist.extract;

import com.anatomist.core.SpringBeanParser.ParsedBean;
import com.anatomist.core.SpringBeanParser.XmlConfigNode;
import com.anatomist.json.Json;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps parsed Spring beans into graph nodes/edges. Unlike the JavaParser-driven
 * {@link Extractor}s this runs as a separate pass (no {@code CompilationUnit}):
 * it joins each bean's {@code class} FQN and {@code ref} bean names against the
 * set of node ids already known (this batch's Java CLASS nodes ∪ the DB), so
 * internal vs. external edges obey the {@code edges} CHECK constraint.
 *
 * <p>Emitted graph:</p>
 * <ul>
 *   <li>one {@code BEAN} node per concrete bean (id {@code bean:<name>@<relXml>});</li>
 *   <li>{@code DEFINED_BY}: BEAN → its class (internal if the class id is known,
 *       else external);</li>
 *   <li>P0/P1 XML config tree nodes for property/constructor-arg/map/list/entry/
 *       ref/value/null/idref.</li>
 *   <li>{@code XML_REFERS_TO}: XML_REF/XML_IDREF → referenced BEAN when known.</li>
 *   <li>{@code WIRES}: ownerClass → refClass when the ref can be resolved.</li>
 * </ul>
 */
public final class XmlBeanExtractor {

    public record BeanRefTarget(String beanId, String className) {}

    private final String scope;

    public XmlBeanExtractor() { this(GraphConstants.Scope.MAIN); }

    public XmlBeanExtractor(String scope) {
        this.scope = scope != null ? scope : GraphConstants.Scope.MAIN;
    }

    public void extract(List<ParsedBean> beans, Set<String> knownIds,
                        String sourceFile, ExtractionResult result) {
        extractWithResolvedBeans(beans, knownIds, Map.of(), sourceFile, result);
    }

    public void extract(List<ParsedBean> beans, Set<String> knownIds,
                        Map<String, String> existingBeanClass,
                        String sourceFile, ExtractionResult result) {
        Map<String, BeanRefTarget> existingBeans = new HashMap<>();
        for (Map.Entry<String, String> e : existingBeanClass.entrySet()) {
            existingBeans.put(e.getKey(), new BeanRefTarget(e.getKey(), e.getValue()));
        }
        extractWithResolvedBeans(beans, knownIds, existingBeans, sourceFile, result);
    }

    public void extractWithResolvedBeans(List<ParsedBean> beans, Set<String> knownIds,
                                         Map<String, BeanRefTarget> existingBeans,
                                         String sourceFile, ExtractionResult result) {
        // bean name → its declared class FQN, for resolving refs to classes.
        Map<String, String> beanClass = new HashMap<>();
        for (ParsedBean b : beans) beanClass.putIfAbsent(b.name(), b.className());

        for (ParsedBean b : beans) {
            String beanId = beanNodeId(b.name(), sourceFile);
            String cls = b.className();
            boolean classKnown = knownIds.contains(cls);

            Node n = new Node();
            n.id = beanId;
            n.label = b.name();
            n.kind = GraphConstants.Kind.BEAN;
            n.qualifiedName = b.name();
            n.sourceFile = sourceFile;
            n.sourceLocation = "L" + b.line();
            n.scope = scope;
            n.metadata = metadataJson(Map.of("className", cls, "source", "xml"));
            result.nodes.add(n);

            // DEFINED_BY: BEAN → class.
            Edge def = baseEdge(GraphConstants.Relation.DEFINED_BY, sourceFile, b.line());
            def.sourceId = beanId;
            if (classKnown) { def.targetId = cls; def.isExternal = false; }
            else { def.externalTargetFqn = cls; def.isExternal = true; def.resolution = GraphConstants.Resolution.XML; }
            result.edges.add(def);

            List<XmlConfigNode> refs = new ArrayList<>();
            emitConfigTree(beanId, beanId, b.children(), sourceFile, result, beanClass,
                    existingBeans, knownIds, refs);
            emitWires(cls, classKnown, refs, beanClass, existingBeans, knownIds,
                    sourceFile, b.line(), result);
        }
    }

    public static String beanNodeId(String beanName, String sourceFile) {
        return "bean:" + beanName + "@" + sourceFile;
    }

    private static Edge baseEdge(String relation, String sourceFile, int line) {
        Edge e = new Edge();
        e.relation = relation;
        e.confidence = GraphConstants.Confidence.EXTRACTED;
        e.sourceFile = sourceFile;
        e.sourceLocation = "L" + line;
        return e;
    }

    private void emitConfigTree(String beanId,
                                String parentId,
                                List<XmlConfigNode> children,
                                String sourceFile,
                                ExtractionResult result,
                                Map<String, String> xmlBeanClass,
                                Map<String, BeanRefTarget> existingBeans,
                                Set<String> knownIds,
                                List<XmlConfigNode> refs) {
        for (int i = 0; i < children.size(); i++) {
            XmlConfigNode child = children.get(i);
            String id = configNodeId(parentId, child, i);
            Node n = new Node();
            n.id = id;
            n.label = labelOf(child);
            n.kind = kindOf(child.kind);
            n.qualifiedName = id;
            n.sourceFile = sourceFile;
            n.sourceLocation = "L" + child.line;
            n.scope = scope;
            n.metadata = metadataJson(metadataOf(child));
            result.nodes.add(n);

            Edge contains = baseEdge(parentId.equals(beanId)
                    ? GraphConstants.Relation.CONFIGURES
                    : GraphConstants.Relation.XML_CONTAINS, sourceFile, child.line);
            contains.sourceId = parentId;
            contains.targetId = id;
            contains.isExternal = false;
            result.edges.add(contains);

            if (("ref".equals(child.kind) || "idref".equals(child.kind)) && child.bean != null) {
                refs.add(child);
                result.edges.add(refEdge(id, child, sourceFile, xmlBeanClass, existingBeans, knownIds));
            }
            emitConfigTree(beanId, id, child.children, sourceFile, result,
                    xmlBeanClass, existingBeans, knownIds, refs);
        }
    }

    private static void emitWires(String ownerClass,
                                  boolean ownerKnown,
                                  List<XmlConfigNode> refs,
                                  Map<String, String> xmlBeanClass,
                                  Map<String, BeanRefTarget> existingBeans,
                                  Set<String> knownIds,
                                  String sourceFile,
                                  int line,
                                  ExtractionResult result) {
        if (!ownerKnown) return;
        for (XmlConfigNode ref : refs) {
            String targetClass = xmlBeanClass.get(ref.bean);
            BeanRefTarget target = targetClass == null ? existingBeans.get("bean:" + ref.bean) : null;
            if (targetClass == null && target != null) targetClass = target.className();
            if (targetClass == null) continue;
            Edge w = baseEdge(GraphConstants.Relation.WIRES, sourceFile, line);
            w.sourceId = ownerClass;
            if (knownIds.contains(targetClass)) { w.targetId = targetClass; w.isExternal = false; }
            else { w.externalTargetFqn = targetClass; w.isExternal = true; w.resolution = GraphConstants.Resolution.XML; }
            result.edges.add(w);
        }
    }

    private static Edge refEdge(String sourceId,
                                XmlConfigNode ref,
                                String sourceFile,
                                Map<String, String> xmlBeanClass,
                                Map<String, BeanRefTarget> existingBeans,
                                Set<String> knownIds) {
        Edge e = baseEdge(GraphConstants.Relation.XML_REFERS_TO, sourceFile, ref.line);
        e.sourceId = sourceId;
        String xmlTarget = xmlBeanClass.containsKey(ref.bean) ? beanNodeId(ref.bean, sourceFile) : null;
        BeanRefTarget existingTarget = existingBeans.get("bean:" + ref.bean);
        if (xmlTarget != null) {
            e.targetId = xmlTarget;
            e.isExternal = false;
        } else if (existingTarget != null && knownIds.contains(existingTarget.beanId())) {
            e.targetId = existingTarget.beanId();
            e.isExternal = false;
        } else {
            e.externalTargetFqn = ref.bean;
            e.isExternal = true;
            e.resolution = GraphConstants.Resolution.XML;
        }
        return e;
    }

    private static String configNodeId(String parentId, XmlConfigNode n, int ordinal) {
        String segment = switch (n.kind) {
            case "property" -> "property:" + safe(n.name);
            case "constructor-arg" -> "constructor-arg:" + n.index;
            case "entry" -> "entry:" + safe(n.key != null ? n.key : String.valueOf(n.index));
            case "ref" -> "ref:" + safe(n.bean);
            case "idref" -> "idref:" + safe(n.bean);
            case "value" -> "value:" + ordinal;
            case "null" -> "null:" + ordinal;
            default -> n.kind;
        };
        return parentId + "/" + segment + "@L" + n.line + "C" + n.column + "I" + ordinal;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "_";
        return value.replace('/', '_').replace(' ', '_');
    }

    private static String labelOf(XmlConfigNode n) {
        return switch (n.kind) {
            case "property" -> n.name == null ? "property" : n.name;
            case "constructor-arg" -> "constructor-arg[" + n.index + "]";
            case "entry" -> n.key == null ? "entry" : n.key;
            case "ref" -> n.bean == null ? "ref" : n.bean;
            case "idref" -> n.bean == null ? "idref" : n.bean;
            case "value" -> n.value == null ? "value" : n.value;
            default -> n.kind;
        };
    }

    private static String kindOf(String kind) {
        return switch (kind) {
            case "property" -> GraphConstants.Kind.XML_PROPERTY;
            case "constructor-arg" -> GraphConstants.Kind.XML_CONSTRUCTOR_ARG;
            case "map" -> GraphConstants.Kind.XML_MAP;
            case "list" -> GraphConstants.Kind.XML_LIST;
            case "entry" -> GraphConstants.Kind.XML_ENTRY;
            case "ref" -> GraphConstants.Kind.XML_REF;
            case "idref" -> GraphConstants.Kind.XML_IDREF;
            case "value" -> GraphConstants.Kind.XML_VALUE;
            case "null" -> GraphConstants.Kind.XML_NULL;
            default -> "XML_" + kind.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        };
    }

    private static Map<String, Object> metadataOf(XmlConfigNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("xmlKind", n.kind);
        if (n.name != null) m.put("name", n.name);
        if (n.key != null) m.put("key", n.key);
        if (n.index != null) m.put("index", n.index);
        if (n.bean != null) m.put("bean", n.bean);
        if (n.value != null) m.put("value", n.value);
        return m;
    }

    private static String metadataJson(Map<String, ?> metadata) {
        return Json.writeCompact(metadata);
    }
}
