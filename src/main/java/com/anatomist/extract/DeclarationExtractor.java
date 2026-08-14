package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.json.Json;
import com.anatomist.model.Declaration;
import com.anatomist.model.Edge;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.GraphConstants;
import com.anatomist.model.Node;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Enumerates source declarations from the parsed AST; no source-text heuristics. */
public final class DeclarationExtractor implements Extractor {
    private static final List<String> MODIFIER_ORDER = List.of(
            "public", "protected", "private", "abstract", "static", "final",
            "transient", "volatile", "synchronized", "native", "strictfp", "default", "sealed", "non-sealed");

    private final ExtractionContext ctx;

    public DeclarationExtractor(ExtractionContext ctx) { this.ctx = ctx; }

    @Override public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = SourceFiles.of(unit);
        new VoidVisitorAdapter<Void>() {
            @Override public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                emitType(n, sourceFile, result); super.visit(n, arg);
            }
            @Override public void visit(EnumDeclaration n, Void arg) {
                emitType(n, sourceFile, result); super.visit(n, arg);
            }
            @Override public void visit(AnnotationDeclaration n, Void arg) {
                emitType(n, sourceFile, result); super.visit(n, arg);
            }
            @Override public void visit(RecordDeclaration n, Void arg) {
                emitType(n, sourceFile, result); super.visit(n, arg);
            }
            @Override public void visit(MethodDeclaration n, Void arg) {
                emitMethod(n, sourceFile, result); super.visit(n, arg);
            }
            @Override public void visit(ConstructorDeclaration n, Void arg) {
                emitConstructor(n, sourceFile, result); super.visit(n, arg);
            }
            @Override public void visit(CompactConstructorDeclaration n, Void arg) {
                emitCompact(n, sourceFile, result); super.visit(n, arg);
            }
        }.visit(unit, null);
    }

    private void emitType(TypeDeclaration<?> ast, String sourceFile, ExtractionResult result) {
        boolean resolved = true;
        String id;
        try { id = ctx.idGenerator().forType(ast.resolve()); }
        catch (RuntimeException failure) {
            resolved = false;
            id = lexicalTypeId(ast);
        }
        String kind = typeNodeKind(ast);
        String owner = ast.findAncestor(TypeDeclaration.class).map(DeclarationExtractor::lexicalTypeId).orElse(null);
        Declaration declaration = base(id, id, ast.getNameAsString(), kind, "type", sourceFile, ast);
        declaration.typeKind = typeKind(ast);
        declaration.declaringType = owner;
        declaration.nestingDepth = typeDepth(ast);
        declaration.directMember = false;
        declaration.bindingResolved = resolved;
        applyModifiers(declaration, ast, implicitTypeModifiers(ast));
        result.declarations.add(declaration);
        ensureNode(result, declaration, packageName(ast));
    }

    private void emitMethod(MethodDeclaration ast, String sourceFile, ExtractionResult result) {
        TypeDeclaration<?> ownerAst = ast.findAncestor(TypeDeclaration.class).orElse(null);
        if (ownerAst == null) return;
        boolean resolved = true;
        String id;
        try { id = CallableIdFactory.forMethod(ctx.idGenerator(), ast); }
        catch (RuntimeException failure) {
            resolved = false;
            id = lexicalTypeId(ownerAst) + "#" + ast.getNameAsString() + "(" + CallableIdFactory.signature(ast) + ")";
        }
        String owner = ownerFromSymbol(id);
        Declaration declaration = base(id, owner + "#" + ast.getNameAsString(), ast.getNameAsString(),
                GraphConstants.Kind.METHOD, "method", sourceFile, ast);
        declaration.declaringType = owner;
        boolean anonymousOwner = ast.findAncestor(ObjectCreationExpr.class)
                .filter(expression -> expression.getAnonymousClassBody().isPresent()).isPresent();
        declaration.nestingDepth = typeDepth(ownerAst) + (anonymousOwner ? 2 : 1);
        declaration.directMember = !anonymousOwner && typeDepth(ownerAst) == 0;
        declaration.bindingResolved = resolved;
        applyModifiers(declaration, ast, implicitMethodModifiers(ast, ownerAst));
        result.declarations.add(declaration);
        ensureNode(result, declaration, packageName(ast));
        ensureContains(result, owner, id, sourceFile, declaration.sourceLocation);
    }

    private void emitConstructor(ConstructorDeclaration ast, String sourceFile, ExtractionResult result) {
        TypeDeclaration<?> ownerAst = ast.findAncestor(TypeDeclaration.class).orElse(null);
        if (ownerAst == null) return;
        boolean resolved = true;
        String id;
        try { id = CallableIdFactory.forConstructor(ctx.idGenerator(), ast); }
        catch (RuntimeException failure) {
            resolved = false;
            id = lexicalTypeId(ownerAst) + "#" + ast.getNameAsString() + "(" + CallableIdFactory.signature(ast) + ")";
        }
        emitConstructorFact(ast, ownerAst, id, resolved, false, sourceFile, result);
    }

    private void emitCompact(CompactConstructorDeclaration ast, String sourceFile, ExtractionResult result) {
        RecordDeclaration ownerAst = ast.findAncestor(RecordDeclaration.class).orElse(null);
        if (ownerAst == null) return;
        boolean resolved = true;
        String id;
        try { id = CallableIdFactory.forCompactConstructor(ctx.idGenerator(), ast); }
        catch (RuntimeException failure) {
            resolved = false;
            id = lexicalTypeId(ownerAst) + "#" + ownerAst.getNameAsString() + "(" + ownerAst.getParameters().stream()
                    .map(p -> AstTypeNames.of(p.getType(), p)).reduce((a, b) -> a + "," + b).orElse("") + ")";
        }
        emitConstructorFact(ast, ownerAst, id, resolved, true, sourceFile, result);
    }

    private void emitConstructorFact(NodeWithModifiers<?> ast, TypeDeclaration<?> ownerAst, String id,
                                     boolean resolved, boolean compact, String sourceFile, ExtractionResult result) {
        String owner = ownerFromSymbol(id);
        Declaration declaration = base(id, owner + "#" + ownerAst.getNameAsString(), ownerAst.getNameAsString(),
                GraphConstants.Kind.CONSTRUCTOR, "constructor", sourceFile,
                (com.github.javaparser.ast.Node) ast);
        declaration.declaringType = owner;
        declaration.nestingDepth = typeDepth(ownerAst) + 1;
        declaration.directMember = typeDepth(ownerAst) == 0;
        declaration.bindingResolved = resolved;
        applyModifiers(declaration, ast, implicitConstructorModifiers(ownerAst, compact));
        result.declarations.add(declaration);
        ensureNode(result, declaration, packageName((com.github.javaparser.ast.Node) ast));
        ensureContains(result, owner, id, sourceFile, declaration.sourceLocation);
    }

    private Declaration base(String id, String qualifiedName, String label, String kind,
                             String declarationKind, String sourceFile, com.github.javaparser.ast.Node ast) {
        Declaration out = new Declaration();
        out.symbolId = id; out.qualifiedName = qualifiedName; out.label = label; out.kind = kind;
        out.declarationKind = declarationKind; out.sourceFile = sourceFile;
        out.sourceLocation = "L" + declarationLine(ast);
        out.module = ctx.module(); out.scope = ctx.scope();
        return out;
    }

    private static int declarationLine(com.github.javaparser.ast.Node ast) {
        if (ast instanceof TypeDeclaration<?> type) return type.getName().getBegin().map(p -> p.line).orElse(0);
        if (ast instanceof MethodDeclaration method) return method.getName().getBegin().map(p -> p.line).orElse(0);
        if (ast instanceof ConstructorDeclaration constructor) return constructor.getName().getBegin().map(p -> p.line).orElse(0);
        if (ast instanceof CompactConstructorDeclaration compact) return compact.getName().getBegin().map(p -> p.line).orElse(0);
        return ast.getBegin().map(p -> p.line).orElse(0);
    }

    private static void applyModifiers(Declaration declaration, NodeWithModifiers<?> ast, List<String> implicit) {
        List<String> declared = ast.getModifiers().stream().map(m -> keyword(m.getKeyword())).toList();
        LinkedHashSet<String> effective = new LinkedHashSet<>(declared);
        effective.addAll(implicit);
        declaration.declaredModifiers = ordered(declared);
        declaration.implicitModifiers = ordered(implicit.stream().filter(m -> !declared.contains(m)).toList());
        declaration.modifiers = ordered(new ArrayList<>(effective));
        declaration.visibility = declaration.modifiers.stream()
                .filter(Set.of("public", "protected", "private")::contains).findFirst().orElse("package");
    }

    private static List<String> implicitTypeModifiers(TypeDeclaration<?> ast) {
        List<String> out = new ArrayList<>();
        TypeDeclaration<?> owner = ast.findAncestor(TypeDeclaration.class).orElse(null);
        if (ast instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()
                || ast instanceof AnnotationDeclaration) out.add("abstract");
        if (ast instanceof RecordDeclaration) out.add("final");
        if (ast instanceof EnumDeclaration declaration
                && declaration.getEntries().stream().allMatch(entry -> entry.getClassBody().isEmpty())) out.add("final");
        if (owner != null && (ast instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()
                || ast instanceof AnnotationDeclaration || ast instanceof EnumDeclaration
                || ast instanceof RecordDeclaration)) out.add("static");
        if (owner instanceof ClassOrInterfaceDeclaration c && c.isInterface()
                || owner instanceof AnnotationDeclaration) {
            out.add("public"); out.add("static");
        }
        return out;
    }

    private static List<String> implicitMethodModifiers(MethodDeclaration ast, TypeDeclaration<?> owner) {
        if (!(owner instanceof ClassOrInterfaceDeclaration c && c.isInterface())
                && !(owner instanceof AnnotationDeclaration)) return List.of();
        List<String> out = new ArrayList<>();
        if (!ast.isPrivate()) out.add("public");
        boolean concrete = ast.isDefault() || ast.isStatic() || ast.isPrivate() || ast.getBody().isPresent();
        if (!concrete) out.add("abstract");
        return out;
    }

    private static List<String> implicitConstructorModifiers(TypeDeclaration<?> owner, boolean compact) {
        if (owner instanceof EnumDeclaration) return List.of("private");
        if (compact && owner instanceof RecordDeclaration record) {
            for (Modifier modifier : record.getModifiers()) {
                String value = keyword(modifier.getKeyword());
                if (Set.of("public", "protected", "private").contains(value)) return List.of(value);
            }
        }
        return List.of();
    }

    private static String keyword(Modifier.Keyword keyword) {
        return keyword.asString();
    }

    private static List<String> ordered(List<String> values) {
        return values.stream().distinct().sorted((a, b) -> {
            int ai = MODIFIER_ORDER.indexOf(a), bi = MODIFIER_ORDER.indexOf(b);
            if (ai < 0) ai = Integer.MAX_VALUE; if (bi < 0) bi = Integer.MAX_VALUE;
            int compared = Integer.compare(ai, bi); return compared != 0 ? compared : a.compareTo(b);
        }).toList();
    }

    private static int typeDepth(TypeDeclaration<?> ast) {
        int depth = 0;
        com.github.javaparser.ast.Node cursor = ast;
        while ((cursor = cursor.getParentNode().orElse(null)) != null) if (cursor instanceof TypeDeclaration<?>) depth++;
        return depth;
    }

    static String lexicalTypeId(TypeDeclaration<?> ast) {
        List<String> names = new ArrayList<>();
        com.github.javaparser.ast.Node cursor = ast;
        while (cursor != null) {
            if (cursor instanceof TypeDeclaration<?> type) names.add(0, type.getNameAsString());
            cursor = cursor.getParentNode().orElse(null);
        }
        String pkg = packageName(ast);
        return (pkg == null || pkg.isBlank() ? "" : pkg + ".") + String.join(".", names);
    }

    private static String ownerFromSymbol(String symbol) {
        int hash = symbol.lastIndexOf('#');
        return hash < 0 ? symbol : symbol.substring(0, hash);
    }

    private static String packageName(com.github.javaparser.ast.Node ast) {
        return ast.findCompilationUnit().flatMap(CompilationUnit::getPackageDeclaration)
                .map(p -> p.getNameAsString()).orElse("");
    }

    private static String typeNodeKind(TypeDeclaration<?> ast) {
        if (ast instanceof AnnotationDeclaration) return GraphConstants.Kind.ANNOTATION;
        if (ast instanceof EnumDeclaration) return GraphConstants.Kind.ENUM;
        if (ast instanceof RecordDeclaration) return GraphConstants.Kind.RECORD;
        if (ast instanceof ClassOrInterfaceDeclaration c && c.isInterface()) return GraphConstants.Kind.INTERFACE;
        return GraphConstants.Kind.CLASS;
    }

    private static String typeKind(TypeDeclaration<?> ast) {
        return switch (typeNodeKind(ast)) {
            case GraphConstants.Kind.ANNOTATION -> "annotation";
            case GraphConstants.Kind.ENUM -> "enum";
            case GraphConstants.Kind.RECORD -> "record";
            case GraphConstants.Kind.INTERFACE -> "interface";
            default -> "class";
        };
    }

    private static void ensureNode(ExtractionResult result, Declaration declaration, String pkg) {
        Node existing = result.nodes.stream().filter(n -> declaration.symbolId.equals(n.id)).findFirst().orElse(null);
        if (existing != null) {
            if (GraphConstants.Kind.CONSTRUCTOR.equals(declaration.kind)) existing.kind = GraphConstants.Kind.CONSTRUCTOR;
            return;
        }
        Node node = new Node();
        node.id = declaration.symbolId; node.label = declaration.label; node.kind = declaration.kind;
        node.qualifiedName = declaration.qualifiedName; node.pkg = pkg; node.sourceFile = declaration.sourceFile;
        node.sourceLocation = declaration.sourceLocation; node.module = declaration.module; node.scope = declaration.scope;
        node.metadata = Json.writeCompact(Map.of("bindingResolved", declaration.bindingResolved));
        result.nodes.add(node);
    }

    private static void ensureContains(ExtractionResult result, String owner, String member,
                                       String sourceFile, String sourceLocation) {
        boolean exists = result.edges.stream().anyMatch(e -> owner.equals(e.sourceId)
                && member.equals(e.targetId) && GraphConstants.Relation.CONTAINS.equals(e.relation));
        if (exists) return;
        Edge edge = new Edge(); edge.sourceId = owner; edge.targetId = member;
        edge.relation = GraphConstants.Relation.CONTAINS; edge.confidence = GraphConstants.Confidence.EXTRACTED;
        edge.sourceFile = sourceFile; edge.sourceLocation = sourceLocation; result.edges.add(edge);
    }
}
