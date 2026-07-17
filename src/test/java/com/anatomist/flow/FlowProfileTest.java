package com.anatomist.flow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowProfileTest {

    @Test
    void matchesMethodPackageAndSourceScopesWithoutRegex() {
        FlowProfile method = new FlowProfile(FlowProfile.Mode.SCOPED,
                List.of("method:p.OrderService#run*"));
        assertTrue(method.detailed(".::MAIN::p.OrderService#run(java.lang.String)",
                "src/main/java/p/OrderService.java"));
        assertFalse(method.detailed(".::MAIN::p.Other#run()",
                "src/main/java/p/Other.java"));

        FlowProfile packages = new FlowProfile(FlowProfile.Mode.SCOPED,
                List.of("package:p.payment.**"));
        assertTrue(packages.detailed(".::MAIN::p.payment.Pay#send()", "Pay.java"));

        FlowProfile source = new FlowProfile(FlowProfile.Mode.SCOPED,
                List.of("source:module/src/main/java/p/**"));
        assertTrue(source.detailed("p.Pay#send()", "module/src/main/java/p/Pay.java"));
    }

    @Test
    void validatesModeAndSelectorCombinations() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlowProfile(FlowProfile.Mode.SCOPED, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowProfile(FlowProfile.Mode.FULL, List.of("method:p.A#run*")));
        assertThrows(IllegalArgumentException.class,
                () -> new FlowProfile(FlowProfile.Mode.SCOPED, List.of("bad:p.A")));
    }
}
