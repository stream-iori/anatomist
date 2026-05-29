package com.anatomist.core;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates stable Node IDs per DESIGN.md §Node ID 生成规则.
 *
 * <ul>
 *   <li>CLASS/INTERFACE/ENUM: FQN preserved as-is</li>
 *   <li>METHOD: {@code <classFqn>#<name>(<erased,param,fqns>)}</li>
 *   <li>FIELD: {@code <classFqn>#<fieldName>}</li>
 * </ul>
 */
public class NodeIdGenerator {

    public String forType(ITypeBinding binding) {
        if (binding == null) throw new IllegalArgumentException("binding is null");
        ITypeBinding erasure = binding.getErasure();
        String name = erasure.getQualifiedName();
        if (name == null || name.isEmpty()) {
            name = erasure.getBinaryName();
        }
        if (name == null || name.isEmpty()) {
            // Anonymous classes inside lambdas etc. can have null qualified
            // name AND null binary name in some JDT versions; fall back to key.
            name = erasure.getKey();
        }
        return name;
    }

    public String forMethod(IMethodBinding binding) {
        if (binding == null) throw new IllegalArgumentException("binding is null");
        IMethodBinding method = binding.getMethodDeclaration();
        ITypeBinding decl = method.getDeclaringClass();
        String classFqn = forType(decl);
        ITypeBinding[] params = method.getParameterTypes();
        String paramList = IntStream.range(0, params.length)
                .mapToObj(i -> params[i].getErasure().getQualifiedName())
                .collect(Collectors.joining(","));
        String name = method.getName();
        return classFqn + "#" + name + "(" + paramList + ")";
    }

    public String forField(IVariableBinding binding) {
        if (binding == null) throw new IllegalArgumentException("binding is null");
        if (!binding.isField()) throw new IllegalArgumentException("not a field binding");
        ITypeBinding decl = binding.getDeclaringClass();
        String classFqn = forType(decl);
        return classFqn + "#" + binding.getName();
    }
}
