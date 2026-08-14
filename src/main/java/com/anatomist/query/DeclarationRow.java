package com.anatomist.query;

import java.util.List;

/** Public JSON contract for declarations-of. */
public class DeclarationRow {
    public String symbolId;
    public String qualifiedName;
    public String label;
    public String kind;
    public String declarationKind;
    public String typeKind;
    public String visibility;
    public List<String> modifiers;
    public List<String> declaredModifiers;
    public List<String> implicitModifiers;
    public String declaringType;
    public String sourceFile;
    public String sourceLocation;
    public String module;
    public String scope;
    public int nestingDepth;
    public boolean directMember;
    public boolean synthetic;
}
