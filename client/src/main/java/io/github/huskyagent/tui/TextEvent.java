package io.github.huskyagent.tui;

public record TextEvent(String text, boolean intermediate, String token, boolean reasoning) {

    public static TextEvent ofMessage(String text, boolean intermediate) {
        return new TextEvent(text, intermediate, null, false);
    }

    public static TextEvent ofToken(String token) {
        return new TextEvent(null, false, token, false);
    }

    public static TextEvent ofReasoning(String token) {
        return new TextEvent(null, false, token, true);
    }

    public boolean isTokenEvent() {
        return token != null;
    }
}