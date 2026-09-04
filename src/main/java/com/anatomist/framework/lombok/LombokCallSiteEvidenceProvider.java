package com.anatomist.framework.lombok;

import com.anatomist.framework.CallSiteEvidenceProvider;
import com.anatomist.framework.SyntheticOrigin;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.ProducerIds;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Source-use evidence for Lombok APIs that are intentionally not synthesized into the AST. */
public final class LombokCallSiteEvidenceProvider implements CallSiteEvidenceProvider {
    static final String VERSION = "1";
    private static final String NAMESPACE = "lombok_usage";

    private final LombokConfiguration configuration;

    public LombokCallSiteEvidenceProvider(Path projectRoot) {
        this.configuration = LombokConfiguration.load(projectRoot);
    }

    @Override public String id() { return "lombok-call-site"; }
    @Override public String producerId() { return ProducerIds.LOMBOK_AST; }
    @Override public String version() { return VERSION; }
    @Override public String fingerprintMaterial() { return configuration.fingerprint(); }

    @Override
    public Optional<Evidence> observe(MethodCallExpr call, FallbackCallSite site) {
        if (call == null || site == null || !site.projectInternal()
                || site.ownerDeclaration() == null) return Optional.empty();
        TypeDeclaration<?> owner = astType(site.ownerDeclaration());
        if (owner == null || explicitMethod(owner, call)) return Optional.empty();

        Optional<Evidence> builder = builderEvidence(call, site, owner);
        if (builder.isPresent()) return builder;
        return accessorEvidence(call, site, owner);
    }

    private Optional<Evidence> accessorEvidence(MethodCallExpr call, FallbackCallSite site,
                                                TypeDeclaration<?> owner) {
        if (call.getArguments().size() > 1) return Optional.empty();
        Map<String, AnnotationExpr> typeAnnotations = annotations(owner);
        List<AccessorMatch> matches = new ArrayList<>();
        for (FieldDeclaration field : owner.getFields()) {
            if (field.isStatic()) continue;
            Map<String, AnnotationExpr> fieldAnnotations = annotations(field);
            Accessors accessors = accessors(fieldAnnotations.getOrDefault(
                    "Accessors", typeAnnotations.get("Accessors")));
            for (VariableDeclarator variable : field.getVariables()) {
                String base = logicalBase(variable.getNameAsString(), accessors.prefixes());
                if (base == null || base.isBlank()) continue;
                if (call.getArguments().isEmpty()
                        && getterEnabled(field, fieldAnnotations, typeAnnotations)
                        && getterName(variable, base, accessors.fluent())
                        .equals(call.getNameAsString())) {
                    matches.add(new AccessorMatch(variable, "read"));
                }
                if (call.getArguments().size() == 1
                        && setterEnabled(field, fieldAnnotations, typeAnnotations)
                        && setterName(base, accessors.fluent()).equals(call.getNameAsString())) {
                    matches.add(new AccessorMatch(variable, "write"));
                }
            }
        }
        if (matches.isEmpty()) return Optional.empty();

        Map<String, Object> value = baseEvidence("accessor", site.ownerFqn());
        value.put("method", call.getNameAsString());
        value.put("arity", call.getArguments().size());
        if (matches.size() == 1) {
            AccessorMatch match = matches.getFirst();
            value.put("mapping_status", "mapped");
            value.put("operation", match.operation());
            value.put("field", match.variable().getNameAsString());
            value.put("field_id", site.ownerFqn() + "#" + match.variable().getNameAsString());
        } else {
            value.put("mapping_status", "ambiguous");
            value.put("candidates", matches.stream()
                    .map(match -> site.ownerFqn() + "#" + match.variable().getNameAsString())
                    .distinct().sorted().toList());
        }
        return Optional.of(new Evidence(NAMESPACE, value,
                matches.size() == 1
                        ? "lombok_usage_accessor_observed"
                        : "lombok_usage_accessor_ambiguous"));
    }

