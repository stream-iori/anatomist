package com.anatomist.extract;

import com.anatomist.json.Json;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node.TreeTraversal;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TypeExtractor implements Extractor {

    private final ExtractionContext ctx;
    private final AstEnclosing enclosing;

    public TypeExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
        this.enclosing = new AstEnclosing(ctx.idGenerator());
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = SourceFiles.of(unit);

        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                emitTypeDecl(n, sourceFile, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(EnumDeclaration n, Void arg) {
                emitTypeDecl(n, sourceFile, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(AnnotationDeclaration n, Void arg) {
                emitTypeDecl(n, sourceFile, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(RecordDeclaration n, Void arg) {
                emitTypeDecl(n, sourceFile, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(ObjectCreationExpr n, Void arg) {
                if (n.getAnonymousClassBody().isPresent()) {
                    emitAnonymous(n, sourceFile, result);
                }
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitTypeDecl(TypeDeclaration<?> decl, String sourceFile, ExtractionResult result) {
        ResolvedReferenceTypeDeclaration rt;
        try {
            rt = decl.resolve();
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }
        Node n = new Node();
        n.id = ctx.idGenerator().forType(rt);
        n.label = rt.getName();
        n.kind = kindOf(decl, rt);
        n.qualifiedName = rt.getQualifiedName();
        n.pkg = rt.getPackageName();
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + lineOf(decl);
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.javadoc = com.anatomist.core.JavadocSummary.extract(
                decl.getJavadocComment().map(c -> c.getContent()).orElse(null));
        n.metadata = metadataJson(decl, rt);
        result.nodes.add(n);
    }

    private void emitAnonymous(ObjectCreationExpr expr, String sourceFile, ExtractionResult result) {
        // Resolve the anonymous class itself. JavaParser's resolved declaration
        // of an anonymous class is a JavaParserAnonymousClassDeclaration whose
        // qualifiedName is the enclosing context + "$N". For ID stability we
        // append "$anon@L<line>" to the enclosing method's id, mirroring the
        // existing Phase 1 rule (DESIGN.md §Node ID 生成规则).
        String parentMethodId = enclosing.ownerIdOf(expr);
        if (parentMethodId == null) return; // initializer/field with unresolved owner — Phase 1 skip
        int line = lineOf(expr);
        String id = parentMethodId + "$anon@L" + line;

        String baseType = expr.getType().getNameAsString();
        try {
            // Resolve the instantiated supertype reference (e.g. java.lang.Runnable),
            // NOT expr.resolve() — for an anonymous class the resolved ctor's
            // declaringType is the anon class itself, whose generated name embeds
            // a per-parse UUID and would break re-index idempotency.
            baseType = expr.getType().resolve().describe();
        } catch (RuntimeException ignore) { /* keep simple-name baseType */ }

        Node n = new Node();
        n.id = id;
        n.label = "$anon";
        n.kind = GraphConstants.Kind.ANONYMOUS_CLASS;
        n.qualifiedName = id;
        n.pkg = expr.findCompilationUnit()
                .flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString())
                .orElse(null);
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + line;
        n.module = ctx.module();
        n.scope = ctx.scope();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("baseType", baseType);
        n.metadata = toJson(meta);
        result.nodes.add(n);

        Edge e = new Edge();
        e.sourceId = parentMethodId;
        e.targetId = id;
        e.relation = GraphConstants.Relation.CONTAINS;
        e.confidence = GraphConstants.Confidence.EXTRACTED;
        e.isExternal = false;
        e.sourceFile = sourceFile;
        e.sourceLocation = n.sourceLocation;
        result.edges.add(e);
    }

    private static String kindOf(TypeDeclaration<?> decl, ResolvedReferenceTypeDeclaration rt) {
        if (decl instanceof EnumDeclaration) return GraphConstants.Kind.ENUM;
        if (decl instanceof RecordDeclaration) return GraphConstants.Kind.RECORD;
        if (decl instanceof AnnotationDeclaration) return GraphConstants.Kind.INTERFACE; // annotations stored as INTERFACE per Phase 1 kind set
        if (rt.isInterface()) return GraphConstants.Kind.INTERFACE;
        return GraphConstants.Kind.CLASS;
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static String metadataJson(TypeDeclaration<?> decl, ResolvedReferenceTypeDeclaration rt) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (decl instanceof EnumDeclaration enumDecl) {
            List<String> consts = new ArrayList<>();
            for (EnumConstantDeclaration c : enumDecl.getEntries()) {
                consts.add(c.getNameAsString());
            }
            meta.put("constants", consts);
        } else {
            boolean isInterface = rt.isInterface();
            meta.put("isAbstract", isAbstract(decl));
            meta.put("isInterface", isInterface);
            try {
                Optional<ResolvedReferenceType> sc = rt.asClass().getSuperClass();
                sc.filter(s -> !"java.lang.Object".equals(s.getQualifiedName()))
                  .ifPresent(s -> meta.put("superClass", s.getTypeDeclaration()
                          .map(d -> d.getName()).orElse(s.getQualifiedName())));
            } catch (RuntimeException ignore) { }
            try {
                List<ResolvedReferenceType> ifs = isInterface
                        ? rt.asInterface().getInterfacesExtended()
                        : rt.asClass().getInterfaces();
                if (!ifs.isEmpty()) {
                    List<String> names = new ArrayList<>();
                    for (ResolvedReferenceType i : ifs) {
                        names.add(i.getTypeDeclaration().map(d -> d.getName())
                                .orElse(i.getQualifiedName()));
                    }
                    meta.put("interfaces", names);
                }
            } catch (RuntimeException ignore) { }
        }
        return toJson(meta);
    }

    private static boolean isAbstract(TypeDeclaration<?> decl) {
        if (decl instanceof ClassOrInterfaceDeclaration c) return c.isAbstract() || c.isInterface();
        return false;
    }

    static String toJson(Map<String, Object> meta) {
        return Json.writeCompact(meta);
    }

    /** Marker used by IndexCommand to stash the relative source path on a CU. */
    public static final class SourceFileKey extends com.github.javaparser.ast.DataKey<String> {
        public static final SourceFileKey KEY = new SourceFileKey();
        private SourceFileKey() {}
    }
}
