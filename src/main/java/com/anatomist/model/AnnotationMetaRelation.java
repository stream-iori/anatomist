package com.anatomist.model;

/** Direct annotation-to-meta-annotation relationship used to compute bounded meta closure. */
public class AnnotationMetaRelation {
    public String annotationFqn;
    public String metaAnnotationFqn;
    public String rawName;
    public String language = "java";
    public String mechanism = "java.annotation.meta";
    public String resolutionStatus;
    public String sourceFile;
    public String sourceLocation;
    public String producerId;
}
