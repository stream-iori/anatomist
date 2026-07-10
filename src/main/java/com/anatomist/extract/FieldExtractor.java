package com.anatomist.extract;

import com.anatomist.json.Json;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
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

    private final ExtractionContext ctx;

    public FieldExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = SourceFiles.of(unit);

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
        catch (RuntimeException e) { ctx.incrementUnresolved(e); return; }
        String classId = ctx.idGenerator().forType(rt);
        StringBuilder ctorParams = new StringBuilder();
        for (com.github.javaparser.ast.body.Parameter p : decl.getParameters()) {
            String name = p.getNameAsString();
            String fieldId = rt.getQualifiedName() + "#" + name;
            String typeDesc;
            try { typeDesc = com.anatomist.core.NodeIdGenerator.erasedTypeDescribe(p.getType().resolve()); }
            catch (RuntimeException e) { typeDesc = p.getTypeAsString(); }

            Node n = new Node();
            n.id = fieldId;
            n.label = name;
            n.kind = GraphConstants.Kind.FIELD;
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
            n.metadata = Json.writeCompact(meta);
            result.nodes.add(n);

            result.edges.add(containsEdge(classId, fieldId, sourceFile, n.sourceLocation));

            if (!hasExplicitAccessor(decl, name)) {
                emitRecordAccessor(rt, classId, name, typeDesc, sourceFile, n.sourceLocation, result);
            }

            if (ctorParams.length() > 0) ctorParams.append(',');
            ctorParams.append(typeDesc);
        }

        if (hasDeclaredCanonicalConstructor(decl)) return;

        // Synthetic canonical constructor — record components imply
        // <RecordName>(<types...>) when no explicit/compact canonical
        // constructor is present.
        String simple = rt.getName();
        String ctorId = rt.getQualifiedName() + "#" + simple + "(" + ctorParams + ")";
        Node ctor = new Node();
        ctor.id = ctorId;
        ctor.label = simple;
        ctor.kind = GraphConstants.Kind.METHOD;
        ctor.qualifiedName = rt.getQualifiedName() + "#" + simple;
        ctor.pkg = rt.getPackageName();
        ctor.sourceFile = sourceFile;
        ctor.sourceLocation = "L" + lineOf(decl);
        ctor.module = ctx.module();
        ctor.scope = ctx.scope();
        Map<String, Object> cmeta = new LinkedHashMap<>();
        cmeta.put("isConstructor", true);
        cmeta.put("isSynthetic", true);
        cmeta.put("isRecordCanonical", true);
        ctor.metadata = Json.writeCompact(cmeta);
        result.nodes.add(ctor);
        result.edges.add(containsEdge(classId, ctorId, sourceFile, ctor.sourceLocation));
    }

    private void emitRecordAccessor(ResolvedReferenceTypeDeclaration record,
                                    String classId,
                                    String name,
                                    String returnType,
                                    String sourceFile,
                                    String sourceLocation,
                                    ExtractionResult result) {
        String methodId = record.getQualifiedName() + "#" + name + "()";
        Node accessor = new Node();
        accessor.id = methodId;
        accessor.label = name;
        accessor.kind = GraphConstants.Kind.METHOD;
        accessor.qualifiedName = record.getQualifiedName() + "#" + name;
        accessor.pkg = record.getPackageName();
        accessor.sourceFile = sourceFile;
        accessor.sourceLocation = sourceLocation;
        accessor.module = ctx.module();
        accessor.scope = ctx.scope();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("returnType", returnType);
        metadata.put("parameters", java.util.List.of());
        metadata.put("isStatic", false);
        metadata.put("isAbstract", false);
        metadata.put("isConstructor", false);
        metadata.put("isAccessor", true);
        metadata.put("isSynthetic", true);
        metadata.put("isRecordComponentAccessor", true);
        accessor.metadata = Json.writeCompact(metadata);
        result.nodes.add(accessor);
        result.edges.add(containsEdge(classId, methodId, sourceFile, sourceLocation));
    }

    private static boolean hasExplicitAccessor(RecordDeclaration declaration, String name) {
        return declaration.getMethodsByName(name).stream()
                .anyMatch(method -> method.getParameters().isEmpty());
    }

    private static boolean hasDeclaredCanonicalConstructor(RecordDeclaration declaration) {
        if (!declaration.getCompactConstructors().isEmpty()) return true;
        java.util.List<String> recordParams = declaration.getParameters().stream()
                .map(p -> AstTypeNames.of(p.getType(), p))
                .toList();
        for (ConstructorDeclaration constructor : declaration.getConstructors()) {
            if (constructor.getParameters().size() != recordParams.size()) continue;
            java.util.List<String> constructorParams = constructor.getParameters().stream()
                    .map(p -> AstTypeNames.of(p.getType(), p))
                    .toList();
            if (recordParams.equals(constructorParams)) return true;
        }
        return false;
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
        n.kind = GraphConstants.Kind.FIELD;
        n.qualifiedName = fieldId;
        n.pkg = declType.getPackageName();
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + lineOf(var);
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.javadoc = com.anatomist.core.JavadocSummary.extract(
                decl.getJavadocComment().map(c -> c.getContent()).orElse(null));
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
                || declType.isEnum() || declType.isRecord() || declType.isAnnotation());
    }

    private static Edge containsEdge(String classId, String fieldId,
                                     String sourceFile, String sourceLoc) {
        Edge e = new Edge();
        e.sourceId = classId;
        e.targetId = fieldId;
        e.relation = GraphConstants.Relation.CONTAINS;
        e.confidence = GraphConstants.Confidence.EXTRACTED;
        e.isExternal = false;
        e.sourceFile = sourceFile;
        e.sourceLocation = sourceLoc;
        return e;
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static String fieldMetadata(FieldDeclaration decl, VariableDeclarator var) {
        Map<String, Object> meta = new LinkedHashMap<>();
        try { meta.put("type", erasedTypeDescribe(var.getType().resolve())); }
        catch (RuntimeException e) { meta.put("type", var.getTypeAsString()); }
        meta.put("isStatic", decl.isStatic());
        meta.put("isFinal", decl.isFinal());
        return Json.writeCompact(meta);
    }
}
