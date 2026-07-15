package com.anatomist.core;

import com.anatomist.store.FileCacheService;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.stmt.BlockStmt;

/** Stable source-contract fingerprint excluding executable bodies and comments. */
public final class JavaContractFingerprint {
    private JavaContractFingerprint() {}

    public static String of(CompilationUnit source) {
        CompilationUnit copy = source.clone();
        copy.getAllContainedComments().forEach(Comment::remove);
        copy.walk(Node.TreeTraversal.PREORDER, node -> node.setComment(null));
        copy.findAll(MethodDeclaration.class).forEach(method -> method.setBody(null));
        copy.findAll(ConstructorDeclaration.class)
                .forEach(constructor -> constructor.setBody(new BlockStmt()));
        copy.findAll(CompactConstructorDeclaration.class)
                .forEach(constructor -> constructor.setBody(new BlockStmt()));
        copy.findAll(InitializerDeclaration.class).forEach(Node::remove);
        copy.findAll(VariableDeclarator.class).forEach(VariableDeclarator::removeInitializer);
        copy.findAll(EnumConstantDeclaration.class).forEach(constant -> constant.getArguments().clear());
        return FileCacheService.sha256OfString(copy.toString());
    }
}
