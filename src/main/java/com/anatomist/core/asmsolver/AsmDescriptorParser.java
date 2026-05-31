package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.resolution.model.typesystem.ReferenceTypeImpl;
import com.github.javaparser.resolution.types.ResolvedArrayType;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.resolution.types.ResolvedVoidType;

import java.util.ArrayList;
import java.util.List;

/** Converts JVM bytecode descriptor strings (primitives + reference + array
 *  + method) into {@link ResolvedType} instances backed by a {@link TypeSolver}.
 *
 *  <p>Generic signatures (the {@code Signature} attribute) are <strong>NOT</strong>
 *  handled here — anatomist's NodeIdGenerator only needs erased types for
 *  method IDs, and CallGraphExtractor / FieldAccessExtractor consume erased
 *  types too. Signature parsing can be added later as a v2 pass without
 *  breaking this API. */
public final class AsmDescriptorParser {

    private AsmDescriptorParser() {}

    /** Parse a single field-style descriptor. Examples:
     *  {@code "I"}, {@code "Ljava/lang/String;"}, {@code "[I"}, {@code "V"}. */
    public static ResolvedType parseFieldDescriptor(String descriptor, TypeSolver solver) {
        return parse(descriptor, new int[]{0}, solver);
    }

    /** Parse the parameter list out of a method descriptor like {@code "(II)V"}. */
    public static List<ResolvedType> parseMethodParameters(String descriptor, TypeSolver solver) {
        if (descriptor.charAt(0) != '(') {
            throw new IllegalArgumentException("not a method descriptor: " + descriptor);
        }
        List<ResolvedType> params = new ArrayList<>();
        int[] cursor = {1};
        while (descriptor.charAt(cursor[0]) != ')') {
            params.add(parse(descriptor, cursor, solver));
        }
        return params;
    }

    /** Parse the return type out of a method descriptor like {@code "(II)V"}. */
    public static ResolvedType parseMethodReturnType(String descriptor, TypeSolver solver) {
        if (descriptor.charAt(0) != '(') {
            throw new IllegalArgumentException("not a method descriptor: " + descriptor);
        }
        int close = descriptor.indexOf(')');
        if (close < 0) throw new IllegalArgumentException("unterminated parameter list: " + descriptor);
        int[] cursor = {close + 1};
        return parse(descriptor, cursor, solver);
    }

    /** Advance {@code cursor[0]} past one type token. */
    private static ResolvedType parse(String d, int[] cursor, TypeSolver solver) {
        char c = d.charAt(cursor[0]);
        switch (c) {
            case 'V': cursor[0]++; return ResolvedVoidType.INSTANCE;
            case 'I': cursor[0]++; return ResolvedPrimitiveType.INT;
            case 'J': cursor[0]++; return ResolvedPrimitiveType.LONG;
            case 'Z': cursor[0]++; return ResolvedPrimitiveType.BOOLEAN;
            case 'D': cursor[0]++; return ResolvedPrimitiveType.DOUBLE;
            case 'F': cursor[0]++; return ResolvedPrimitiveType.FLOAT;
            case 'B': cursor[0]++; return ResolvedPrimitiveType.BYTE;
            case 'S': cursor[0]++; return ResolvedPrimitiveType.SHORT;
            case 'C': cursor[0]++; return ResolvedPrimitiveType.CHAR;
            case '[':
                cursor[0]++;
                return new ResolvedArrayType(parse(d, cursor, solver));
            case 'L': {
                int end = d.indexOf(';', cursor[0]);
                if (end < 0) throw new IllegalArgumentException(
                        "unterminated reference at offset " + cursor[0] + ": " + d);
                String internal = d.substring(cursor[0] + 1, end);
                cursor[0] = end + 1;
                String fqn = internal.replace('/', '.');
                // Walk up to root so siblings of our AsmTypeSolver (e.g. ReflectionTypeSolver
                // in the Combined chain) can resolve java.lang.* etc.
                TypeSolver root = solver.getRoot();
                SymbolReference<ResolvedReferenceTypeDeclaration> ref = root.tryToSolveType(fqn);
                if (!ref.isSolved()) {
                    throw new IllegalArgumentException(
                            "cannot resolve referenced type " + fqn);
                }
                return new ReferenceTypeImpl(ref.getCorrespondingDeclaration());
            }
            default:
                throw new IllegalArgumentException(
                        "unsupported descriptor char '" + c + "' in " + d);
        }
    }
}
