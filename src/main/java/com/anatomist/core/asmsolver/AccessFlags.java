package com.anatomist.core.asmsolver;

import com.github.javaparser.ast.AccessSpecifier;
import org.objectweb.asm.Opcodes;

public final class AccessFlags {

    private AccessFlags() {}

    public static AccessSpecifier toSpecifier(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0)    return AccessSpecifier.PUBLIC;
        if ((access & Opcodes.ACC_PROTECTED) != 0) return AccessSpecifier.PROTECTED;
        if ((access & Opcodes.ACC_PRIVATE) != 0)   return AccessSpecifier.PRIVATE;
        return AccessSpecifier.NONE;
    }

    public static boolean isPublic(int access)   { return (access & Opcodes.ACC_PUBLIC) != 0; }
    public static boolean isStatic(int access)    { return (access & Opcodes.ACC_STATIC) != 0; }
    public static boolean isAbstract(int access)  { return (access & Opcodes.ACC_ABSTRACT) != 0; }
    public static boolean isFinal(int access)     { return (access & Opcodes.ACC_FINAL) != 0; }
    public static boolean isInterface(int access) { return (access & Opcodes.ACC_INTERFACE) != 0; }
    public static boolean isEnum(int access)      { return (access & Opcodes.ACC_ENUM) != 0; }
    public static boolean isAnnotation(int access){ return (access & Opcodes.ACC_ANNOTATION) != 0; }
    public static boolean isRecord(int access)    { return (access & Opcodes.ACC_RECORD) != 0; }
    public static boolean isSynthetic(int access) { return (access & Opcodes.ACC_SYNTHETIC) != 0; }
    public static boolean isBridge(int access)    { return (access & Opcodes.ACC_BRIDGE) != 0; }
    public static boolean isVarArgs(int access)   { return (access & Opcodes.ACC_VARARGS) != 0; }
}
