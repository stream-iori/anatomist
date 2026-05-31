package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.core.NodeIdGenerator;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.anatomist.core.NodeIdGenerator.erasedTypeDescribe;

public class MethodExtractor implements Extractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExtractionContext ctx;
    private final AstEnclosing enclosing;

    public MethodExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
        this.enclosing = new AstEnclosing(ctx.idGenerator());
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = unit.containsData(TypeExtractor.SourceFileKey.KEY)
                ? unit.getData(TypeExtractor.SourceFileKey.KEY)
                : null;
        if (sourceFile == null) {
            sourceFile = unit.getStorage().map(s -> s.getPath().toString()).orElse(null);
        }
        final String sf = sourceFile;

        new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(MethodDeclaration n, Void arg) {
                emitMethod(n, sf, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                emitConstructor(n, sf, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(LambdaExpr n, Void arg) {
                emitLambda(n, sf, result);
                super.visit(n, arg);
            }

            @Override
            public void visit(MethodReferenceExpr n, Void arg) {
                emitMethodRef(n, sf, result);
                super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitMethod(MethodDeclaration decl, String sourceFile, ExtractionResult result) {
        ResolvedMethodDeclaration r;
        try {
            r = decl.resolve();
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }
        ResolvedTypeDeclaration declType;
        String methodId;
        String classId;
        String returnTypeName;
        try {
            declType = r.declaringType();
            if (skipDeclaringType(declType)) return;
            methodId = ctx.idGenerator().forMethod(r);
            classId = ctx.idGenerator().forType(declType);
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }

        Node n = new Node();
        n.id = methodId;
        n.label = r.getName();
        n.kind = "METHOD";
        n.qualifiedName = declType.getQualifiedName() + "#" + r.getName();
        n.pkg = declType.getPackageName();
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + lineOf(decl);
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.javadoc = decl.getJavadocComment().map(c -> c.getContent()).orElse(null);
        n.metadata = methodMetadata(decl, r, false);
        result.nodes.add(n);

        result.edges.add(containsEdge(classId, methodId, sourceFile, n.sourceLocation));
    }

    private void emitConstructor(ConstructorDeclaration decl, String sourceFile, ExtractionResult result) {
        ResolvedConstructorDeclaration r;
        try {
            r = decl.resolve();
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }
        ResolvedTypeDeclaration declType;
        String methodId;
        String classId;
        try {
            declType = r.declaringType();
            if (skipDeclaringType(declType)) return;
            methodId = ctx.idGenerator().forConstructor(r);
            classId = ctx.idGenerator().forType(declType);
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
            return;
        }

        Node n = new Node();
        n.id = methodId;
        n.label = r.getName();
        n.kind = "METHOD";
        n.qualifiedName = declType.getQualifiedName() + "#" + r.getName();
        n.pkg = declType.getPackageName();
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + lineOf(decl);
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.javadoc = decl.getJavadocComment().map(c -> c.getContent()).orElse(null);
        n.metadata = methodMetadata(decl, r, true);
        result.nodes.add(n);

        result.edges.add(containsEdge(classId, methodId, sourceFile, n.sourceLocation));
    }

    private void emitLambda(LambdaExpr lambda, String sourceFile, ExtractionResult result) {
        String parentId;
        try { parentId = enclosing.ownerIdOf(lambda); }
        catch (RuntimeException e) { ctx.incrementUnresolved(); return; }
        if (parentId == null) return;

        int line = lambda.getBegin().map(p -> p.line).orElse(0);
        int col  = lambda.getBegin().map(p -> p.column).orElse(0);
        String id = NodeIdGenerator.forLambda(parentId, line, col);

        boolean bindingResolved = true;
        String returnType = null;
        try { returnType = NodeIdGenerator.erasedTypeDescribe(lambda.calculateResolvedType()); }
        catch (RuntimeException e) { bindingResolved = false; ctx.incrementUnresolved(); }

        List<Map<String, String>> params = new ArrayList<>();
        for (com.github.javaparser.ast.body.Parameter p : lambda.getParameters()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", p.getNameAsString());
            String typeDesc;
            try { typeDesc = NodeIdGenerator.erasedTypeDescribe(p.getType().resolve()); }
            catch (RuntimeException e) { typeDesc = "<unresolved>"; }
            entry.put("type", typeDesc);
            params.add(entry);
        }

        StringBuilder sig = new StringBuilder("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sig.append(", ");
            sig.append(params.get(i).get("type")).append(' ').append(params.get(i).get("name"));
        }
        sig.append(") -> ").append(returnType == null ? "<unresolved>" : returnType);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("parameters", params);
        meta.put("returnType", returnType);
        meta.put("signature", sig.toString());
        meta.put("bindingResolved", bindingResolved);

        Node n = new Node();
        n.id = id;
        n.label = "$lambda";
        n.kind = "LAMBDA";
        n.qualifiedName = id;
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + line;
        n.module = ctx.module();
        n.scope = ctx.scope();
        try { n.metadata = JSON.writeValueAsString(meta); }
        catch (JsonProcessingException e) { n.metadata = "{}"; }
        result.nodes.add(n);

        result.edges.add(containsEdge(parentId, id, sourceFile, n.sourceLocation));
    }

    private void emitMethodRef(MethodReferenceExpr ref, String sourceFile, ExtractionResult result) {
        String parentId;
        try { parentId = enclosing.ownerIdOf(ref); }
        catch (RuntimeException e) { ctx.incrementUnresolved(); return; }
        if (parentId == null) return;

        int line = ref.getBegin().map(p -> p.line).orElse(0);
        int col  = ref.getBegin().map(p -> p.column).orElse(0);
        String id = NodeIdGenerator.forMethodRef(parentId, line, col);

        boolean bindingResolved = false;
        ResolvedMethodDeclaration target = null;
        try {
            target = ref.resolve();
            bindingResolved = true;
        } catch (RuntimeException e) {
            ctx.incrementUnresolved();
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("identifier", ref.getIdentifier());
        meta.put("bindingResolved", bindingResolved);

        Node n = new Node();
        n.id = id;
        n.label = "$methodref:" + ref.getIdentifier();
        n.kind = "METHOD_REF";
        n.qualifiedName = id;
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + line;
        n.module = ctx.module();
        n.scope = ctx.scope();
        try { n.metadata = JSON.writeValueAsString(meta); }
        catch (JsonProcessingException e) { n.metadata = "{}"; }
        result.nodes.add(n);

        result.edges.add(containsEdge(parentId, id, sourceFile, n.sourceLocation));

        if (target != null) {
            Edge call = new Edge();
            call.sourceId = id;
            call.relation = "CALLS";
            call.callKind = target.isStatic() ? "STATIC" : "INSTANCE";
            call.confidence = "EXTRACTED";
            call.sourceLocation = "L" + line;
            try {
                ResolvedTypeDeclaration decl = target.declaringType();
                if (ctx.isProjectInternal(decl)) {
                    call.targetId = ctx.idGenerator().forMethod(target);
                    call.isExternal = false;
                } else {
                    call.externalTargetFqn = NodeIdGenerator.externalMethodFqn(target);
                    call.isExternal = true;
                }
                result.edges.add(call);
            } catch (RuntimeException e) {
                ctx.incrementUnresolved();
            }
        }
    }

    /**
     * Same invariant as the original {@code MethodExtractor.emit} short-circuit
     * (BR-007): Phase 1 does not emit nodes for anonymous / local classes via
     * a TypeDeclaration walk, so methods declared in such bodies would point
     * at a non-existent classId and break the FK on the CONTAINS edge.
     * TypeExtractor emits anonymous classes, so anonymous-owned methods are
     * fine; only local classes are still skipped here.
     */
    private static boolean skipDeclaringType(ResolvedTypeDeclaration declType) {
        // JavaParser does not expose "is local class" as a uniform predicate.
        // Anonymous declarations come back as JavaParserAnonymousClassDeclaration
        // — those are emitted by TypeExtractor, keep them. Reject things that
        // are neither class/interface/enum/annotation at all.
        return !(declType.isClass() || declType.isInterface()
                || declType.isEnum() || declType.isAnnotation());
    }

    private static Edge containsEdge(String classId, String methodId,
                                     String sourceFile, String sourceLoc) {
        Edge e = new Edge();
        e.sourceId = classId;
        e.targetId = methodId;
        e.relation = "CONTAINS";
        e.confidence = "EXTRACTED";
        e.isExternal = false;
        e.sourceFile = sourceFile;
        e.sourceLocation = sourceLoc;
        return e;
    }

    private static boolean isAccessor(MethodDeclaration md) {
        String name = md.getNameAsString();
        int paramCount = md.getParameters().size();
        String returnType;
        try {
            returnType = md.getType().asString();
        } catch (RuntimeException e) {
            return false;
        }
        boolean voidReturn = "void".equals(returnType);
        boolean booleanReturn = "boolean".equals(returnType) || "Boolean".equals(returnType);

        if (name.startsWith("get") && name.length() > 3 && paramCount == 0 && !voidReturn) {
            return true;
        }
        if (name.startsWith("is") && name.length() > 2 && paramCount == 0 && booleanReturn) {
            return true;
        }
        if (name.startsWith("set") && name.length() > 3 && paramCount == 1 && voidReturn) {
            return true;
        }
        return false;
    }

    private static int lineOf(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(p -> p.line).orElse(0);
    }

    private static String methodMetadata(com.github.javaparser.ast.body.CallableDeclaration<?> decl,
                                         ResolvedMethodLikeDeclaration r,
                                         boolean isConstructor) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (isConstructor) {
            meta.put("returnType", null);
        } else {
            try { meta.put("returnType", erasedTypeDescribe(((ResolvedMethodDeclaration) r).getReturnType())); }
            catch (RuntimeException e) { meta.put("returnType", null); }
        }

        List<Map<String, String>> params = new ArrayList<>();
        for (int i = 0; i < r.getNumberOfParams(); i++) {
            Map<String, String> p = new LinkedHashMap<>();
            String name = i < decl.getParameters().size()
                    ? decl.getParameter(i).getNameAsString() : "arg" + i;
            p.put("name", name);
            String typeDesc;
            try { typeDesc = erasedTypeDescribe(r.getParam(i).getType()); }
            catch (RuntimeException e) { typeDesc = "<unresolved>"; }
            p.put("type", typeDesc);
            params.add(p);
        }
        meta.put("parameters", params);

        boolean isStatic = decl.isStatic();
        boolean isAbstract = decl instanceof MethodDeclaration md && md.isAbstract();
        meta.put("isStatic", isStatic);
        meta.put("isAbstract", isAbstract);
        meta.put("isConstructor", isConstructor);
        meta.put("isAccessor", isConstructor ? false : isAccessor((MethodDeclaration) decl));

        List<String> mods = new ArrayList<>();
        for (Modifier m : decl.getModifiers()) {
            String kw = m.getKeyword().asString();
            mods.add(kw);
        }
        meta.put("modifiers", mods);

        StringBuilder sig = new StringBuilder(r.getName()).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sig.append(", ");
            Map<String, String> p = params.get(i);
            sig.append(p.get("type")).append(' ').append(p.get("name"));
        }
        sig.append(")");
        meta.put("signature", sig.toString());

        try { return JSON.writeValueAsString(meta); }
        catch (JsonProcessingException e) { return "{}"; }
    }
}
