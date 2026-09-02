package com.anatomist.framework.lombok;

import com.anatomist.core.IndexDiagnostic;
import com.anatomist.framework.AstModelExtension;
import com.anatomist.framework.ExtensionAstData;
import com.anatomist.framework.ExtensionNodeMetadata;
import com.anatomist.framework.SyntheticOrigin;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.ProducerIds;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.VoidType;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Signature-only Lombok model. It never invokes Lombok or creates method bodies with facts. */
public final class LombokAstModelExtension implements AstModelExtension {
    static final String VERSION = "2";
    private static final Set<String> SUPPORTED = Set.of(
            "Getter", "Setter", "NoArgsConstructor", "RequiredArgsConstructor",
            "AllArgsConstructor", "Data", "Value", "NonNull",
            "Slf4j", "Log", "Log4j", "Log4j2", "XSlf4j", "CommonsLog", "JBossLog", "Flogger");
    private static final Set<String> UNSUPPORTED = Set.of(
            "Builder", "SuperBuilder", "Accessors", "With", "CustomLog", "Cleanup",
            "SneakyThrows", "Synchronized", "Locked", "Delegate", "ExtensionMethod");
    private static final Map<String, String> LOG_TYPES = Map.ofEntries(
            Map.entry("Slf4j", "org.slf4j.Logger"),
            Map.entry("XSlf4j", "org.slf4j.ext.XLogger"),
            Map.entry("Log", "java.util.logging.Logger"),
            Map.entry("Log4j", "org.apache.log4j.Logger"),
            Map.entry("Log4j2", "org.apache.logging.log4j.Logger"),
            Map.entry("CommonsLog", "org.apache.commons.logging.Log"),
            Map.entry("JBossLog", "org.jboss.logging.Logger"),
            Map.entry("Flogger", "com.google.common.flogger.FluentLogger"));

    private final Path projectRoot;
    private final boolean strict;
    private final LombokConfiguration configuration;

    public LombokAstModelExtension(Path projectRoot, boolean strict) {
        this.projectRoot = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        this.strict = strict;
        this.configuration = LombokConfiguration.load(this.projectRoot);
    }

    @Override public String id() { return ProducerIds.LOMBOK_AST; }
    @Override public String producerId() { return ProducerIds.LOMBOK_AST; }
    @Override public String version() { return VERSION; }
    @Override public String fingerprintMaterial() {
        return "strict=" + strict + "|config=" + configuration.fingerprint();
    }

    @Override
    public boolean appliesTo(CompilationUnit unit) {
        if (unit == null) return false;
        boolean importsLombok = unit.getImports().stream()
                .anyMatch(value -> value.getNameAsString().startsWith("lombok"));
        if (!importsLombok) {
            return unit.findAll(AnnotationExpr.class).stream()
                    .map(AnnotationExpr::getNameAsString).anyMatch(name -> name.startsWith("lombok."));
        }
        return unit.findAll(AnnotationExpr.class).stream()
                .anyMatch(annotation -> lombokSimpleName(unit, annotation) != null);
    }

    @Override
    public void augment(CompilationUnit unit) {
        ExtensionAstData.increment(unit, "lombok_files_seen", 1);
        if (configuration.partial()) diagnostic(unit, "LOMBOK_CONFIG_PARTIAL", null,
                "Only project-root signature settings are supported in Lombok AST mode.");
        boolean bodyDiagnostic = false;
        for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) continue;
            Map<String, AnnotationExpr> typeAnnotations = annotations(unit, type.getAnnotations());
            LombokCapabilitySummary typePolicy = summary(unit, type, typeAnnotations);
            boolean data = typeAnnotations.containsKey("Data");
            boolean value = typeAnnotations.containsKey("Value");
            AnnotationExpr typeGetter = typeAnnotations.get("Getter");
            AnnotationExpr typeSetter = typeAnnotations.get("Setter");
            boolean typeAccessors = typeAnnotations.containsKey("Accessors");
            boolean typeGetterEffective = typeGetter != null || data || value;
            boolean typeSetterEffective = typeSetter != null || data;
            if (typeAccessors && typeGetterEffective) typePolicy.partial(LombokCapabilitySummary.GETTER);
            if (typeAccessors && typeSetterEffective) typePolicy.partial(LombokCapabilitySummary.SETTER);
            LombokCapabilitySummary aggregate = new LombokCapabilitySummary();
            aggregate.merge(typePolicy);
            int before = lombokGeneratedMemberCount(type);

