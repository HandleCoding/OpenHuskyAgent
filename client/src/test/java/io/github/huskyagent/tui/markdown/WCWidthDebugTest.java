package io.github.huskyagent.tui.markdown;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import org.jline.utils.WCWidth;

class WCWidthDebugTest {

    @Test
    @DisplayName("debug WCWidth return values")
    void debugWCWidth() {
        System.out.println("\n=== WCWidth return value debug ===\n");
        
        // ASCII
        System.out.println("ASCII:");
        System.out.println("  'a' = " + WCWidth.wcwidth('a'));
        System.out.println("  'A' = " + WCWidth.wcwidth('A'));
        System.out.println("  '1' = " + WCWidth.wcwidth('1'));
        
        // Chinese
        System.out.println("\nChinese:");
        System.out.println("  '中' = " + WCWidth.wcwidth('中'));
        System.out.println("  '文' = " + WCWidth.wcwidth('文'));
        System.out.println("  '你' = " + WCWidth.wcwidth('你'));
        
        // Japanese
        System.out.println("\nJapanese:");
        System.out.println("  'あ' (hiragana) = " + WCWidth.wcwidth('あ'));
        System.out.println("  'ア' (katakana) = " + WCWidth.wcwidth('ア'));
        
        // Korean
        System.out.println("\nKorean:");
        System.out.println("  '한' = " + WCWidth.wcwidth('한'));
        
        // Emoji (using codepoints)
        System.out.println("\nEmoji (codepoints):");
        System.out.println("  😀 (U+1F600) = " + WCWidth.wcwidth(0x1F600));
        System.out.println("  ✅ (U+2705) = " + WCWidth.wcwidth(0x2705));
        System.out.println("  ❌ (U+274C) = " + WCWidth.wcwidth(0x274C));
        System.out.println("  ❤ (U+2764) = " + WCWidth.wcwidth(0x2764));
        System.out.println("  ⚠ (U+26A0) = " + WCWidth.wcwidth(0x26A0));
        
        // Full-width
        System.out.println("\nFull-width:");
        System.out.println("  'Ａ' = " + WCWidth.wcwidth('Ａ'));
        System.out.println("  '０' = " + WCWidth.wcwidth('０'));
        
        // Zero-width
        System.out.println("\nZero-width:");
        System.out.println("  U+200B (ZWSP) = " + WCWidth.wcwidth(0x200B));
        System.out.println("  U+200D (ZWJ) = " + WCWidth.wcwidth(0x200D));
        System.out.println("  U+FE0F (VS16) = " + WCWidth.wcwidth(0xFE0F));
        
        // Test string width
        System.out.println("\n=== String displayWidth ===\n");
        
        System.out.println("ASCII 'hello': " + InlineRenderer.displayWidth("hello"));
        System.out.println("Chinese '中文': " + InlineRenderer.displayWidth("中文"));
        System.out.println("Japanese 'ああ': " + InlineRenderer.displayWidth("ああ"));
        System.out.println("Mixed 'hello世界': " + InlineRenderer.displayWidth("hello世界"));
        
        // Emoji strings
        System.out.println("\nEmoji strings:");
        System.out.println("  '😀': " + InlineRenderer.displayWidth("😀"));
        System.out.println("  '✅': " + InlineRenderer.displayWidth("✅"));
        System.out.println("  '❌': " + InlineRenderer.displayWidth("❌"));
        
        // Always pass
        assertTrue(true);
    }
}
