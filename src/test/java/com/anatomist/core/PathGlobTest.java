package com.anatomist.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PathGlobTest {

    @Test
    void doubleStarCrossesDirectoriesButSingleStarDoesNot() {
        assertTrue(new PathGlob("src/**/A.java").matches("src/main/java/A.java"));
        assertTrue(new PathGlob("**/*.java").matches("A.java"));
        assertFalse(new PathGlob("src/*/A.java").matches("src/main/java/A.java"));
    }

    @Test
    void questionMarkMatchesOneCharacter() {
        assertTrue(new PathGlob("src/A?.java").matches("src/Ab.java"));
        assertFalse(new PathGlob("src/A?.java").matches("src/A.java"));
    }
}