            List<FieldDeclaration> originalFields = new ArrayList<>(type.getFields());
            for (FieldDeclaration field : originalFields) {
                Map<String, AnnotationExpr> fieldAnnotations = annotations(unit, field.getAnnotations());
                LombokCapabilitySummary fieldSummary = summary(unit, field, fieldAnnotations);
                boolean fieldAccessors = fieldAnnotations.containsKey("Accessors");
                boolean fieldGetterEffective = fieldAnnotations.containsKey("Getter") || typeGetterEffective;
                boolean fieldSetterEffective = fieldAnnotations.containsKey("Setter") || typeSetterEffective;
                if ((typeAccessors || fieldAccessors) && fieldGetterEffective) {
                    fieldSummary.partial(LombokCapabilitySummary.GETTER);
                }
                if ((typeAccessors || fieldAccessors) && fieldSetterEffective) {
                    fieldSummary.partial(LombokCapabilitySummary.SETTER);
                }
                if (fieldSummary.hasEvidence()) {
                    ExtensionNodeMetadata.put(field, "lombok", fieldSummary.toMap());
                    aggregate.merge(fieldSummary);
                }
                boolean getterBlocked = typePolicy.isPartial(LombokCapabilitySummary.GETTER)
                        || fieldSummary.isPartial(LombokCapabilitySummary.GETTER);
                boolean setterBlocked = typePolicy.isPartial(LombokCapabilitySummary.SETTER)
                        || fieldSummary.isPartial(LombokCapabilitySummary.SETTER);
                for (VariableDeclarator variable : field.getVariables()) {
                    if (field.isStatic()) continue;
                    AnnotationExpr getter = fieldAnnotations.getOrDefault("Getter", typeGetter);
                    AnnotationExpr setter = fieldAnnotations.getOrDefault("Setter", typeSetter);
                    if (!getterBlocked && (getter != null || data || value)) {
                        Access access = access(getter);
                        if (access != Access.NONE) addGetter(type, field, variable, access,
                                getter != null ? getter : typeAnnotations.get(data ? "Data" : "Value"),
                                getter != null ? "@Getter" : data ? "@Data" : "@Value");
                    }
                    if (!setterBlocked && !value && !field.isFinal() && (setter != null || data)) {
                        Access access = access(setter);
                        if (access != Access.NONE) addSetter(type, field, variable, access,
                                setter != null ? setter : typeAnnotations.get("Data"),
                                setter != null ? "@Setter" : "@Data");
                    }
                }
            }

            addConstructors(type, typeAnnotations, originalFields, typePolicy);
            if (data || value) {
                AnnotationExpr source = typeAnnotations.get(data ? "Data" : "Value");
                if (!typePolicy.isPartial(LombokCapabilitySummary.EQUALS_HASH_CODE)) {
                    addObjectMethod(type, "equals", "boolean", List.of(new Parameter(
                            typeOf("java.lang.Object"), "other")), source,
                            data ? "@Data" : "@Value");
                    addObjectMethod(type, "hashCode", "int", List.of(), source,
                            data ? "@Data" : "@Value");
                }
                if (!typePolicy.isPartial(LombokCapabilitySummary.TO_STRING)) {
                    addObjectMethod(type, "toString", "java.lang.String", List.of(), source,
                            data ? "@Data" : "@Value");
                }
                if (value) diagnostic(unit, "LOMBOK_VALUE_MODIFIER_SEMANTICS_OMITTED", type.getNameAsString(),
                        "AST mode recovers generated signatures but does not rewrite explicit field/type modifiers.");
            }
            if (!typePolicy.isPartial(LombokCapabilitySummary.LOGGER_FIELD)) {
                addLogger(type, typeAnnotations);
            }

