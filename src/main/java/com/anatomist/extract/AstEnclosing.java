package com.anatomist.extract;

import com.anatomist.core.NodeIdGenerator;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;

import java.util.Optional;

/**
 * Walks AST ancestors to compute the Node ID of the entity that owns a given
 * AST element — accounting for LAMBDA / METHOD_REF nodes that are now first-
 * class members of the index graph (REQ-001, REQ-002, BR-001).
 *
 * <p>The owner search order, from closest to farthest, is:
 * LambdaExpr → MethodReferenceExpr → MethodDeclaration → ConstructorDeclaration
 * → FieldDeclaration → TypeDeclaration. The first match wins.</p>
 */
final class AstEnclosing {

    private final NodeIdGenerator gen;

    AstEnclosing(NodeIdGenerator gen) {
        this.gen = gen;
    }

    /** Owner of {@code node}: the id of the closest ancestor entity, or null. */
    String ownerIdOf(Node node) {
        Optional<Node> p = node.getParentNode();
        while (p.isPresent()) {
            Node cur = p.get();
            if (cur instanceof LambdaExpr le) return lambdaId(le);
            if (cur instanceof MethodReferenceExpr mr) return methodRefId(mr);
            if (cur instanceof MethodDeclaration md) return tryMethodId(md);
            if (cur instanceof ConstructorDeclaration cd) return tryConstructorId(cd);
            if (cur instanceof FieldDeclaration fd) return fieldFallbackId(fd);
            if (cur instanceof TypeDeclaration<?> td) return tryTypeId(td);
            p = cur.getParentNode();
        }
        return null;
    }

    /** Id of a LAMBDA Node itself. */
    String lambdaId(LambdaExpr le) {
        String parent = ownerIdOf(le);
        if (parent == null) return null;
        int line = le.getBegin().map(pos -> pos.line).orElse(0);
        int col  = le.getBegin().map(pos -> pos.column).orElse(0);
        return NodeIdGenerator.forLambda(parent, line, col);
    }

    /** Id of a METHOD_REF Node itself. */
    String methodRefId(MethodReferenceExpr mr) {
        String parent = ownerIdOf(mr);
        if (parent == null) return null;
        int line = mr.getBegin().map(pos -> pos.line).orElse(0);
        int col  = mr.getBegin().map(pos -> pos.column).orElse(0);
        return NodeIdGenerator.forMethodRef(parent, line, col);
    }

    private String tryMethodId(MethodDeclaration md) {
        try { return gen.forMethod(md.resolve()); }
        catch (RuntimeException e) { return anonymousMethodFallbackId(md); }
    }

    private String tryConstructorId(ConstructorDeclaration cd) {
        try { return gen.forConstructor(cd.resolve()); }
        catch (RuntimeException e) { return null; }
    }

    private String tryTypeId(TypeDeclaration<?> td) {
        try { return gen.forType(td.resolve()); }
        catch (RuntimeException e) { return null; }
    }

    private String anonymousMethodFallbackId(MethodDeclaration md) {
        Optional<ObjectCreationExpr> anon = md.findAncestor(ObjectCreationExpr.class)
                .filter(o -> o.getAnonymousClassBody().isPresent());
        if (anon.isEmpty()) return null;
        Optional<MethodDeclaration> outer = anon.get().findAncestor(MethodDeclaration.class);
        if (outer.isEmpty()) return null;
        String outerId;
        try { outerId = gen.forMethod(outer.get().resolve()); }
        catch (RuntimeException e) { return null; }
        int line = anon.get().getBegin().map(p -> p.line).orElse(0);
        return outerId + "$anon@L" + line + "#" + md.getNameAsString()
                + "(" + astSignature(md) + ")";
    }

    private static String astSignature(MethodDeclaration md) {
        return md.getParameters().stream()
                .map(p -> {
                    try { return NodeIdGenerator.erasedTypeDescribe(p.getType().resolve()); }
                    catch (RuntimeException e) { return "<unresolved>"; }
                })
                .collect(java.util.stream.Collectors.joining(","));
    }

    /**
     * Field initialisers don't have a CallableDeclaration ancestor. Mirror
     * {@link FieldExtractor}: id is {@code <classFqn>#<firstVarName>}.
     */
    private String fieldFallbackId(FieldDeclaration fd) {
        if (fd.getVariables().isEmpty()) return null;
        VariableDeclarator first = fd.getVariable(0);
        Optional<TypeDeclaration> td = fd.findAncestor(TypeDeclaration.class);
        if (td.isEmpty()) return null;
        try {
            return td.get().resolve().getQualifiedName() + "#" + first.getNameAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
