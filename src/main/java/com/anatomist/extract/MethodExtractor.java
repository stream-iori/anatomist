package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MethodExtractor implements Extractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExtractionContext ctx;

    public MethodExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = sourceFileOf(unit);
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                emit(node, sourceFile, result);
                return true;
            }
        });
    }

    private void emit(MethodDeclaration decl, String sourceFile, ExtractionResult result) {
        IMethodBinding binding = decl.resolveBinding();
        if (binding == null) return;
        ITypeBinding declClass = binding.getDeclaringClass();
        if (declClass == null) return;

        String methodId = ctx.idGenerator().forMethod(binding);
        String classId = ctx.idGenerator().forType(declClass);

        Node n = new Node();
        n.id = methodId;
        n.label = binding.getName();
        n.kind = "METHOD";
        n.qualifiedName = declClass.getErasure().getQualifiedName() + "#" + binding.getName();
        n.pkg = declClass.getPackage() == null ? null : declClass.getPackage().getName();
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + lineNumber(decl);
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.javadoc = decl.getJavadoc() == null ? null : decl.getJavadoc().toString();
        n.metadata = metadataJson(decl, binding);
        result.nodes.add(n);

        Edge e = new Edge();
        e.sourceId = classId;
        e.targetId = methodId;
        e.externalTargetFqn = null;
        e.relation = "CONTAINS";
        e.callKind = null;
        e.confidence = "EXTRACTED";
        e.context = null;
        e.isExternal = false;
        e.sourceFile = sourceFile;
        e.sourceLocation = n.sourceLocation;
        result.edges.add(e);
    }

    private static int lineNumber(MethodDeclaration decl) {
        CompilationUnit cu = (CompilationUnit) decl.getRoot();
        return cu.getLineNumber(decl.getStartPosition());
    }

    private static String sourceFileOf(CompilationUnit unit) {
        Object prop = unit.getProperty("source_file");
        return prop instanceof String s ? s : null;
    }

    private static String metadataJson(MethodDeclaration decl, IMethodBinding binding) {
        Map<String, Object> meta = new LinkedHashMap<>();
        ITypeBinding rt = binding.getReturnType();
        meta.put("returnType", rt == null ? null : rt.getName());

        List<Map<String, String>> params = new ArrayList<>();
        ITypeBinding[] paramTypes = binding.getParameterTypes();
        List<?> declParams = decl.parameters();
        for (int i = 0; i < paramTypes.length; i++) {
            Map<String, String> p = new LinkedHashMap<>();
            String name = i < declParams.size()
                    ? ((SingleVariableDeclaration) declParams.get(i)).getName().getIdentifier()
                    : "arg" + i;
            p.put("name", name);
            p.put("type", paramTypes[i].getName());
            params.add(p);
        }
        meta.put("parameters", params);
        meta.put("isStatic", Modifier.isStatic(binding.getModifiers()));
        meta.put("isAbstract", Modifier.isAbstract(binding.getModifiers()));
        meta.put("isConstructor", binding.isConstructor());

        List<String> mods = new ArrayList<>();
        int m = binding.getModifiers();
        if (Modifier.isPublic(m)) mods.add("public");
        if (Modifier.isProtected(m)) mods.add("protected");
        if (Modifier.isPrivate(m)) mods.add("private");
        if (Modifier.isStatic(m)) mods.add("static");
        if (Modifier.isFinal(m)) mods.add("final");
        meta.put("modifiers", mods);

        StringBuilder sig = new StringBuilder(binding.getName()).append("(");
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            String name = i < declParams.size()
                    ? ((SingleVariableDeclaration) declParams.get(i)).getName().getIdentifier()
                    : "arg" + i;
            sig.append(paramTypes[i].getName()).append(' ').append(name);
        }
        sig.append(")");
        meta.put("signature", sig.toString());

        try {
            return JSON.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
