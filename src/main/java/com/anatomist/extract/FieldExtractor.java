package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

public class FieldExtractor implements Extractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExtractionContext ctx;

    public FieldExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = sourceFileOf(unit);
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration node) {
                for (Object o : node.fragments()) {
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) o;
                    IVariableBinding binding = frag.resolveBinding();
                    if (binding == null || !binding.isField()) continue;
                    emit(node, frag, binding, sourceFile, result);
                }
                return true;
            }
        });
    }

    private void emit(FieldDeclaration decl, VariableDeclarationFragment frag,
                      IVariableBinding binding, String sourceFile, ExtractionResult result) {
        ITypeBinding declClass = binding.getDeclaringClass();
        if (declClass == null) return;
        if (declClass.isAnonymous() || declClass.isLocal()) {
            // Anonymous/local class fields would have no matching CLASS node
            // until ANONYMOUS_CLASS lands; safe to skip them for the same
            // reason MethodExtractor used to skip anon methods.
            return;
        }
        String classId = ctx.idGenerator().forType(declClass);
        String fieldId = ctx.idGenerator().forField(binding);

        Node n = new Node();
        n.id = fieldId;
        n.label = binding.getName();
        n.kind = "FIELD";
        n.qualifiedName = declClass.getErasure().getQualifiedName() + "#" + binding.getName();
        n.pkg = declClass.getPackage() == null ? null : declClass.getPackage().getName();
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + ((CompilationUnit) decl.getRoot()).getLineNumber(frag.getStartPosition());
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.javadoc = decl.getJavadoc() == null ? null : decl.getJavadoc().toString();
        n.metadata = metadataJson(binding);
        result.nodes.add(n);

        Edge e = new Edge();
        e.sourceId = classId;
        e.targetId = fieldId;
        e.relation = "CONTAINS";
        e.confidence = "EXTRACTED";
        e.isExternal = false;
        e.sourceFile = sourceFile;
        e.sourceLocation = n.sourceLocation;
        result.edges.add(e);
    }

    private static String sourceFileOf(CompilationUnit unit) {
        Object prop = unit.getProperty("source_file");
        return prop instanceof String s ? s : null;
    }

    private static String metadataJson(IVariableBinding binding) {
        Map<String, Object> meta = new LinkedHashMap<>();
        ITypeBinding type = binding.getType();
        meta.put("type", type == null ? null : type.getName());
        int mods = binding.getModifiers();
        meta.put("isStatic", Modifier.isStatic(mods));
        meta.put("isFinal", Modifier.isFinal(mods));
        try {
            return JSON.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