    private Optional<Evidence> builderEvidence(MethodCallExpr call, FallbackCallSite site,
                                               TypeDeclaration<?> owner) {
        if (!call.getArguments().isEmpty()) return Optional.empty();
        BuilderSettings settings = builderSettings(owner);
        if (settings == null) return Optional.empty();

        boolean staticRoot = settings.builderMethodName().equals(call.getNameAsString())
                && GraphConstants.CallKind.STATIC.equals(site.callKind());
        boolean toBuilder = settings.toBuilder()
                && "toBuilder".equals(call.getNameAsString())
                && !GraphConstants.CallKind.STATIC.equals(site.callKind());
        if (!staticRoot && !toBuilder) return Optional.empty();

        List<MethodCallExpr> chain = chainedCalls(call);
        List<FieldInfo> fields = builderFields(site.ownerFqn(), owner, site.ownerDeclaration(),
                "super_builder".equals(settings.capability()));
        List<Map<String, Object>> steps = new ArrayList<>();
        boolean ambiguous = false;
        boolean unmapped = false;
        for (int index = 0; index < chain.size(); index++) {
            MethodCallExpr step = chain.get(index);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", step.getNameAsString());
            row.put("arity", step.getArguments().size());
            boolean terminal = index == chain.size() - 1
                    && settings.buildMethodName().equals(step.getNameAsString())
                    && step.getArguments().isEmpty();
            row.put("role", terminal ? "terminal" : "property");
            if (!terminal && step.getArguments().size() == 1) {
                String property = builderProperty(step.getNameAsString(), settings.setterPrefix());
                List<FieldInfo> candidates = fields.stream()
                        .filter(field -> field.name().equals(property)).toList();
                if (candidates.size() == 1) {
                    row.put("mapping_status", "mapped");
                    row.put("field", candidates.getFirst().name());
                    row.put("field_id", candidates.getFirst().id());
                } else if (candidates.size() > 1) {
                    ambiguous = true;
                    row.put("mapping_status", "ambiguous");
                    row.put("candidates", candidates.stream().map(FieldInfo::id).sorted().toList());
                } else {
                    unmapped = true;
                    row.put("mapping_status", "unmapped");
                }
            } else if (!terminal) {
                unmapped = true;
                row.put("mapping_status", "unmapped");
            }
            steps.add(row);
        }

        Map<String, Object> value = baseEvidence(settings.capability(), site.ownerFqn());
        value.put("root", call.getNameAsString());
        value.put("builder_type", null);
        value.put("mapping_status", ambiguous ? "ambiguous" : unmapped ? "unmapped" : "mapped");
        value.put("steps", steps);
        return Optional.of(new Evidence(NAMESPACE, value,
                "lombok_usage_builder_chain_observed"));
    }

    private BuilderSettings builderSettings(TypeDeclaration<?> owner) {
        Map<String, AnnotationExpr> typeAnnotations = annotations(owner);
        AnnotationExpr annotation = typeAnnotations.get("SuperBuilder");
        String capability = "super_builder";
        if (annotation == null) {
            annotation = typeAnnotations.get("Builder");
            capability = "builder";
        }
        if (annotation == null && owner instanceof ClassOrInterfaceDeclaration type) {
            for (ConstructorDeclaration constructor : type.getConstructors()) {
                annotation = annotations(constructor).get("Builder");
                if (annotation != null) {
                    capability = "builder";
                    break;
                }
            }
        }
        if (annotation == null) return null;
        return new BuilderSettings(
                capability,
                stringAttribute(annotation, "builderMethodName", "builder"),
                stringAttribute(annotation, "buildMethodName", "build"),
                stringAttribute(annotation, "setterPrefix", ""),
                booleanAttribute(annotation, "toBuilder", false));
    }

