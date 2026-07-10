package com.anatomist.model;

public class Annotation {
    public String nodeId;
    public String annotationFqn;
    public String attributes;
    /** Extraction-only source identity hint; not persisted in the annotations table. */
    public String sourceFile;
}
