package com.anatomist.extract;

import com.anatomist.model.GraphConstants;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

final class CallKindClassifier {

    private CallKindClassifier() {}

    static String classify(ResolvedMethodDeclaration target, MethodCallExpr call) {
        if (call.getScope().isPresent() && call.getScope().get() instanceof SuperExpr) {
            return GraphConstants.CallKind.SUPER;
        }
        if (target.isStatic()) return GraphConstants.CallKind.STATIC;
        try {
            ResolvedReferenceTypeDeclaration declaringType = target.declaringType().asReferenceType();
            if (declaringType.isInterface()) return GraphConstants.CallKind.INTERFACE;
        } catch (RuntimeException ignore) {
            // Fall through to instance when JavaParser cannot expose the owner kind.
        }
        return GraphConstants.CallKind.INSTANCE;
    }
}
