package com.anatomist.extract;

import com.anatomist.core.NodeIdGenerator;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;

import java.util.Optional;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Walks AST ancestors to compute the Node ID of the entity that owns a given
 * AST element — accounting for LAMBDA / METHOD_REF nodes that are now first-
 * class members of the index graph (REQ-001, REQ-002, BR-001).
 *
 * <p>The owner search order, from closest to farthest, is:
 * LambdaExpr → MethodReferenceExpr → MethodDeclaration → ConstructorDeclaration
 * → FieldDeclaration → TypeDeclaration. The first match wins.</p>
 */
public final class AstEnclosing {

    private final NodeIdGenerator gen;
    private final Map<Node, String> entityIds = new IdentityHashMap<>();

    public AstEnclosing(NodeIdGenerator gen) {
        this.gen = gen;
    }

    /** Owner of {@code node}: the id of the closest ancestor entity, or null. */
    public String ownerIdOf(Node node) {
        Optional<Node> p = node.getParentNode();
        while (p.isPresent()) {
            Node cur = p.get();
            if (cur instanceof LambdaExpr le) return cachedId(le, () -> lambdaId(le));
            if (cur instanceof MethodReferenceExpr mr) return cachedId(mr, () -> methodRefId(mr));
            if (cur instanceof MethodDeclaration md) return cachedId(md, () -> tryMethodId(md));
            if (cur instanceof ConstructorDeclaration cd) return cachedId(cd, () -> tryConstructorId(cd));
            if (cur instanceof CompactConstructorDeclaration cd) {
                return cachedId(cd, () -> tryCompactConstructorId(cd));
            }
            if (cur instanceof FieldDeclaration fd) return cachedId(fd, () -> fieldFallbackId(fd));
            if (cur instanceof TypeDeclaration<?> td) return cachedId(td, () -> tryTypeId(td));
            p = cur.getParentNode();
        }
        return null;
    }

    private String cachedId(Node node, java.util.function.Supplier<String> loader) {
        if (entityIds.containsKey(node)) return entityIds.get(node);
        String id = loader.get();
        entityIds.put(node, id);
        return id;
    }

    int cachedEntityCount() {
        return entityIds.size();
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

    public String anonymousClassId(ObjectCreationExpr expr) {
        if (expr == null || expr.getAnonymousClassBody().isEmpty()) return null;
        String parent = ownerIdOf(expr);
        if (parent == null) return null;
        int line = expr.getBegin().map(p -> p.line).orElse(0);
        int column = expr.getBegin().map(p -> p.column).orElse(0);
        return parent + "$anon@L" + line + "C" + column;
    }

    private String tryMethodId(MethodDeclaration md) {
        try { return CallableIdFactory.forMethod(gen, md); }
        catch (RuntimeException e) { return anonymousMethodFallbackId(md); }
    }

    private String tryConstructorId(ConstructorDeclaration cd) {
        try { return CallableIdFactory.forConstructor(gen, cd); }
        catch (RuntimeException e) { return null; }
    }

    private String tryCompactConstructorId(CompactConstructorDeclaration cd) {
        try { return CallableIdFactory.forCompactConstructor(gen, cd); }
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
        String classId = anonymousClassId(anon.get());
        if (classId == null) return null;
        return CallableIdFactory.forAnonymousMethod(classId, md);
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