    private List<FieldInfo> builderFields(String ownerFqn, TypeDeclaration<?> owner,
                                          ResolvedReferenceTypeDeclaration declaration,
                                          boolean includeAncestors) {
        List<FieldInfo> fields = new ArrayList<>();
        addFields(fields, ownerFqn, owner);
        if (!includeAncestors) return fields;
        try {
            for (var ancestor : declaration.getAllAncestors()) {
                Optional<ResolvedReferenceTypeDeclaration> ancestorDeclaration =
                        ancestor.getTypeDeclaration();
                if (ancestorDeclaration.isEmpty()) continue;
                TypeDeclaration<?> ancestorAst = astType(ancestorDeclaration.get());
                if (ancestorAst == null || !annotations(ancestorAst).containsKey("SuperBuilder")) continue;
                addFields(fields, ancestorDeclaration.get().getQualifiedName(), ancestorAst);
            }
        } catch (RuntimeException ignored) {
            // The raw chain remains useful when a source ancestor cannot be solved.
        }
        return fields;
    }

    private static void addFields(List<FieldInfo> fields, String ownerFqn, TypeDeclaration<?> owner) {
        for (FieldDeclaration field : owner.getFields()) {
            if (field.isStatic()) continue;
            for (VariableDeclarator variable : field.getVariables()) {
                fields.add(new FieldInfo(variable.getNameAsString(),
                        ownerFqn + "#" + variable.getNameAsString()));
            }
        }
    }

