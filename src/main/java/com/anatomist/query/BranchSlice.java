package com.anatomist.query;

import java.util.ArrayList;
import java.util.List;

public class BranchSlice {
    public String owner;
    public String ownerLabel;
    public String context;
    public String branchKind;
    public Integer branchLine;
    public String sourceFile;
    public SourceWindow sourceWindow;
    public List<EdgeRow> calls = new ArrayList<>();
    public List<EdgeRow> reads = new ArrayList<>();
    public List<EdgeRow> writes = new ArrayList<>();
}
