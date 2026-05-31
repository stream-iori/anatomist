package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedEnumConstantDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.anatomist.core.NodeIdGenerator.erasedTypeDescribe;

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

        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(FieldDeclaration n, Void arg) {
                for (VariableDeclarator var : n.getVariables()) {
                    emitField(n, var, sourceFile, result);
                }
                super.visit(n, arg);
            }

            @Override
            public void visit(EnumConstantDeclaration n, Void arg) {
                emitEnumConstant(n, sourceFile, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(RecordDeclaration n, Void arg) {
                emitRecordComponents(n, sourceFile, result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitRecordComponents(RecordDeclaration decl, String sourceFile, ExtractionResult result) {
        ResolvedReferenceTypeDeclaration rt;
        try { rt = decl.resolve(); }
        catch (RuntimeException e) { ctx.incrementUnresolved(); return; }
        String classId = ctx.idGenerator().forType(rt);
        for (com.github.javaparser.ast.body.Parameter p : decl.getParameters()) {
            String name = p.getNameAsString();
            String fieldId = rt.getQualifiedName() + "#" + name;
            String typeDesc;
            try { typeDesc = com.anatomist.core.NodeIdGenerator.erasedTypeDescribe(p.getType().resolve()); }
            catch (RuntimeException e) { typeDesc = p.getTypeAsString(); }

            Node n = new Node();
            n.id = fieldId;
            n.label = name;
            n.kind = "FIELD";
            n.qualifiedName = fieldId;
            n.pkg = rt.getPackageName();
            n.sourceFile = sourceFile;
            n.sourceLocation = "L" + lineOf(p);
            n.module = ctx.module();
            n.scope = ctx.scope();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("type", typeDesc);
            meta.put("isStatic", false);
            meta.put("isFinal", true);
            meta.put("isRecordComponent", true);
            try { n.metadata = JSON.writeValueAsString(meta); }
            catch (JsonProcessingException e) { n.metadata = "{}"; }
            result.nodes.add(n);

            result.edges.add(containsEdge(classId, fieldId, sourceFile, n.sourceLocation));
        }
    }

    private void emitField(FieldDeclaration decl, VariableDeclarator var,
                           String sourceFile, ExtractionResult result) {
        // FieldDeclaration.resolve() requires a single VariableDeclarator;
        // for `int a, b, c;` we must resolve each VariableDeclarator
        // individually.
        ResolvedFieldDeclaration r;
        try {
            com.github.javaparser.resolution.declarations.ResolvedValueDeclaration v = var.resolve();
            if (!(v instanceof ResolvedFieldDeclaration field)) return;
            r = field;
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }
        ResolvedTypeDeclaration declType;
        try {
            declType = r.declaringType();
            if (skipDeclaringType(declType)) return;
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }
        String classId = ctx.idGenerator().forType(declType);
        String fieldId = declType.getQualifiedName() + "#" + var.getNameAsString();

        Node n = new Node();
        n.id = fieldId;
        n.label = var.getNameAsString();
        n.kind = "FIELD";
        n.qualifiedName = fieldId;
        n.pkg = declType.getPackageName();
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + lineOf(var);
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.javadoc = decl.getJavadocComment().map(c -> c.getContent()).orElse(null);
        n.metadata = fieldMetadata(decl, var);
        result.nodes.add(n);

        result.edges.add(containsEdge(classId, fieldId, sourceFile, n.sourceLocation));
    }

    private void emitEnumConstant(EnumConstantDeclaration decl, String sourceFile, ExtractionResult result) {
        ResolvedEnumConstantDeclaration r;
        try {
            r = decl.resolve();
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }
        // ResolvedEnumConstantDeclaration only exposes the type FQN via getType().
        String enumFqn;
        try {
            enumFqn = r.getType().describe();
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }
        String id = enumFqn + "#" + decl.getNameAsString();

        Node n = new Node();
        n.id = id;
        n.label = decl.getNameAsString();
        n.kind = "ENUM_CONSTANT";
        n.qualifiedName = id;
        n.pkg = packageOf(enumFqn);
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + lineOf(decl);
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.metadata = "{}";
        result.nodes.add(n);

        result.edges.add(containsEdge(enumFqn, id, sourceFile, n.sourceLocation));
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    private static boolean skipDeclaringType(ResolvedTypeDeclaration declType) {
        // Anonymous classes are emitted by TypeExtractor — keep their fields.
        // Local classes still have no CLASS node, so skip their fields to
        // avoid orphan CONTAINS edges (same rationale as MethodExtractor).
        return !(declType.isClass() || declType.isInterface()
                || declType.isEnum() || declType.isAnnotation());
    }

    private static Edge containsEdge(String classId, String fieldId,
                                     String sourceFile, String sourceLoc) {
        Edge e = new Edge();
        e.sourceId = classId;
        e.targetId = fieldId;
        e.relation = "CONTAINS";
        e.confidence = "EXTRACTED";
        e.isExternal = false;
        e.sourceFile = sourceFile;
        e.sourceLocation = sourceLoc;
        return e;
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static String sourceFileOf(CompilationUnit unit) {
        if (unit.containsData(TypeExtractor.SourceFileKey.KEY)) {
            return unit.getData(TypeExtractor.SourceFileKey.KEY);
        }
        return unit.getStorage().map(s -> s.getPath().toString()).orElse(null);
    }

    private static String fieldMetadata(FieldDeclaration decl, VariableDeclarator var) {
        Map<String, Object> meta = new LinkedHashMap<>();
        try { meta.put("type", erasedTypeDescribe(var.getType().resolve())); }
        catch (RuntimeException e) { meta.put("type", var.getTypeAsString()); }
        meta.put("isStatic", decl.isStatic());
        meta.put("isFinal", decl.isFinal());
        try { return JSON.writeValueAsString(meta); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}
