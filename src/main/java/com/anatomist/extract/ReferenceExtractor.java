package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
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
                    ResolvedValueDeclaration v;
                    try { v = var.resolve(); }
                    catch (RuntimeException e) { ctx.incrementUnresolved(); continue; }
                    if (!(v instanceof ResolvedFieldDeclaration field)) continue;
                    String fieldId = ctx.idGenerator().forField(field);
                    ResolvedType type;
                    try { type = var.getType().resolve(); }
                    catch (RuntimeException e) { ctx.incrementUnresolved(); continue; }
                    emitTypeRef(fieldId, type, "field_type", result, 0);
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                ResolvedMethodDeclaration m;
                try { m = n.resolve(); }
                catch (RuntimeException e) { ctx.incrementUnresolved(); return; }
                String methodId = ctx.idGenerator().forMethod(m);

                // Return type
                try {
                    if (!"void".equals(n.getTypeAsString())) {
                        emitTypeRef(methodId, n.getType().resolve(), "return_type", result, 0);
                    }
                } catch (RuntimeException e) { ctx.incrementUnresolved(); }

                // Parameters
                for (Parameter p : n.getParameters()) {
                    try {
                        emitTypeRef(methodId, p.getType().resolve(), "parameter_type", result, 0);
                    } catch (RuntimeException e) { ctx.incrementUnresolved(); }
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
                        } catch (RuntimeException e) { ctx.incrementUnresolved(); }
                    }
                    try {
                        ResolvedType functional = n.calculateResolvedType();
                        if (functional != null && functional.isReferenceType()) {
                            // Functional interface return type is the descriptor's return.
                            // We can't easily isolate it without resolving the SAM, but we
                            // already cover parameter_type which is the common signal.
                        }
                    } catch (RuntimeException e) { ctx.incrementUnresolved(); }
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
            if (ctx.isProjectInternal(td)) {
                Edge e = new Edge();
                e.sourceId = sourceId;
                e.targetId = ctx.idGenerator().forType(td);
                e.relation = "REFERENCES";
                e.confidence = "EXTRACTED";
                e.context = context;
                e.isExternal = false;
                result.edges.add(e);
            }
            // External references aren't tracked yet — would inflate the edge
            // table for every java.lang.String / java.util.* in the project.
            // Phase 1.5 keeps REFERENCES project-internal only.
        });
        // Recurse into generic args regardless of outer internal/external.
        for (ResolvedType arg : ref.typeParametersValues()) {
            emitTypeRef(sourceId, arg, "generic_arg", result, depth + 1);
        }
    }
}
