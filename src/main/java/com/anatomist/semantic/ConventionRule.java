package com.anatomist.semantic;

/**
 * One CONVENTION rule. {@code annotationFqn} non-null = annotation rule
 * (匹配 annotations 表 of holding node);  non-null {@code labelSuffix} =
 * naming rule (匹配 nodes.label endsWith).
 */
public final class ConventionRule {
    public final String annotationFqn;
    public final String labelSuffix;
    public final String category;

    private ConventionRule(String annotationFqn, String labelSuffix, String category) {
        this.annotationFqn = annotationFqn;
        this.labelSuffix = labelSuffix;
        this.category = category;
    }

    public static ConventionRule annotation(String fqn, String category) {
        return new ConventionRule(fqn, null, category);
    }

    public static ConventionRule naming(String suffix, String category) {
        return new ConventionRule(null, suffix, category);
    }

    public boolean isAnnotation() {
        return annotationFqn != null;
    }
}
