package com.anatomist.core.asmsolver;

import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.resolution.declarations.ResolvedTypeParameterDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AsmSignatureParserTest {

    private static final TypeSolver SOLVER = new ReflectionTypeSolver();

    // ── Type parameter extraction (existing v2 functionality) ──

    @Test
    void classTypeParams_singleUnbounded() {
        // <E:Ljava/lang/Object;>Ljava/lang/Object;
        var params = AsmSignatureParser.parseClassTypeParameters(
                "<E:Ljava/lang/Object;>Ljava/lang/Object;", "java.util.List", SOLVER);
        assertEquals(1, params.size());
        assertEquals("E", params.get(0).getName());
    }

    @Test
    void classTypeParams_multipleParams() {
        // <K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/lang/Object;
        var params = AsmSignatureParser.parseClassTypeParameters(
                "<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/lang/Object;", "java.util.Map", SOLVER);
        assertEquals(2, params.size());
        assertEquals("K", params.get(0).getName());
        assertEquals("V", params.get(1).getName());
    }

    @Test
    void classTypeParams_withBound() {
        // <T:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;
        var params = AsmSignatureParser.parseClassTypeParameters(
                "<T:Ljava/lang/Comparable<TT;>;>Ljava/lang/Object;", "com.Foo", SOLVER);
        assertEquals(1, params.size());
        assertEquals("T", params.get(0).getName());
        assertTrue(params.get(0).getBounds().size() > 0);
    }

    @Test
    void classTypeParams_nullSignature() {
        var params = AsmSignatureParser.parseClassTypeParameters(null, "Foo", SOLVER);
        assertTrue(params.isEmpty());
    }

    // ── Method return type parsing (v3 new functionality) ──

    @Test
    void methodReturnType_nullSignature() {
        ResolvedType rt = AsmSignatureParser.parseMethodReturnType(null, null, SOLVER);
        assertNull(rt, "null signature → null return (caller falls back to descriptor)");
    }

    @Test
    void methodReturnType_primitive() {
        // ()J — signature with primitive return
        ResolvedType rt = AsmSignatureParser.parseMethodReturnType("()J", null, SOLVER);
        assertNotNull(rt);
        assertEquals("long", rt.describe());
    }

    @Test
    void methodReturnType_parameterizedReturn() {
        // Collection.stream(): ()Ljava/util/stream/Stream<TE;>;
        ResolvedType rt = AsmSignatureParser.parseMethodReturnType(
                "()Ljava/util/stream/Stream<TE;>;", null, SOLVER);
        assertNotNull(rt);
        assertTrue(rt.isReferenceType());
        assertEquals("java.util.stream.Stream", rt.asReferenceType().getQualifiedName());
        // Should have 1 type parameter value (E as a type variable)
        assertEquals(1, rt.asReferenceType().typeParametersValues().size());
    }

    @Test
    void methodReturnType_rawReturn() {
        // ()Ljava/lang/String; — no generics
        ResolvedType rt = AsmSignatureParser.parseMethodReturnType(
                "()Ljava/lang/String;", null, SOLVER);
        assertNotNull(rt);
        assertEquals("java.lang.String", rt.asReferenceType().getQualifiedName());
    }

    @Test
    void methodReturnType_withTypeParamsAndReturn() {
        // Optional.ofNullable: <T:Ljava/lang/Object;>(TT;)Ljava/util/Optional<TT;>;
        ResolvedType rt = AsmSignatureParser.parseMethodReturnType(
                "<T:Ljava/lang/Object;>(TT;)Ljava/util/Optional<TT;>;", null, SOLVER);
        assertNotNull(rt);
        assertEquals("java.util.Optional", rt.asReferenceType().getQualifiedName());
        assertEquals(1, rt.asReferenceType().typeParametersValues().size());
    }

    @Test
    void methodReturnType_doubleStream() {
        // Stream.mapToDouble: (Ljava/util/function/ToDoubleFunction<-TT;>;)Ljava/util/stream/DoubleStream;
        ResolvedType rt = AsmSignatureParser.parseMethodReturnType(
                "(Ljava/util/function/ToDoubleFunction<-TT;>;)Ljava/util/stream/DoubleStream;", null, SOLVER);
        assertNotNull(rt);
        assertEquals("java.util.stream.DoubleStream", rt.asReferenceType().getQualifiedName());
    }

    // ── Method parameter type parsing (v3) ──

    @Test
    void methodParamTypes_simple() {
        // Stream.filter: (Ljava/util/function/Predicate<-TT;>;)Ljava/util/stream/Stream<TT;>;
        List<ResolvedType> params = AsmSignatureParser.parseMethodParameterTypes(
                "(Ljava/util/function/Predicate<-TT;>;)Ljava/util/stream/Stream<TT;>;", null, SOLVER);
        assertNotNull(params);
        assertEquals(1, params.size());
        assertEquals("java.util.function.Predicate", params.get(0).asReferenceType().getQualifiedName());
        assertEquals(1, params.get(0).asReferenceType().typeParametersValues().size());
    }

    @Test
    void methodParamTypes_nullSignature() {
        assertNull(AsmSignatureParser.parseMethodParameterTypes(null, null, SOLVER));
    }

    @Test
    void methodParamTypes_mapPut() {
        // Map.put: (TK;TV;)TV;
        List<ResolvedType> params = AsmSignatureParser.parseMethodParameterTypes(
                "(TK;TV;)TV;", null, SOLVER);
        assertNotNull(params);
        assertEquals(2, params.size());
        // Type variables should be returned as type parameters
        assertTrue(params.get(0).isTypeVariable());
        assertTrue(params.get(1).isTypeVariable());
    }

    @Test
    void methodReturnType_typeVariable() {
        // Map.get: (Ljava/lang/Object;)TV;
        ResolvedType rt = AsmSignatureParser.parseMethodReturnType(
                "(Ljava/lang/Object;)TV;", null, SOLVER);
        assertNotNull(rt);
        assertTrue(rt.isTypeVariable());
    }

    @Test
    void methodParamTypes_void() {
        // ()V — no params in signature
        List<ResolvedType> params = AsmSignatureParser.parseMethodParameterTypes(
                "()V", null, SOLVER);
        assertNotNull(params);
        assertTrue(params.isEmpty());
    }
}
