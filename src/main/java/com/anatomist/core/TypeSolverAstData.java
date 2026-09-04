package com.anatomist.core;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.DataKey;
import com.github.javaparser.ast.Node;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import java.util.Optional;

/** Read-only access to the parser session's exact type solver from an AST node. */
public final class TypeSolverAstData {
    private TypeSolverAstData() {}

    public static final TypeSolverKey KEY = new TypeSolverKey();

    static void attach(CompilationUnit unit, TypeSolver solver) {
        if (unit != null && solver != null) unit.setData(KEY, solver);
    }

    public static Optional<ResolvedReferenceTypeDeclaration> solve(Node node, String fqn) {
        if (node == null || fqn == null || fqn.isBlank()) return Optional.empty();
        TypeSolver solver = node.findCompilationUnit()
                .flatMap(unit -> unit.findData(KEY)).orElse(null);
        if (solver == null) return Optional.empty();
        try {
            var solved = solver.tryToSolveType(fqn);
            return solved.isSolved()
                    ? Optional.of(solved.getCorrespondingDeclaration()) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public static final class TypeSolverKey extends DataKey<TypeSolver> {
        private TypeSolverKey() {}
    }
}
