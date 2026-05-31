package com.anatomist.core.nativeimage;

import java.util.List;

/** Structural record of a JDK class, captured at anatomist build time and
 *  shipped as a binary resource. Holds only erased descriptor info — generics
 *  signature parsing lives in the future {@code AsmTypeSolver} adapter. */
public class JdkType {

    public static final int FLAG_PUBLIC      = 1 << 0;
    public static final int FLAG_STATIC      = 1 << 1;
    public static final int FLAG_FINAL       = 1 << 2;
    public static final int FLAG_INTERFACE   = 1 << 3;
    public static final int FLAG_ABSTRACT    = 1 << 4;
    public static final int FLAG_ENUM        = 1 << 5;
    public static final int FLAG_ANNOTATION  = 1 << 6;
    public static final int FLAG_CLASS       = 1 << 7;
    public static final int FLAG_RECORD      = 1 << 8;

    public String fqn;
    public String superFqn;          // null for java.lang.Object and interfaces
    public List<String> interfaceFqns;
    public int flags;
    public List<FieldEntry> fields;
    public List<MethodEntry> methods;

    public static final class FieldEntry {
        public final String name;
        public final String descriptor;  // JVM descriptor (e.g. "Ljava/util/List;")
        public final int flags;
        public FieldEntry(String name, String descriptor, int flags) {
            this.name = name; this.descriptor = descriptor; this.flags = flags;
        }
    }

    public static final class MethodEntry {
        public final String name;
        public final String descriptor;  // JVM method descriptor (e.g. "(II)Ljava/lang/String;")
        public final int flags;
        public MethodEntry(String name, String descriptor, int flags) {
            this.name = name; this.descriptor = descriptor; this.flags = flags;
        }
    }
}
