package com.anatomist.query;

import java.util.List;

public class SliceResult {
    public String level;
    public List<BlockResult> blocks;

    public SliceResult(String level, List<BlockResult> blocks) {
        this.level = level;
        this.blocks = blocks;
    }
}
