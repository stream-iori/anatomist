package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Annotation;
import com.anatomist.model.ExtractionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnnotationExtractor implements Extractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExtractionContext ctx;

    public AnnotationExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                ITypeBinding b = node.resolveBinding();
                if (b != null) collect(ctx.idGenerator().forType(b), b.getAnnotations(), result);
                return true;
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                ITypeBinding b = node.resolveBinding();
                if (b != null) collect(ctx.idGenerator().forType(b), b.getAnnotations(), result);
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding b = node.resolveBinding();
                if (b == null) return true;
                String methodId = ctx.idGenerator().forMethod(b);
                collect(methodId, b.getAnnotations(), result);
                // parameter annotations
                List<?> params = node.parameters();
                for (int i = 0; i < params.size(); i++) {
                    SingleVariableDeclaration p = (SingleVariableDeclaration) params.get(i);
                    IVariableBinding pb = p.resolveBinding();
                    if (pb == null) continue;
                    IAnnotationBinding[] anns = pb.getAnnotations();
                    if (anns.length == 0) continue;
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("_param", i);
                    extra.put("_name", p.getName().getIdentifier());
                    collectWithExtra(methodId, anns, extra, result);
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                for (Object o : node.fragments()) {
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) o;
                    IVariableBinding b = frag.resolveBinding();
                    if (b == null || !b.isField()) continue;
                    ITypeBinding decl = b.getDeclaringClass();
                    if (decl == null) continue;
                    String fieldId = ctx.idGenerator().forField(b);
                    collect(fieldId, b.getAnnotations(), result);
                }
                return true;
            }
        });
    }

    private static void collect(String nodeId, IAnnotationBinding[] anns, ExtractionResult result) {
        if (anns == null) return;
        for (IAnnotationBinding ann : anns) {
            ITypeBinding annType = ann.getAnnotationType();
            if (annType == null) continue;
            Annotation a = new Annotation();
            a.nodeId = nodeId;
            a.annotationFqn = annType.getQualifiedName();
            a.attributes = attributesJson(ann, null);
            result.annotations.add(a);
        }
    }

    private static void collectWithExtra(String nodeId, IAnnotationBinding[] anns,
                                         Map<String, Object> extra, ExtractionResult result) {
        if (anns == null) return;
        for (IAnnotationBinding ann : anns) {
            ITypeBinding annType = ann.getAnnotationType();
            if (annType == null) continue;
            Annotation a = new Annotation();
            a.nodeId = nodeId;
            a.annotationFqn = annType.getQualifiedName();
            a.attributes = attributesJson(ann, extra);
            result.annotations.add(a);
        }
    }

    private static String attributesJson(IAnnotationBinding ann, Map<String, Object> extra) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (extra != null) attrs.putAll(extra);
        for (IMemberValuePairBinding mvp : ann.getDeclaredMemberValuePairs()) {
            attrs.put(mvp.getName(), stringifyValue(mvp.getValue()));
        }
        try {
            return JSON.writeValueAsString(attrs);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static Object stringifyValue(Object value) {
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Object[] arr) {
            List<Object> out = new ArrayList<>();
            for (Object o : arr) out.add(stringifyValue(o));
            return out;
        }
        return value.toString();
    }
}
