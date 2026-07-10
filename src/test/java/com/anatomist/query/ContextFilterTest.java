package com.anatomist.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextFilterTest {

    @Test
    void loopAndBranchFlagsAreCombinedWithAnd() {
        EdgeRow loop = edge("for@L1");
        EdgeRow branch = edge("if-then@L2");
        EdgeRow nested = edge("for@L1>if-then@L2");

        assertEquals(List.of(nested), ContextFilter.apply(
                List.of(loop, branch, nested), true, true));
    }

    private static EdgeRow edge(String context) {
        EdgeRow row = new EdgeRow();
        row.context = context;
        return row;
    }
}
