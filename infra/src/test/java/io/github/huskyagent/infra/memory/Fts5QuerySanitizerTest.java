package io.github.huskyagent.infra.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Fts5QuerySanitizerTest {

    @Test
    void basicEnglishPassesThrough() {
        assertEquals("docker deployment", Fts5QuerySanitizer.sanitize("docker deployment"));
        assertEquals("hello", Fts5QuerySanitizer.sanitize("hello"));
    }

    @Test
    void stripsSpecialCharacters() {
        assertEquals("hello world", Fts5QuerySanitizer.sanitize("hello+world"));
        assertEquals("test something", Fts5QuerySanitizer.sanitize("test{something}"));
        assertEquals("query", Fts5QuerySanitizer.sanitize("(query)"));
        assertEquals("search", Fts5QuerySanitizer.sanitize("^search"));
    }

    @Test
    void preservesQuotedPhrases() {
        assertEquals("\"exact phrase\"", Fts5QuerySanitizer.sanitize("\"exact phrase\""));
        assertEquals("\"chat send\"  other", Fts5QuerySanitizer.sanitize("\"chat send\" +other"));
    }

    @Test
    void wrapsHyphenatedTerms() {
        assertEquals("\"chat-send\"", Fts5QuerySanitizer.sanitize("chat-send"));
        assertEquals("\"P2.2\"", Fts5QuerySanitizer.sanitize("P2.2"));
        assertEquals("\"my-app.config\"", Fts5QuerySanitizer.sanitize("my-app.config"));
    }

    @Test
    void collapsesRepeatedStar() {
        assertEquals("deploy*", Fts5QuerySanitizer.sanitize("deploy***"));
    }

    @Test
    void removesLeadingStar() {
        assertEquals("search", Fts5QuerySanitizer.sanitize("*search"));
    }

    @Test
    void removesDanglingBooleanOperators() {
        assertEquals("hello", Fts5QuerySanitizer.sanitize("AND hello"));
        assertEquals("world", Fts5QuerySanitizer.sanitize("world OR"));
        assertEquals("test", Fts5QuerySanitizer.sanitize("NOT test"));
    }

    @Test
    void emptyOrNullReturnsEmpty() {
        assertEquals("", Fts5QuerySanitizer.sanitize(null));
        assertEquals("", Fts5QuerySanitizer.sanitize(""));
        assertEquals("", Fts5QuerySanitizer.sanitize("   "));
    }

    @Test
    void chineseQueryPassesThrough() {
        // Chinese text has no FTS5 special chars, passes through unchanged
        assertEquals("编程", Fts5QuerySanitizer.sanitize("编程"));
        assertEquals("启动 服务", Fts5QuerySanitizer.sanitize("启动 服务"));
    }

    @Test
    void mixedContentSanitized() {
        // "exact phrase" is preserved, chat-send gets quoted
        assertEquals("\"exact phrase\" \"chat-send\"", Fts5QuerySanitizer.sanitize("\"exact phrase\" chat-send"));
    }
}