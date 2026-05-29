package com.anatomist.extract;

import com.anatomist.core.ExtractionContext;
import com.anatomist.model.ExtractionResult;
import com.anatomist.model.Node;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TypeExtractor implements Extractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExtractionContext ctx;

    public TypeExtractor(ExtractionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void extract(CompilationUnit unit, ExtractionResult result) {
        if (unit == null) return;
        String sourceFile = relativeSourceFile(unit);
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                emit(node, sourceFile, result);
                return true;
            }

            @Override
            public boolean visit(EnumDeclaration node) {
                emit(node, sourceFile, result);
                return true;
            }
        });
    }

    private void emit(AbstractTypeDeclaration decl, String sourceFile, ExtractionResult result) {
        ITypeBinding binding = decl.resolveBinding();
        if (binding == null) return;
        Node n = new Node();
        n.id = ctx.idGenerator().forType(binding);
        n.label = binding.getName();
        n.kind = kindOf(decl, binding);
        n.qualifiedName = binding.getErasure().getQualifiedName();
        n.pkg = binding.getPackage() == null ? null : binding.getPackage().getName();
        n.sourceFile = sourceFile;
        n.sourceLocation = "L" + lineNumber(decl);
        n.module = ctx.module();
        n.scope = ctx.scope();
        n.javadoc = javadocText(decl);
        n.metadata = metadataJson(decl, binding);
        result.nodes.add(n);
    }

    private static String kindOf(AbstractTypeDeclaration decl, ITypeBinding binding) {
        if (decl instanceof EnumDeclaration) return "ENUM";
        if (binding.isInterface()) return "INTERFACE";
        return "CLASS";
    }

    private static int lineNumber(AbstractTypeDeclaration decl) {
        CompilationUnit cu = (CompilationUnit) decl.getRoot();
        return cu.getLineNumber(decl.getStartPosition());
    }

    private static String javadocText(AbstractTypeDeclaration decl) {
        return decl.getJavadoc() == null ? null : decl.getJavadoc().toString();
    }

    private String relativeSourceFile(CompilationUnit unit) {
        Object prop = unit.getProperty("source_file");
        if (prop instanceof String s) return s;
        return null;
    }

    private static String metadataJson(AbstractTypeDeclaration decl, ITypeBinding binding) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (decl instanceof EnumDeclaration enumDecl) {
            List<String> consts = new ArrayList<>();
            for (Object o : enumDecl.enumConstants()) {
                consts.add(((EnumConstantDeclaration) o).getName().getIdentifier());
            }
            meta.put("constants", consts);
        } else {
            meta.put("isAbstract", java.lang.reflect.Modifier.isAbstract(binding.getModifiers()));
            meta.put("isInterface", binding.isInterface());
            ITypeBinding sc = binding.getSuperclass();
            if (sc != null && !"java.lang.Object".equals(sc.getQualifiedName())) {
                meta.put("superClass", sc.getName());
            }
            ITypeBinding[] ifs = binding.getInterfaces();
            if (ifs.length > 0) {
                List<String> names = new ArrayList<>();
                for (ITypeBinding i : ifs) names.add(i.getName());
                meta.put("interfaces", names);
            }
        }
        try {
            return JSON.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
