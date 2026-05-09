package io.github.huskyagent.domain.prompt;

public abstract class AbstractPromptSection implements PromptSection {

    private boolean enabled = true;

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    protected String buildWithTitle(String title, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(title).append("\n\n");
        sb.append(content.trim());
        sb.append("\n\n");
        return sb.toString();
    }

    protected String buildWithTag(String tag, String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<").append(tag).append(">\n");
        sb.append(content.trim());
        sb.append("\n</").append(tag).append(">\n\n");
        return sb.toString();
    }
}