package io.github.huskyagent.infra.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CJKDetectorTest {

    @Test
    void pureEnglishReturnsFalse() {
        assertFalse(CJKDetector.containsCJK("hello world"));
        assertFalse(CJKDetector.containsCJK("docker deployment"));
        assertFalse(CJKDetector.containsCJK(""));
        assertFalse(CJKDetector.containsCJK(null));
    }

    @Test
    void chineseReturnsTrue() {
        assertTrue(CJKDetector.containsCJK("你好"));
        assertTrue(CJKDetector.containsCJK("Python编程"));
        assertTrue(CJKDetector.containsCJK("启动服务"));
    }

    @Test
    void japaneseReturnsTrue() {
        assertTrue(CJKDetector.containsCJK("こんにちは"));  // Hiragana
        assertTrue(CJKDetector.containsCJK("カタカナ"));    // Katakana
    }

    @Test
    void koreanReturnsTrue() {
        assertTrue(CJKDetector.containsCJK("한국어"));  // Hangul
    }

    @Test
    void mixedEnglishCJKReturnsTrue() {
        assertTrue(CJKDetector.containsCJK("hello 你好"));
        assertTrue(CJKDetector.containsCJK("docker部署服务"));
    }

    @Test
    void cjkSymbolsReturnTrue() {
        assertTrue(CJKDetector.containsCJK("【】"));  // CJK Symbols U+3000 range
    }

    @Test
    void cjkExtensionBReturnsTrue() {
        // 𠮷 (U+20BB7) is in CJK Extension B range
        assertTrue(CJKDetector.containsCJK("𠮷"));
    }
}