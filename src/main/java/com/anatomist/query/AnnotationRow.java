package com.anatomist.query;

/** Direct or meta-expanded annotation use attached to one structural entity. */
public record AnnotationRow(String entity,
                            String name,
                            String rawName,
                            String attributes,
                            String targetKind,
                            String targetPath,
                            String language,
                            String mechanism,
                            String resolutionStatus,
                            String sourceFile,
                            String sourceLocation,
                            Integer beginLine,
                            Integer beginColumn,
                            Integer endLine,
                            Integer endColumn,
                            String producerId,
                            boolean direct,
                            int metaDepth,
                            String via) {}
