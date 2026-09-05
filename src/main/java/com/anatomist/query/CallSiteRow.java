package com.anatomist.query;

import java.util.ArrayList;
import java.util.List;

/** Canonical source call site with zero or more static resolution targets. */
public final class CallSiteRow {
    public String id;
    public String callerId;
    public String sourceFile;
    public int beginLine;
    public int beginColumn;
    public int endLine;
    public int endColumn;
    public int ordinal;
    public String syntaxTarget;
    public String receiverStaticType;
    public String dispatchKind;
    public String origin;
    public String resolutionStatus;
    public String producerId;
    public final List<Target> targets = new ArrayList<>();

    public record Target(String id, String qualifiedName, boolean external,
                         String resolutionStatus, String confidence,
                         String producerId) {}
}
