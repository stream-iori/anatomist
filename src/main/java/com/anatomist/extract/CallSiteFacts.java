package com.anatomist.extract;

import com.anatomist.model.Edge;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;

/** Adds language-frontend call-site facts without coupling query code to JavaParser. */
final class CallSiteFacts {
    private CallSiteFacts() {}

    static void attach(Edge edge, Node node) {
        if (edge == null || node == null) return;
        Range range = node.getRange().orElse(null);
        if (range == null) return;
        edge.beginLine = range.begin.line;
        edge.beginColumn = range.begin.column;
        edge.endLine = range.end.line;
        edge.endColumn = range.end.column;
        edge.sourceOrdinal = 0;
        edge.sourceFile = node.findCompilationUnit().map(SourceFiles::of).orElse(edge.sourceFile);
        edge.syntaxTarget = syntaxTarget(node);
        edge.receiverStaticType = receiverStaticType(node);
        if (edge.sourceLocation == null) edge.sourceLocation = "L" + range.begin.line;
    }

    private static String syntaxTarget(Node node) {
        if (node instanceof MethodCallExpr call) {
            return call.getScope().map(scope -> scope + ".").orElse("")
                    + call.getNameAsString();
        }
        if (node instanceof ObjectCreationExpr creation) return "new " + creation.getTypeAsString();
        if (node instanceof MethodReferenceExpr reference) {
            return reference.getScope() + "::" + reference.getIdentifier();
        }
        return null;
    }

    private static String receiverStaticType(Node node) {
        // Never trigger a second symbol-solver walk merely to decorate an already-resolved call.
        // Resolved extractors fill the declaring/static fallback after target resolution.
        return node instanceof ObjectCreationExpr creation ? creation.getTypeAsString() : null;
    }
}