            int generatedTotal = lombokGeneratedMemberCount(type);
            int generated = Math.max(0, generatedTotal - before);
            if (generated > 0) {
                ExtensionAstData.increment(unit, "lombok_types_augmented", 1);
                ExtensionAstData.increment(unit, "lombok_members_generated", generated);
                bodyDiagnostic = true;
            }
            if (aggregate.hasEvidence()) {
                aggregate.generatedMemberCount(generatedTotal);
                ExtensionNodeMetadata.put(type, "lombok", aggregate.toMap());
            }
        }
        if (bodyDiagnostic) diagnostic(unit, "LOMBOK_BODY_SEMANTICS_OMITTED", null,
                "Generated members contain signature-only placeholder bodies.");
    }

    private void addGetter(ClassOrInterfaceDeclaration owner, FieldDeclaration field,
                           VariableDeclarator variable, Access access, AnnotationExpr source,
                           String generatedFrom) {
        String name = getterName(variable);
        if (hasMethod(owner, name, List.of())) return;
        MethodDeclaration method = owner.addMethod(name, modifiers(access, field.isStatic()));
        method.setType(variable.getType().clone()).setBody(new BlockStmt());
        mark(method, source != null ? source : field, generatedFrom);
    }

    private void addSetter(ClassOrInterfaceDeclaration owner, FieldDeclaration field,
                           VariableDeclarator variable, Access access, AnnotationExpr source,
                           String generatedFrom) {
        String name = "set" + capitalize(variable.getNameAsString());
        String type = normalized(variable.getType());
        if (hasMethod(owner, name, List.of(type))) return;
        MethodDeclaration method = owner.addMethod(name, modifiers(access, field.isStatic()));
        method.setType(new VoidType()).addParameter(variable.getType().clone(), variable.getNameAsString())
                .setBody(new BlockStmt());
        mark(method, source != null ? source : field, generatedFrom);
    }

    private void addConstructors(ClassOrInterfaceDeclaration type,
                                 Map<String, AnnotationExpr> annotations,
                                 List<FieldDeclaration> fields,
                                 LombokCapabilitySummary policy) {
        AnnotationExpr noArgs = annotations.get("NoArgsConstructor");
        AnnotationExpr required = annotations.get("RequiredArgsConstructor");
        AnnotationExpr all = annotations.get("AllArgsConstructor");
        AnnotationExpr data = annotations.get("Data");
        AnnotationExpr value = annotations.get("Value");
        if (noArgs != null && !policy.isPartial(LombokCapabilitySummary.NO_ARGS_CONSTRUCTOR)) {
            addConstructor(type, List.of(), access(noArgs), noArgs, "@NoArgsConstructor");
        }
        if ((required != null || data != null)
                && !policy.isPartial(LombokCapabilitySummary.REQUIRED_CONSTRUCTOR)) {
            AnnotationExpr source = required != null ? required : data;
            List<VariableDeclarator> variables = instanceVariables(fields).stream()
                    .filter(variable -> !variable.getInitializer().isPresent())
                    .filter(variable -> variable.findAncestor(FieldDeclaration.class)
                            .map(FieldDeclaration::isFinal).orElse(false) || hasNonNull(variable))
                    .toList();
            addConstructor(type, variables, access(required), source,
                    required != null ? "@RequiredArgsConstructor" : "@Data");
        }
        if ((all != null || value != null)
                && !policy.isPartial(LombokCapabilitySummary.ALL_ARGS_CONSTRUCTOR)) {
            AnnotationExpr source = all != null ? all : value;
            addConstructor(type, instanceVariables(fields), access(all), source,
                    all != null ? "@AllArgsConstructor" : "@Value");
        }
    }

    private void addConstructor(ClassOrInterfaceDeclaration owner, List<VariableDeclarator> variables,
                                Access access, AnnotationExpr source, String generatedFrom) {
        List<String> parameters = variables.stream().map(variable -> normalized(variable.getType())).toList();
        if (hasConstructor(owner, parameters)) return;
        ConstructorDeclaration constructor = owner.addConstructor(modifiers(access, false));
        for (VariableDeclarator variable : variables) {
            constructor.addParameter(variable.getType().clone(), variable.getNameAsString());
        }
        constructor.setBody(new BlockStmt());
        mark(constructor, source != null ? source : owner, generatedFrom);
    }

    private void addObjectMethod(ClassOrInterfaceDeclaration owner, String name, String returnType,
                                 List<Parameter> parameters, AnnotationExpr source, String generatedFrom) {
        List<String> types = parameters.stream().map(parameter -> normalized(parameter.getType())).toList();
        if (hasMethod(owner, name, types)) return;
        MethodDeclaration method = owner.addMethod(name, Modifier.Keyword.PUBLIC);
        method.setType(typeOf(returnType)).setBody(new BlockStmt());
        parameters.forEach(parameter -> method.addParameter(parameter.clone()));
        mark(method, source != null ? source : owner, generatedFrom);
    }

    private void addLogger(ClassOrInterfaceDeclaration owner, Map<String, AnnotationExpr> annotations) {
        for (Map.Entry<String, String> logger : LOG_TYPES.entrySet()) {
            AnnotationExpr source = annotations.get(logger.getKey());
            if (source == null) continue;
            String fieldName = configuration.logFieldName();
            if (owner.getFields().stream().flatMap(field -> field.getVariables().stream())
                    .anyMatch(variable -> fieldName.equals(variable.getNameAsString()))) return;
            List<Modifier.Keyword> modifiers = new ArrayList<>(List.of(
                    Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL));
            if (configuration.logFieldStatic()) modifiers.add(Modifier.Keyword.STATIC);
            FieldDeclaration field = owner.addField(typeOf(logger.getValue()), fieldName,
                    modifiers.toArray(Modifier.Keyword[]::new));
            mark(field, source, "@" + logger.getKey());
            return;
        }
    }

    private void mark(Node generated, Node source, String generatedFrom) {
        source.getRange().ifPresent(generated::setRange);
        SyntheticOrigin.mark(generated, new SyntheticOrigin(
                ProducerIds.LOMBOK_AST, "lombok", "ast", generatedFrom,
                GraphConstants.Confidence.INFERRED, false));
    }

    private String getterName(VariableDeclarator variable) {
        boolean primitiveBoolean = variable.getType().isPrimitiveType()
                && variable.getType().asPrimitiveType().getType()
                == com.github.javaparser.ast.type.PrimitiveType.Primitive.BOOLEAN;
        return primitiveBoolean && !configuration.noIsPrefix()
                ? "is" + capitalize(variable.getNameAsString())
                : "get" + capitalize(variable.getNameAsString());
    }

    private static boolean hasMethod(ClassOrInterfaceDeclaration owner, String name, List<String> parameters) {
        return owner.getMethodsByName(name).stream().anyMatch(method ->
                method.getParameters().stream().map(parameter -> normalized(parameter.getType())).toList()
                        .equals(parameters));
    }

    private static boolean hasConstructor(ClassOrInterfaceDeclaration owner, List<String> parameters) {
        return owner.getConstructors().stream().anyMatch(constructor ->
                constructor.getParameters().stream().map(parameter -> normalized(parameter.getType())).toList()
                        .equals(parameters));
    }

    private static List<VariableDeclarator> instanceVariables(List<FieldDeclaration> fields) {
        return fields.stream().filter(field -> !field.isStatic())
                .flatMap(field -> field.getVariables().stream()).toList();
    }

    private static boolean hasNonNull(VariableDeclarator variable) {
        return variable.findAncestor(FieldDeclaration.class).stream()
                .flatMap(field -> field.getAnnotations().stream())
                .anyMatch(annotation -> annotation.getNameAsString().endsWith("NonNull"));
    }

    private static String normalized(Type type) {
        String value = type.asString().replace(" ", "");
        int generic = value.indexOf('<');
        if (generic >= 0) value = value.substring(0, generic);
        int dot = value.lastIndexOf('.');
        return dot < 0 ? value : value.substring(dot + 1);
    }

    private static Type typeOf(String name) {
        return switch (name) {
            case "boolean" -> PrimitiveType.booleanType();
            case "int" -> PrimitiveType.intType();
            case "void" -> new VoidType();
            default -> {
                String[] parts = name.split("\\.");
                ClassOrInterfaceType type = new ClassOrInterfaceType(null, parts[0]);
                for (int index = 1; index < parts.length; index++) {
                    type = new ClassOrInterfaceType(type, parts[index]);
                }
                yield type;
            }
        };
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        int first = value.codePointAt(0);
        int title = Character.toTitleCase(first);
        if (first == title) return value;
        return new StringBuilder(value.length())
                .appendCodePoint(title)
                .append(value, Character.charCount(first), value.length())
                .toString();
    }

    private static int lombokGeneratedMemberCount(ClassOrInterfaceDeclaration type) {
        return (int) type.getMembers().stream()
                .map(SyntheticOrigin::of)
                .filter(origin -> origin != null && ProducerIds.LOMBOK_AST.equals(origin.producerId()))
                .count();
    }

    private LombokCapabilitySummary summary(CompilationUnit unit, Node owner,
                                             Map<String, AnnotationExpr> annotations) {
        LombokCapabilitySummary out = new LombokCapabilitySummary();
        for (Map.Entry<String, AnnotationExpr> annotation : annotations.entrySet()) {
            String name = annotation.getKey();
            out.detect(name);
            if (UNSUPPORTED.contains(name)) unsupported(unit, owner, name);
            Set<String> uncertain = uncertainAnnotationCapabilities(name, annotation.getValue());
            if (!uncertain.isEmpty()) {
                uncertain.forEach(out::partial);
                diagnostic(unit, "LOMBOK_SIGNATURE_UNCERTAIN", name,
                        "Unsupported @" + name + " arguments may change generated signatures.");
            }
        }
        out.degrade(configuration.uncertainCapabilities());
        return out;
    }

    private static Set<String> uncertainAnnotationCapabilities(String name, AnnotationExpr annotation) {
        boolean supported = switch (name) {
            case "Getter", "Setter", "NoArgsConstructor", "RequiredArgsConstructor",
                 "AllArgsConstructor" -> accessArgumentsSupported(annotation);
            case "Data", "Value" -> markerOrEmpty(annotation);
            default -> true;
        };
        if (supported) return Set.of();
        return switch (name) {
            case "Getter" -> Set.of(LombokCapabilitySummary.GETTER);
            case "Setter" -> Set.of(LombokCapabilitySummary.SETTER);
            case "NoArgsConstructor" -> Set.of(LombokCapabilitySummary.NO_ARGS_CONSTRUCTOR);
            case "RequiredArgsConstructor" -> Set.of(LombokCapabilitySummary.REQUIRED_CONSTRUCTOR);
            case "AllArgsConstructor" -> Set.of(LombokCapabilitySummary.ALL_ARGS_CONSTRUCTOR);
            case "Data" -> Set.of(LombokCapabilitySummary.REQUIRED_CONSTRUCTOR);
            case "Value" -> Set.of(LombokCapabilitySummary.ALL_ARGS_CONSTRUCTOR);
            default -> Set.of();
        };
    }

    private static boolean markerOrEmpty(AnnotationExpr annotation) {
        return annotation.isMarkerAnnotationExpr()
                || annotation instanceof NormalAnnotationExpr normal && normal.getPairs().isEmpty();
    }

    private static boolean accessArgumentsSupported(AnnotationExpr annotation) {
        if (annotation.isMarkerAnnotationExpr()) return true;
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return accessValueSupported(single.getMemberValue());
        }
        if (annotation instanceof NormalAnnotationExpr normal) {
            return normal.getPairs().stream().allMatch(pair ->
                    Set.of("value", "access").contains(pair.getNameAsString())
                            && accessValueSupported(pair.getValue()));
        }
        return false;
    }

    private static boolean accessValueSupported(Expression value) {
        String name = value.toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        try {
            Access.valueOf(name.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private Map<String, AnnotationExpr> annotations(CompilationUnit unit,
                                                    List<AnnotationExpr> annotations) {
        Map<String, AnnotationExpr> out = new LinkedHashMap<>();
        for (AnnotationExpr annotation : annotations) {
            String simple = lombokSimpleName(unit, annotation);
            if (simple != null) out.put(simple, annotation);
        }
        return out;
    }

    private static String lombokSimpleName(CompilationUnit unit, AnnotationExpr annotation) {
        String supplied = annotation.getNameAsString();
        if (supplied.startsWith("lombok.")) {
            String simple = supplied.substring(supplied.lastIndexOf('.') + 1);
            return SUPPORTED.contains(simple) || UNSUPPORTED.contains(simple) ? simple : null;
        }
        if (!SUPPORTED.contains(supplied) && !UNSUPPORTED.contains(supplied)) return null;
        boolean imported = unit.getImports().stream().anyMatch(value -> {
            String name = value.getNameAsString();
            return !value.isStatic() && (name.equals("lombok." + supplied)
                    || name.endsWith("." + supplied) && name.startsWith("lombok.")
                    || value.isAsterisk() && ("lombok".equals(name)
                    || expectedPackage(supplied).equals(name)));
        });
        return imported ? supplied : null;
    }

    private static String expectedPackage(String simple) {
        if (LOG_TYPES.containsKey(simple) || "CustomLog".equals(simple)) return switch (simple) {
            case "Slf4j", "XSlf4j", "CustomLog" -> "lombok.extern.slf4j";
            case "Log4j" -> "lombok.extern.log4j";
            case "Log4j2" -> "lombok.extern.log4j";
            case "CommonsLog" -> "lombok.extern.apachecommons";
            case "JBossLog" -> "lombok.extern.jbosslog";
            case "Flogger" -> "lombok.extern.flogger";
            case "Log" -> "lombok.extern.java";
            default -> "lombok";
        };
        return "lombok";
    }

    private static Access access(AnnotationExpr annotation) {
        if (annotation == null) return Access.PUBLIC;
        Expression value = null;
        if (annotation instanceof SingleMemberAnnotationExpr single) value = single.getMemberValue();
        else if (annotation instanceof NormalAnnotationExpr normal) {
            value = normal.getPairs().stream().filter(pair -> Set.of("value", "access").contains(pair.getNameAsString()))
                    .map(MemberValuePair::getValue).findFirst().orElse(null);
        }
        if (value == null) return Access.PUBLIC;
        String name = value.toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        try { return Access.valueOf(name.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) { return Access.PUBLIC; }
    }

    private static Modifier.Keyword[] modifiers(Access access, boolean isStatic) {
        List<Modifier.Keyword> out = new ArrayList<>();
        switch (access) {
            case PUBLIC -> out.add(Modifier.Keyword.PUBLIC);
            case PROTECTED -> out.add(Modifier.Keyword.PROTECTED);
            case PRIVATE -> out.add(Modifier.Keyword.PRIVATE);
            case PACKAGE, MODULE, NONE -> { }
        }
        if (isStatic) out.add(Modifier.Keyword.STATIC);
        return out.toArray(Modifier.Keyword[]::new);
    }

    private void unsupported(CompilationUnit unit, Node node, String feature) {
        ExtensionAstData.increment(unit, "lombok_unsupported_features", 1);
        diagnostic(unit, "LOMBOK_FEATURE_UNSUPPORTED", feature,
                "Lombok AST mode does not model @" + feature + ".");
    }

    private void diagnostic(CompilationUnit unit, String code, String symbol, String sample) {
        String source = unit.getStorage().map(storage -> storage.getPath().toString()).orElse(null);
        ExtensionAstData.addDiagnostic(unit, new IndexDiagnostic(
                strict ? "error" : "warning", code, "EXTENSION_AST", source,
                null, null, symbol, 1, sample));
    }

    private enum Access { PUBLIC, PROTECTED, PRIVATE, PACKAGE, MODULE, NONE }
}
