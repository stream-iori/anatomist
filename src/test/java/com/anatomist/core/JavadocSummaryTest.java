package com.anatomist.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavadocSummaryTest {

    @Test
    void extract_firstSentenceWithPeriod() {
        String raw = " * 创建订单，扣减库存并触发支付。\n * \n * 具体流程：先校验库存是否充足。\n";
        assertEquals("创建订单，扣减库存并触发支付。", JavadocSummary.extract(raw));
    }

    @Test
    void extract_firstSentenceWithChinesePeriod() {
        String raw = " * 订单服务。负责编排下单流程。\n";
        assertEquals("订单服务。", JavadocSummary.extract(raw));
    }

    @Test
    void extract_noSentenceTerminator_returnsFullBody() {
        String raw = " * A utility class for price calculations\n";
        assertEquals("A utility class for price calculations", JavadocSummary.extract(raw));
    }

    @Test
    void extract_stripsParamAndReturnTags() {
        String raw = " * Calculate total price.\n * @param items the order items\n * @return total price\n";
        assertEquals("Calculate total price.", JavadocSummary.extract(raw));
    }

    @Test
    void extract_multiLineBody_stopsAtTags() {
        String raw = " * Orchestrates the checkout flow\n * including validation and payment\n * @throws Exception on failure\n";
        assertEquals("Orchestrates the checkout flow including validation and payment", JavadocSummary.extract(raw));
    }

    @Test
    void extract_nullInput() {
        assertNull(JavadocSummary.extract(null));
    }

    @Test
    void extract_emptyInput() {
        assertNull(JavadocSummary.extract(""));
        assertNull(JavadocSummary.extract("   "));
    }

    @Test
    void extract_onlyTags() {
        String raw = " * @param id the order id\n * @return the order\n";
        assertNull(JavadocSummary.extract(raw));
    }

    @Test
    void stripTags_preservesBodyBeforeTags() {
        String raw = " * First line.\n * Second line.\n * @param x desc\n";
        assertEquals("First line. Second line.", JavadocSummary.stripTags(raw));
    }

    @Test
    void extractSummaryFragment_periodFollowedBySpace() {
        assertEquals("Hello world.", JavadocSummary.extractSummaryFragment("Hello world. More text here"));
    }

    @Test
    void extractSummaryFragment_periodAtEnd() {
        assertEquals("Hello world.", JavadocSummary.extractSummaryFragment("Hello world."));
    }

    @Test
    void extractSummaryFragment_noPeriod() {
        assertEquals("No period here", JavadocSummary.extractSummaryFragment("No period here"));
    }

    @Test
    void extract_dotInPackageName_notSentenceEnd() {
        // e.g. "See com.example.Foo for details. More info here."
        // The dot after Foo is NOT followed by space, so it's not a sentence end.
        // The dot after "details" IS followed by space.
        String raw = " * See com.example.Foo for details. More info here.\n";
        assertEquals("See com.example.Foo for details.", JavadocSummary.extract(raw));
    }
}
