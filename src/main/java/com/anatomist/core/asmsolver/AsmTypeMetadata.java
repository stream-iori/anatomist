package com.anatomist.core.asmsolver;

import java.util.List;

/** Immutable ASM declaration data suitable for packed persistence. */
record AsmTypeMetadata(String fqn, String superFqn, List<String> interfaceFqns,
                       int access, String signature, List<Field> fields,
                       List<Method> methods, List<Constructor> constructors,
                       List<String> annotations, List<String> nestedTypes) {
    record Field(String name, String descriptor, int access) {}
    record Method(String name, String descriptor, String signature, int access) {}
    record Constructor(String descriptor, int access) {}
}
