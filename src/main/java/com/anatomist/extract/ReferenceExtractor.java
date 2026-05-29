package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

public class ReferenceExtractor implements Extractor {

    private static final int MAX_GENERIC_DEPTH = 5;

    private final ExtractionContext ctx;

    public ReferenceExtractor(ExtractionContext ctx) {
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
                    IVariableBinding fb = frag.resolveBinding();
                    if (fb == null || !fb.isField()) continue;
                    String fieldId = ctx.idGenerator().forField(fb);
                    ITypeBinding type = fb.getType();
                    if (type == null) continue;
                    emitTypeReference(fieldId, type, "field_type", sourceFile, result, 0);
                }
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding mb = node.resolveBinding();
                if (mb == null) return true;
                String methodId = ctx.idGenerator().forMethod(mb);
                ITypeBinding rt = mb.getReturnType();
                if (rt != null) emitTypeReference(methodId, rt, "return_type", sourceFile, result, 0);
                ITypeBinding[] pts = mb.getParameterTypes();
                for (ITypeBinding pt : pts) {
                    emitTypeReference(methodId, pt, "parameter_type", sourceFile, result, 0);
                }
                return true;
            }
        });
    }

    private void emitTypeReference(String sourceId, ITypeBinding type, String context,
                                   String sourceFile, ExtractionResult result, int depth) {
        if (type == null || depth > MAX_GENERIC_DEPTH) return;
        ITypeBinding erasure = type.getErasure();
        if (erasure == null) return;
        if (erasure.isPrimitive() || erasure.isTypeVariable() || erasure.isWildcardType()) {
            // primitives and type variables don't reference a real type node;
            // still recurse into bounds for wildcards (rare in field/param types)
        } else if (ctx.isProjectInternal(erasure)) {
            Edge e = new Edge();
            e.sourceId = sourceId;
            e.targetId = ctx.idGenerator().forType(erasure);
            e.relation = "REFERENCES";
            e.confidence = "EXTRACTED";
            e.context = context;
            e.isExternal = false;
            e.sourceFile = sourceFile;
            result.edges.add(e);
        }
        // recurse into generic args regardless of whether the outer type is
        // project-internal — Map<String, Order> should still emit Order
        for (ITypeBinding arg : type.getTypeArguments()) {
            emitTypeReference(sourceId, arg, "generic_arg", sourceFile, result, depth + 1);
        }
    }

    private static String sourceFileOf(CompilationUnit unit) {
        Object prop = unit.getProperty("source_file");
        return prop instanceof String s ? s : null;
    }
}
