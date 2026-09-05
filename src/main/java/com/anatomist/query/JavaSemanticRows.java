package com.anatomist.query;

import java.util.List;

/** Storage-independent rows used by the Java semantic query adapter. */
public final class JavaSemanticRows {
    private JavaSemanticRows() {}

    public record TypeRelation(String id, String semantic, String mechanism,
                               String subject, String object, String subjectName,
                               String objectName, boolean externalObject,
                               boolean declared, String origin,
                               String resolutionStatus, String confidence) {}

    public record RuntimeImplementation(NodeRow entity, String instantiability,
                                        String reason, String world,
                                        List<TypeRelation> proof) {
        public RuntimeImplementation {
            proof = proof == null ? List.of() : List.copyOf(proof);
        }
    }

    public record CallableRelation(String id, String semantic, String mechanism,
                                   String subject, String object, String subjectName,
                                   String objectName, boolean externalObject,
                                   String origin, String resolutionStatus,
                                   String confidence) {}

    public record DispatchTarget(String id, String callSite, String caller,
                                 String target, String targetName,
                                 String candidateKind, String mechanism,
                                 Boolean executable, String instantiability,
                                 String algorithm, String world,
                                 String resolutionStatus, List<String> reason,
                                 List<CallableRelation> proof) {
        public DispatchTarget {
            reason = reason == null ? List.of() : List.copyOf(reason);
            proof = proof == null ? List.of() : List.copyOf(proof);
        }
    }
}
