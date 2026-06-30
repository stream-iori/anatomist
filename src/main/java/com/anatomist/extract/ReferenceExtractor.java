package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.type.UnknownType;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

public class ReferenceExtractor implements Extractor {

    private static final int MAX_GENERIC_DEPTH = 5;

    private final ExtractionContext ctx;
    private final AstEnclosing enclosing;

    public ReferenceExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
        this.enclosing = new AstEnclosing(ctx.idGenerator());
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;

        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(FieldDeclaration n, Void arg) {
                for (VariableDeclarator var : n.getVariables()) {
                    String fieldId;
                    try {
                        ResolvedValueDeclaration v = var.resolve();
                        if (!(v instanceof ResolvedFieldDeclaration field)) continue;
                        fieldId = ctx.idGenerator().forField(field);
                    } catch (RuntimeException e) { ctx.incrementUnresolved(e); continue; }
                    ResolvedType type;
                    try { type = var.getType().resolve(); }
                    catch (RuntimeException e) { ctx.incrementUnresolved(e); continue; }
                    emitTypeRef(fieldId, type, "field_type", result, 0);
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                String methodId;
                try { methodId = ctx.idGenerator().forMethod(n.resolve()); }
                catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }

                // Return type
                try {
                    if (!"void".equals(n.getTypeAsString())) {
                        emitTypeRef(methodId, n.getType().resolve(), "return_type", result, 0);
                    }
                } catch (RuntimeException e) { ctx.incrementUnresolved(e); }

                // Parameters
                for (Parameter p : n.getParameters()) {
                    try {
                        emitTypeRef(methodId, p.getType().resolve(), "parameter_type", result, 0);
                    } catch (RuntimeException e) { ctx.incrementUnresolved(e); }
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(LambdaExpr n, Void arg) {
                String lambdaId = enclosing.lambdaId(n);
                if (lambdaId != null) {
                    for (Parameter p : n.getParameters()) {
                        if (p.getType() instanceof UnknownType) continue;
                        try {
                            emitTypeRef(lambdaId, p.getType().resolve(), "parameter_type", result, 0);
                        } catch (RuntimeException e) { ctx.incrementUnresolved(e); }
                    }
                    try {
                        ResolvedType functional = n.calculateResolvedType();
                        if (functional != null && functional.isReferenceType()) {
                            // Functional interface return type is the descriptor's return.
                            // We can't easily isolate it without resolving the SAM, but we
                            // already cover parameter_type which is the common signal.
                        }
                    } catch (RuntimeException e) { ctx.incrementUnresolved(e); }
                }
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitTypeRef(String sourceId, ResolvedType type, String context,
                             ExtractionResult result, int depth) {
        if (type == null || depth > MAX_GENERIC_DEPTH) return;
        if (type.isPrimitive() || type.isVoid() || type.isTypeVariable() || type.isWildcard()) {
            return;
        }
        if (type.isArray()) {
            emitTypeRef(sourceId, type.asArrayType().getComponentType(), context, result, depth);
            return;
        }
        if (!type.isReferenceType()) return;
        ResolvedReferenceType ref = type.asReferenceType();
        ref.getTypeDeclaration().ifPresent(td -> {
            Edge e = new Edge();
            e.sourceId = sourceId;
            e.relation = GraphConstants.Relation.REFERENCES;
            e.confidence = GraphConstants.Confidence.EXTRACTED;
            e.context = context;

            if (ctx.isProjectInternal(td)) {
                e.targetId = ctx.idGenerator().forType(td);
                e.isExternal = false;
            } else {
                String fqn = NodeIdGenerator.externalTypeFqn(td);
                if (ctx.isExternalExcluded(fqn)) return;
                e.externalTargetFqn = fqn;
                e.isExternal = true;
            }
            result.edges.add(e);
        });
        // Recurse into generic args regardless of outer internal/external.
        for (ResolvedType arg : ref.typeParametersValues()) {
            emitTypeRef(sourceId, arg, "generic_arg", result, depth + 1);
        }
    }
}
