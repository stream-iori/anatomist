package com.anatomist.model;

import java.util.List;

/** AST-backed Java declaration persisted independently from graph edges. */
public class Declaration {
    public String symbolId;
    public String qualifiedName;
    public String label;
    public String kind;
    public String declarationKind;
    public String typeKind;
    public String visibility;
    public List<String> modifiers = List.of();
    public List<String> declaredModifiers = List.of();
    public List<String> implicitModifiers = List.of();
    public String declaringType;
    public String sourceFile;
    public String sourceLocation;
    public String module;
    public String scope;
    public int nestingDepth;
    public boolean directMember;
    public boolean synthetic;
    public boolean bindingResolved;
    public String producerId;
}
