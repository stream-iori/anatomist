package com.anatomist.extract;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Computes the lightweight control-flow context path of an AST node — the
 * nested branch/loop blocks the node lives inside, relative to its nearest
 * owner (method / constructor / lambda / field initialiser / type).
 *
 * <p>This is NOT a CFG: no statement nodes, no control edges, no reachability.
 * It is a single string label attached to an already-emitted CALLS / READS /
 * WRITES edge so the index can answer "this call is inside the else branch of
 * an if, inside a for loop" without re-parsing.</p>
 *
 * <p>Encoding is the full nested path, outer→inner, joined by {@code >}, each
 * segment {@code <kind>@L<line>}, e.g. {@code for@L40>if-else@L42}. Returns
 * {@code null} when the node is not inside any tracked control-flow block.</p>
 *
 * <p>Mirrors {@link AstEnclosing}: walks {@code getParentNode()} and stops at
 * the same owner boundaries, and uses identity ({@code ==}) — never
 * {@code equals()} — to distinguish then/else, try/finally, etc., because
 * JavaParser's {@code Node.equals()} is structural.</p>
 *
 * <p>Out of scope: {@code &&}/{@code ||} short-circuit RHS is intentionally not
 * treated as a branch — too noisy for too little signal.</p>
 */
final class ControlContext {

    private ControlContext() {}

    static String of(Node node) {
        List<String> segs = new ArrayList<>();
        Node child = node;
        Optional<Node> p = node.getParentNode();
        while (p.isPresent()) {
            Node cur = p.get();
            if (isOwnerBoundary(cur)) break;
            String seg = segmentFor(cur, child);
            if (seg != null) segs.add(seg);
            child = cur;
            p = cur.getParentNode();
        }
        if (segs.isEmpty()) return null;
        java.util.Collections.reverse(segs); // outer → inner
        return String.join(">", segs);
    }

    private static boolean isOwnerBoundary(Node n) {
        return n instanceof LambdaExpr
                || n instanceof MethodReferenceExpr
                || n instanceof MethodDeclaration
                || n instanceof ConstructorDeclaration
                || n instanceof InitializerDeclaration
                || n instanceof FieldDeclaration
                || n instanceof TypeDeclaration<?>;
    }

    /** Segment for {@code cur} given the {@code child} we ascended from, or null. */
    private static String segmentFor(Node cur, Node child) {
        if (cur instanceof IfStmt ifs) {
            if (child == ifs.getThenStmt()) return "if-then@L" + line(cur);
            if (ifs.getElseStmt().map(e -> e == child).orElse(false)) return "if-else@L" + line(cur);
            return null; // condition is unconditionally executed
        }
        if (cur instanceof ForStmt) return "for@L" + line(cur);
        if (cur instanceof ForEachStmt) return "foreach@L" + line(cur);
        if (cur instanceof WhileStmt) return "while@L" + line(cur);
        if (cur instanceof DoStmt) return "do@L" + line(cur);
        if (cur instanceof SwitchEntry se) {
            return (se.getLabels().isEmpty() ? "default@L" : "case@L") + line(cur);
        }
        if (cur instanceof CatchClause) return "catch@L" + line(cur);
        if (cur instanceof TryStmt ts) {
            if (ts.getFinallyBlock().map(b -> b == child).orElse(false)) return "finally@L" + line(cur);
            if (child == ts.getTryBlock()) return "try@L" + line(cur);
            return null; // resources / catch handled elsewhere
        }
        if (cur instanceof SynchronizedStmt) return "synchronized@L" + line(cur);
        if (cur instanceof ConditionalExpr ce) {
            if (child == ce.getThenExpr()) return "ternary-then@L" + line(cur);
            if (child == ce.getElseExpr()) return "ternary-else@L" + line(cur);
            return null; // condition is unconditionally executed
        }
        return null;
    }

    private static int line(Node n) {
        return n.getBegin().map(pos -> pos.line).orElse(0);
    }
}
