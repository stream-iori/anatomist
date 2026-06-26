package com.anatomist.model;

import java.util.Set;

public final class GraphConstants {
    private GraphConstants() {}

    public static final Set<String> TYPE_KINDS = Set.of(
            Kind.CLASS, Kind.INTERFACE, Kind.ENUM, Kind.ANNOTATION, Kind.RECORD, Kind.ANONYMOUS_CLASS);
    public static final Set<String> INDEX_SUMMARY_TYPE_KINDS = Set.of(
            Kind.CLASS, Kind.INTERFACE, Kind.ENUM, Kind.RECORD, Kind.ANONYMOUS_CLASS);
    public static final Set<String> METHOD_KINDS = Set.of(Kind.METHOD, Kind.CONSTRUCTOR);

    public static final class Kind {
        public static final String ANNOTATION = "ANNOTATION";
        public static final String ANONYMOUS_CLASS = "ANONYMOUS_CLASS";
        public static final String BEAN = "BEAN";
        public static final String CLASS = "CLASS";
        public static final String CONSTRUCTOR = "CONSTRUCTOR";
        public static final String ENUM = "ENUM";
        public static final String FIELD = "FIELD";
        public static final String INTERFACE = "INTERFACE";
        public static final String METHOD = "METHOD";
        public static final String RECORD = "RECORD";

        private Kind() {}
    }

    public static final class Relation {
        public static final String CALLS = "CALLS";
        public static final String CONTAINS = "CONTAINS";
        public static final String IMPLEMENTS = "IMPLEMENTS";
        public static final String INHERITS = "INHERITS";
        public static final String OVERRIDES = "OVERRIDES";
        public static final String READS = "READS";
        public static final String REFERENCES = "REFERENCES";
        public static final String WIRES = "WIRES";
        public static final String WRITES = "WRITES";

        private Relation() {}
    }

    public static final class Confidence {
        public static final String EXTRACTED = "EXTRACTED";

        private Confidence() {}
    }

    public static final class Scope {
        public static final String MAIN = "MAIN";

        private Scope() {}
    }
}
