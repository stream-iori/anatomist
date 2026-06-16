package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.List;

public final class LazyDescriptorResolver {

    private final String signature;
    private final String descriptor;
    private final TypeSolver solver;
    private volatile List<ResolvedType> paramTypes;
    private volatile ResolvedType returnType;

    public LazyDescriptorResolver(String descriptor, TypeSolver solver) {
        this(null, descriptor, solver);
    }

    public LazyDescriptorResolver(String signature, String descriptor, TypeSolver solver) {
        this.signature = signature;
        this.descriptor = descriptor;
        this.solver = solver;
    }

    public List<ResolvedType> paramTypes() {
        if (paramTypes == null) {
            synchronized (this) {
                if (paramTypes == null) {
                    List<ResolvedType> fromSig = AsmSignatureParser.parseMethodParameterTypes(
                            signature, descriptor, solver);
                    paramTypes = fromSig != null ? fromSig
                            : AsmDescriptorParser.parseMethodParameters(descriptor, solver);
                }
            }
        }
        return paramTypes;
    }

    public ResolvedType returnType() {
        if (returnType == null) {
            synchronized (this) {
                if (returnType == null) {
                    ResolvedType fromSig = AsmSignatureParser.parseMethodReturnType(
                            signature, descriptor, solver);
                    returnType = fromSig != null ? fromSig
                            : AsmDescriptorParser.parseMethodReturnType(descriptor, solver);
                }
            }
        }
        return returnType;
    }

    public String descriptor() { return descriptor; }
    public String signature() { return signature; }
    public TypeSolver solver() { return solver; }
}
