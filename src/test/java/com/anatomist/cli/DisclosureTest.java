package com.anatomist.cli;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisclosureTest {

    @Test
    void renderCommandPreservesSafeCharactersAndQuotesUnsafeValues() {
        assertEquals("search abc-DEF_123/.:=@+, 'hello world' 'a'\\''b'",
                Disclosure.renderCommand(List.of(
                        "search", "abc-DEF_123/.:=@+,", "hello world", "a'b")));
    }

    @Test
    void renderCommandQuotesEmptyValue() {
        assertEquals("''", Disclosure.renderCommand(List.of("")));
    }
}
