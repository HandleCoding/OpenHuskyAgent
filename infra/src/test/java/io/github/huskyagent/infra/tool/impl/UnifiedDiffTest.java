package io.github.huskyagent.infra.tool.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedDiffTest {

    @Test
    void insertionDoesNotMarkUnchangedTailAsChanged() {
        String oldContent = "one\ntwo\nfive\nsix\n";
        String newContent = "one\ntwo\nthree\nfour\nfive\nsix\n";

        String diff = FileUtils.generateDiff(oldContent, newContent, "sample.txt");

        assertTrue(diff.contains("--- a/sample.txt"));
        assertTrue(diff.contains("+++ b/sample.txt"));
        assertTrue(diff.contains("+three"));
        assertTrue(diff.contains("+four"));
        assertFalse(diff.contains("-five"));
        assertFalse(diff.contains("-six"));
    }

    @Test
    void multipleChangesProduceMultipleHunks() {
        String oldContent = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
        String newContent = "A\nb\nc\nd\ne\nf\ng\nh\ni\nJ\n";

        String diff = FileUtils.generateDiff(oldContent, newContent, "sample.txt");

        assertTrue(diff.contains("-a"));
        assertTrue(diff.contains("+A"));
        assertTrue(diff.contains("-j"));
        assertTrue(diff.contains("+J"));
        assertTrue(diff.indexOf("@@") != diff.lastIndexOf("@@"));
    }

    @Test
    void unchangedContentReturnsEmptyDiff() {
        assertEquals("", FileUtils.generateDiff("same\n", "same\n", "sample.txt"));
    }
}