    private static List<MethodCallExpr> chainedCalls(MethodCallExpr root) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Node current = root;
        while (current.getParentNode().orElse(null) instanceof MethodCallExpr parent
                && parent.getScope().orElse(null) == current) {
            calls.add(parent);
            current = parent;
        }
        return calls;
    }

    private boolean getterEnabled(FieldDeclaration field,
                                  Map<String, AnnotationExpr> fieldAnnotations,
                                  Map<String, AnnotationExpr> typeAnnotations) {
        AnnotationExpr fieldGetter = fieldAnnotations.get("Getter");
        if (fieldGetter != null) return !accessNone(fieldGetter);
        AnnotationExpr typeGetter = typeAnnotations.get("Getter");
        if (typeGetter != null) return !accessNone(typeGetter);
        return typeAnnotations.containsKey("Data") || typeAnnotations.containsKey("Value");
    }

    private boolean setterEnabled(FieldDeclaration field,
                                  Map<String, AnnotationExpr> fieldAnnotations,
                                  Map<String, AnnotationExpr> typeAnnotations) {
        AnnotationExpr fieldSetter = fieldAnnotations.get("Setter");
        if (fieldSetter != null) return !accessNone(fieldSetter) && !field.isFinal();
        AnnotationExpr typeSetter = typeAnnotations.get("Setter");
        if (typeSetter != null) return !field.isFinal() && !accessNone(typeSetter);
        return !field.isFinal() && !typeAnnotations.containsKey("Value")
                && typeAnnotations.containsKey("Data");
    }

    private String getterName(VariableDeclarator variable, String base, boolean fluent) {
        if (fluent) return base;
        boolean primitiveBoolean = variable.getType().isPrimitiveType()
                && variable.getType().asPrimitiveType().getType()
                == com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN;
        if (primitiveBoolean && !configuration.noIsPrefix()) {
            if (base.length() > 2 && base.startsWith("is")
                    && Character.isUpperCase(base.charAt(2))) return base;
            return "is" + capitalize(base);
        }
        return "get" + capitalize(base);
    }

    private static String setterName(String base, boolean fluent) {
        return fluent ? base : "set" + capitalize(base);
    }

    private static String builderProperty(String method, String setterPrefix) {
        if (setterPrefix == null || setterPrefix.isEmpty()) return method;
        if (!method.startsWith(setterPrefix) || method.length() == setterPrefix.length()) return null;
        return decapitalize(method.substring(setterPrefix.length()));
    }

    private static String logicalBase(String fieldName, List<String> prefixes) {
        if (prefixes.isEmpty()) return fieldName;
        for (String prefix : prefixes) {
            if (prefix.isEmpty()) return fieldName;
            if (!fieldName.startsWith(prefix) || fieldName.length() == prefix.length()) continue;
            char next = fieldName.charAt(prefix.length());
            char last = prefix.charAt(prefix.length() - 1);
            if (Character.isLetter(last) && Character.isLowerCase(next)) continue;
            return decapitalize(fieldName.substring(prefix.length()));
        }
        return null;
    }

    private static Accessors accessors(AnnotationExpr annotation) {
        if (annotation == null) return new Accessors(false, List.of());
        return new Accessors(booleanAttribute(annotation, "fluent", false),
                stringListAttribute(annotation, "prefix"));
    }

    private static boolean explicitMethod(TypeDeclaration<?> owner, MethodCallExpr call) {
        return owner.getMethodsByName(call.getNameAsString()).stream()
                .filter(method -> method.getParameters().size() == call.getArguments().size())
                .anyMatch(method -> SyntheticOrigin.of(method) == null);
    }

    private static boolean accessNone(AnnotationExpr annotation) {
        Expression value = attribute(annotation, "value");
        return value != null && ("NONE".equals(value.toString())
                || value.toString().endsWith(".NONE"));
    }

    private static TypeDeclaration<?> astType(ResolvedReferenceTypeDeclaration declaration) {
        try {
            Optional<Node> ast = declaration.toAst();
            return ast.filter(TypeDeclaration.class::isInstance)
                    .map(node -> (TypeDeclaration<?>) node).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, AnnotationExpr> annotations(NodeWithAnnotations<?> node) {
        Map<String, AnnotationExpr> out = new LinkedHashMap<>();
        for (AnnotationExpr annotation : node.getAnnotations()) {
            String simple = lombokSimpleName(annotation);
            if (simple != null) out.put(simple, annotation);
        }
        return out;
    }

    private static String lombokSimpleName(AnnotationExpr annotation) {
        String name = annotation.getNameAsString();
        if (name.startsWith("lombok.")) return name.substring(name.lastIndexOf('.') + 1);
        CompilationUnit unit = annotation.findCompilationUnit().orElse(null);
        if (unit == null) return null;
        for (var imported : unit.getImports()) {
            String importedName = imported.getNameAsString();
            if (!importedName.startsWith("lombok")) continue;
            if (imported.isAsterisk() || importedName.endsWith("." + name)) return name;
        }
        return null;
    }

    private static String stringAttribute(AnnotationExpr annotation, String name, String fallback) {
        Expression value = attribute(annotation, name);
        return value instanceof StringLiteralExpr string ? string.asString() : fallback;
    }

    private static boolean booleanAttribute(AnnotationExpr annotation, String name, boolean fallback) {
        Expression value = attribute(annotation, name);
        return value instanceof BooleanLiteralExpr bool ? bool.getValue() : fallback;
    }

    private static List<String> stringListAttribute(AnnotationExpr annotation, String name) {
        Expression value = attribute(annotation, name);
        if (value instanceof StringLiteralExpr string) return List.of(string.asString());
        if (!(value instanceof ArrayInitializerExpr array)) return List.of();
        List<String> values = new ArrayList<>();
        for (Expression expression : array.getValues()) {
            if (expression instanceof StringLiteralExpr string) values.add(string.asString());
        }
        return List.copyOf(values);
    }

    private static Expression attribute(AnnotationExpr annotation, String name) {
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream()
                    .filter(pair -> pair.getNameAsString().equals(name))
                    .map(MemberValuePair::getValue).findFirst().orElse(null);
        }
        if ("value".equals(name) && annotation.isSingleMemberAnnotationExpr()) {
            return annotation.asSingleMemberAnnotationExpr().getMemberValue();
        }
        return null;
    }

    private static Map<String, Object> baseEvidence(String capability, String ownerFqn) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", "usage-observed");
        value.put("evidence", "call-site");
        value.put("capability", capability);
        value.put("owner", ownerFqn);
        return value;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String decapitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private record AccessorMatch(VariableDeclarator variable, String operation) {}
    private record Accessors(boolean fluent, List<String> prefixes) {}
    private record FieldInfo(String name, String id) {}
    private record BuilderSettings(String capability, String builderMethodName,
                                   String buildMethodName, String setterPrefix,
                                   boolean toBuilder) {}
}
