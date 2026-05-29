package com.anatomist.core;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for spinning up JDT bindings inside unit tests without standing up a
 * full Maven project — we parse an in-memory source string with bindings
 * enabled and walk the resulting AST.
 */
public final class JdtTestSupport {

    private JdtTestSupport() {}

    public static CompilationUnit parse(String unitName, String source) {
        ASTParser parser = ASTParser.newParser(AST.JLS21);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setEnvironment(new String[0], new String[0], null, true);
        parser.setUnitName(unitName);
        Map<String, String> opts = new HashMap<>();
        opts.put(org.eclipse.jdt.core.JavaCore.COMPILER_SOURCE, org.eclipse.jdt.core.JavaCore.VERSION_21);
        opts.put(org.eclipse.jdt.core.JavaCore.COMPILER_COMPLIANCE, org.eclipse.jdt.core.JavaCore.VERSION_21);
        opts.put(org.eclipse.jdt.core.JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, org.eclipse.jdt.core.JavaCore.VERSION_21);
        parser.setCompilerOptions(opts);
        parser.setSource(source.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    public static List<AbstractTypeDeclaration> topTypes(CompilationUnit cu) {
        List<AbstractTypeDeclaration> out = new ArrayList<>();
        for (Object o : cu.types()) out.add((AbstractTypeDeclaration) o);
        return out;
    }

    public static ITypeBinding bindingOf(CompilationUnit cu, String simpleName) {
        for (AbstractTypeDeclaration t : topTypes(cu)) {
            if (t.getName().getIdentifier().equals(simpleName)) {
                return t.resolveBinding();
            }
            if (t instanceof TypeDeclaration td) {
                for (TypeDeclaration nested : td.getTypes()) {
                    if (nested.getName().getIdentifier().equals(simpleName)) {
                        return nested.resolveBinding();
                    }
                }
            }
        }
        return null;
    }

    public static List<IMethodBinding> methodBindings(CompilationUnit cu, String typeName, String methodName) {
        List<IMethodBinding> out = new ArrayList<>();
        for (AbstractTypeDeclaration t : topTypes(cu)) {
            if (!(t instanceof TypeDeclaration td)) continue;
            if (!td.getName().getIdentifier().equals(typeName)) continue;
            for (MethodDeclaration m : td.getMethods()) {
                if (m.getName().getIdentifier().equals(methodName)) {
                    out.add(m.resolveBinding());
                }
            }
        }
        return out;
    }
}
