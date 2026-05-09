package io.github.huskyagent.infra.tool.match;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FuzzyMatcherTest {

    @Test
    void strictProfileDoesNotUseContextAwareSimilarity() {
        String content = "start\nalpha changed\nbeta changed\nend\n";
        String pattern = "start\nalpha original\nbeta original\nend";

        FuzzyMatcher.MatchResult strict = FuzzyMatcher.findAndReplaceStrict(content, pattern, "replacement", false);
        FuzzyMatcher.MatchResult fuzzy = FuzzyMatcher.findAndReplace(content, pattern, "replacement", false);

        assertNotNull(strict.error());
        assertEquals(0, strict.matchCount());
        assertNull(fuzzy.error());
        assertTrue(List.of("block_anchor", "context_aware").contains(fuzzy.strategy()));
    }

    @Test
    void strictProfileStillAllowsIndentationFlexibleReplacement() {
        String content = "class A {\n    void run() {\n        call();\n    }\n}\n";
        String pattern = "void run() {\n    call();\n}";

        FuzzyMatcher.MatchResult result = FuzzyMatcher.findAndReplaceStrict(content, pattern, "void run() {\n    done();\n}", false);

        assertNull(result.error());
        assertTrue(List.of("line_trimmed", "indentation_flexible").contains(result.strategy()));
        assertTrue(result.newContent().contains("done();"));
    }
}
