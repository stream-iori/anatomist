package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AsmDescriptorParserTest {

    private static final TypeSolver SOLVER = new ReflectionTypeSolver();

    @Test
    void primitives() {
        assertEquals("int", parse("I").describe());
        assertEquals("long", parse("J").describe());
        assertEquals("boolean", parse("Z").describe());
        assertEquals("double", parse("D").describe());
        assertEquals("float", parse("F").describe());
        assertEquals("byte", parse("B").describe());
        assertEquals("short", parse("S").describe());
        assertEquals("char", parse("C").describe());
    }

    @Test
    void voidType() {
        assertEquals("void", parse("V").describe());
    }

    @Test
    void referenceType() {
        assertEquals("java.lang.String", parse("Ljava/lang/String;").describe());
    }

    @Test
    void arrayPrimitive() {
        assertEquals("int[]", parse("[I").describe());
    }

    @Test
    void arrayReference() {
        assertEquals("java.lang.String[]", parse("[Ljava/lang/String;").describe());
    }

    @Test
    void array2D() {
        assertEquals("int[][]", parse("[[I").describe());
    }

    @Test
    void methodDescriptor_parameters() {
        // (II)Ljava/lang/String; -> [int, int]
        List<ResolvedType> params = AsmDescriptorParser.parseMethodParameters("(II)Ljava/lang/String;", SOLVER);
        assertEquals(2, params.size());
        assertEquals("int", params.get(0).describe());
        assertEquals("int", params.get(1).describe());
    }

    @Test
    void methodDescriptor_returnType() {
        ResolvedType ret = AsmDescriptorParser.parseMethodReturnType("(II)Ljava/lang/String;", SOLVER);
        assertEquals("java.lang.String", ret.describe());
    }

    @Test
    void methodDescriptor_mixedParams() {
        // (ILjava/lang/String;[I)V -> [int, String, int[]] -> void
        List<ResolvedType> params = AsmDescriptorParser.parseMethodParameters(
                "(ILjava/lang/String;[I)V", SOLVER);
        assertEquals(3, params.size());
        assertEquals("int", params.get(0).describe());
        assertEquals("java.lang.String", params.get(1).describe());
        assertEquals("int[]", params.get(2).describe());

        ResolvedType ret = AsmDescriptorParser.parseMethodReturnType("(ILjava/lang/String;[I)V", SOLVER);
        assertEquals("void", ret.describe());
    }

    @Test
    void methodDescriptor_noParams() {
        List<ResolvedType> params = AsmDescriptorParser.parseMethodParameters("()I", SOLVER);
        assertTrue(params.isEmpty());
        assertEquals("int", AsmDescriptorParser.parseMethodReturnType("()I", SOLVER).describe());
    }

    private static ResolvedType parse(String descriptor) {
        return AsmDescriptorParser.parseFieldDescriptor(descriptor, SOLVER);
    }
}
