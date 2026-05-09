package io.github.huskyagent.service.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.huskyagent.infra.channel.ApprovalPrompt;
import io.github.huskyagent.infra.channel.ClarifyPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class FeishuCardRenderer {

    private final ObjectMapper objectMapper;

    String markdownCardContent(String text) throws Exception {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("body", Map.of("elements", List.of(
                Map.of("tag", "markdown", "content", text != null ? text : "")
        )));
        return objectMapper.writeValueAsString(card);
    }

    String markdownTableCardContent(String text) throws Exception {
        List<MarkdownBlock> blocks = markdownBlocks(text != null ? text : "");
        if (blocks.stream().noneMatch(block -> block.table() != null)) {
            return null;
        }
        List<Map<String, Object>> elements = new ArrayList<>();
        for (MarkdownBlock block : blocks) {
            if (block.table() != null) {
                elements.add(tableElement(block.table()));
            } else if (block.code() != null && !block.code().isBlank()) {
                elements.add(Map.of(
                        "tag", "div",
                        "text", Map.of("tag", "lark_md", "content", block.code())
                ));
            } else if (block.text() != null && !block.text().isBlank()) {
                elements.add(Map.of(
                        "tag", "div",
                        "text", Map.of("tag", "lark_md", "content", block.text())
                ));
            }
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true, "enable_forward", true));
        card.put("elements", elements);
        return objectMapper.writeValueAsString(card);
    }

    String approvalCardContent(ApprovalPrompt prompt, String status) throws Exception {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true, "enable_forward", false, "update_multi", true));
        card.put("header", Map.of(
                "template", approvalTemplate(status),
                "title", Map.of("tag", "plain_text", "content", approvalTitle(status))
        ));
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of(
                "tag", "div",
                "text", Map.of("tag", "lark_md", "content", approvalMarkdown(prompt, status))
        ));
        if ("pending".equals(status)) {
            elements.add(Map.of(
                    "tag", "action",
                    "layout", "bisected",
                    "actions", List.of(
                            approvalButton("Approve Once", "primary", prompt.getRequestId(), "approve"),
                            approvalButton("Always Allow", "default", prompt.getRequestId(), "always"),
                            approvalButton("Reject", "danger", prompt.getRequestId(), "reject")
                    )
            ));
        }
        card.put("elements", elements);
        return objectMapper.writeValueAsString(card);
    }

    String clarifyCardContent(ClarifyPrompt prompt, String status, String answer) throws Exception {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true, "enable_forward", false, "update_multi", true));
        card.put("header", Map.of(
                "template", clarifyTemplate(status),
                "title", Map.of("tag", "plain_text", "content", clarifyTitle(status))
        ));
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(Map.of(
                "tag", "div",
                "text", Map.of("tag", "lark_md", "content", clarifyMarkdown(prompt, status, answer))
        ));
        if ("pending".equals(status)) {
            List<String> options = prompt.getOptions() != null ? prompt.getOptions() : List.of();
            if (!options.isEmpty()) {
                List<Map<String, Object>> actions = new ArrayList<>();
                for (String option : options.stream().limit(4).toList()) {
                    actions.add(clarifyButton(limit(option, 40), "default", prompt.getRequestId(), option));
                }
                elements.add(Map.of(
                        "tag", "action",
                        "actions", actions
                ));
            }
        }
        card.put("elements", elements);
        return objectMapper.writeValueAsString(card);
    }

    List<String> splitByTableLimit(String text, int maxTables) {
        if (text == null || text.isBlank()) {
            return List.of(text != null ? text : "");
        }
        List<String> chunks = new ArrayList<>();
        List<String> currentLines = new ArrayList<>();
        List<String> pendingTableLines = new ArrayList<>();
        int tableCount = 0;
        boolean inCodeBlock = false;

        for (String line : text.split("\\R", -1)) {
            String stripped = line.strip();
            if (stripped.startsWith("```")) {
                if (!inCodeBlock) {
                    flushPendingTable(currentLines, pendingTableLines);
                }
                inCodeBlock = !inCodeBlock;
                currentLines.add(line);
                continue;
            }
            if (inCodeBlock) {
                currentLines.add(line);
                continue;
            }
            if (stripped.startsWith("|")) {
                pendingTableLines.add(line);
            } else {
                if (!pendingTableLines.isEmpty()) {
                    if (tableCount >= maxTables) {
                        String chunk = String.join("\n", currentLines).strip();
                        if (!chunk.isBlank()) {
                            chunks.add(chunk);
                        }
                        currentLines.clear();
                        tableCount = 0;
                    }
                    flushPendingTable(currentLines, pendingTableLines);
                    tableCount++;
                }
                currentLines.add(line);
            }
        }
        if (!pendingTableLines.isEmpty()) {
            if (tableCount >= maxTables) {
                String chunk = String.join("\n", currentLines).strip();
                if (!chunk.isBlank()) {
                    chunks.add(chunk);
                }
                currentLines.clear();
            }
            flushPendingTable(currentLines, pendingTableLines);
        }
        String last = String.join("\n", currentLines).strip();
        if (!last.isBlank()) {
            chunks.add(last);
        }
        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    String stripMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("```[\\s\\S]*?```", "[code]")
                .replaceAll("`([^`]*)`", "$1")
                .replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1 ($2)")
                .replaceAll("[*_~#>]", "")
                .replace("\r\n", "\n");
    }

    // ── Approval helpers ────────────────────────────────────────────────────

    private String approvalMarkdown(ApprovalPrompt prompt, String status) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Tool:** ").append(prompt.getToolName() != null ? prompt.getToolName() : "unknown").append("\n");
        if (prompt.getReason() != null && !prompt.getReason().isBlank()) {
            sb.append("**Reason:** ").append(limit(prompt.getReason(), 500)).append("\n");
        }
        if (prompt.getAgentText() != null && !prompt.getAgentText().isBlank()) {
            String agentText = prompt.getAgentText().trim();
            String firstLine = agentText.lines().filter(l -> !l.isBlank()).findFirst().orElse("");
            String plain = firstLine.replaceAll("[`*#>_~]", "").trim();
            if (!plain.isBlank()) {
                sb.append("**Agent:** ").append(limit(plain, 200)).append("\n");
            }
        }
        if (prompt.getToolArgs() != null && !prompt.getToolArgs().isBlank()) {
            sb.append("**Args:** `").append(limit(escapeInlineCode(prompt.getToolArgs()), 1200)).append("`");
        }
        if (!"pending".equals(status)) {
            sb.append("\n\n**Status:** ").append(approvalTitle(status));
        }
        return sb.toString();
    }

    private Map<String, Object> approvalButton(String text, String type, String requestId, String decision) {
        return Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", text),
                "type", type,
                "value", Map.of(
                        "kind", "husky_approval",
                        "requestId", requestId,
                        "decision", decision
                )
        );
    }

    private String approvalTitle(String status) {
        return switch (status) {
            case "approved" -> "Tool Approved";
            case "always" -> "Tool Always Allowed";
            case "rejected" -> "Tool Rejected";
            case "timeout" -> "Tool Approval Timed Out";
            default -> "Tool Approval Required";
        };
    }

    private String approvalTemplate(String status) {
        return switch (status) {
            case "approved", "always" -> "green";
            case "rejected", "timeout" -> "red";
            default -> "orange";
        };
    }

    // ── Clarify helpers ──────────────────────────────────────────────────────

    private String clarifyMarkdown(ClarifyPrompt prompt, String status, String answer) {
        StringBuilder sb = new StringBuilder();
        String question = prompt.getQuestion() != null ? prompt.getQuestion() : "";
        sb.append("**Question:** ").append(limit(question, 1000));
        if (prompt.getAgentText() != null && !prompt.getAgentText().isBlank()) {
            String firstLine = prompt.getAgentText().trim().lines().filter(l -> !l.isBlank()).findFirst().orElse("");
            String plain = firstLine.replaceAll("[`*#>_~]", "").trim();
            if (!plain.isBlank()) {
                sb.append("\n**Agent:** ").append(limit(plain, 200));
            }
        }
        if (prompt.getOptions() != null && !prompt.getOptions().isEmpty()) {
            sb.append("\n\n");
            int index = 1;
            for (String option : prompt.getOptions().stream().limit(4).toList()) {
                sb.append(index++).append(". ").append(limit(option, 300)).append("\n");
            }
        } else if ("pending".equals(status)) {
            sb.append("\n\nPlease reply to this message with your answer.");
        }
        if (!"pending".equals(status)) {
            sb.append("\n**Status:** ").append(clarifyTitle(status));
            if (answer != null && !answer.isBlank()) {
                sb.append("\n**Answer:** ").append(limit(answer, 1000));
            }
        }
        return sb.toString();
    }

    private Map<String, Object> clarifyButton(String text, String type, String requestId, String answer) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "husky_clarify");
        value.put("requestId", requestId);
        if (answer != null) {
            value.put("answer", answer);
        }
        return Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", text),
                "type", type,
                "value", value
        );
    }

    private String clarifyTitle(String status) {
        return switch (status) {
            case "answered" -> "Clarification Answered";
            case "timeout" -> "Clarification Timed Out";
            default -> "Clarification Needed";
        };
    }

    private String clarifyTemplate(String status) {
        return switch (status) {
            case "answered" -> "green";
            case "timeout" -> "red";
            default -> "blue";
        };
    }

    // ── Markdown block/table parsing ─────────────────────────────────────────

    private List<MarkdownBlock> markdownBlocks(String text) {
        List<MarkdownBlock> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        List<String> tableLines = new ArrayList<>();
        boolean inCodeBlock = false;
        for (String line : text.split("\\R", -1)) {
            String stripped = line.strip();
            boolean fence = stripped.startsWith("```");
            if (fence) {
                if (!inCodeBlock) {
                    flushMarkdownBlock(blocks, current);
                    flushMarkdownTableBlock(blocks, tableLines);
                    current.add(line);
                    inCodeBlock = true;
                } else {
                    current.add(line);
                    flushCodeBlock(blocks, current);
                    inCodeBlock = false;
                }
                continue;
            }
            if (!inCodeBlock && stripped.startsWith("|")) {
                flushMarkdownBlock(blocks, current);
                tableLines.add(line);
            } else {
                flushMarkdownTableBlock(blocks, tableLines);
                current.add(line);
            }
        }
        flushMarkdownTableBlock(blocks, tableLines);
        flushMarkdownBlock(blocks, current);
        return blocks;
    }

    private void flushCodeBlock(List<MarkdownBlock> blocks, List<String> current) {
        if (current.isEmpty()) {
            return;
        }
        String segment = String.join("\n", current).stripTrailing();
        current.clear();
        if (!segment.isBlank()) {
            blocks.add(new MarkdownBlock(null, null, segment));
        }
    }

    private void flushMarkdownTableBlock(List<MarkdownBlock> blocks, List<String> tableLines) {
        if (tableLines.isEmpty()) {
            return;
        }
        MarkdownTable table = parseMarkdownTable(tableLines);
        if (table != null) {
            blocks.add(new MarkdownBlock(null, table, null));
        } else {
            blocks.add(new MarkdownBlock(String.join("\n", tableLines), null, null));
        }
        tableLines.clear();
    }

    private void flushMarkdownBlock(List<MarkdownBlock> blocks, List<String> current) {
        if (current.isEmpty()) {
            return;
        }
        String segment = String.join("\n", current).strip();
        current.clear();
        if (!segment.isBlank()) {
            blocks.add(new MarkdownBlock(segment, null, null));
        }
    }

    private MarkdownTable parseMarkdownTable(List<String> tableLines) {
        if (tableLines.size() < 3) {
            return null;
        }
        List<String> headers = parsePipeCells(tableLines.get(0));
        List<String> separators = parsePipeCells(tableLines.get(1));
        if (headers == null || separators == null || headers.isEmpty() || separators.size() != headers.size()) {
            return null;
        }
        if (separators.stream().anyMatch(separator -> !separator.replace(" ", "").matches(":?-{3,}:?"))) {
            return null;
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 2; i < tableLines.size(); i++) {
            List<String> cells = parsePipeCells(tableLines.get(i));
            if (cells == null) {
                return null;
            }
            rows.add(normalizeCells(cells, headers.size()));
        }
        return rows.isEmpty() ? null : new MarkdownTable(headers, rows);
    }

    private List<String> parsePipeCells(String line) {
        if (line == null) {
            return null;
        }
        String stripped = line.strip();
        if (!stripped.startsWith("|") || !stripped.endsWith("|")) {
            return null;
        }
        String[] rawCells = stripped.substring(1, stripped.length() - 1).split("\\|", -1);
        List<String> cells = new ArrayList<>();
        for (String rawCell : rawCells) {
            cells.add(rawCell.strip());
        }
        return cells;
    }

    private List<String> normalizeCells(List<String> cells, int size) {
        List<String> normalized = new ArrayList<>(cells.subList(0, Math.min(cells.size(), size)));
        while (normalized.size() < size) {
            normalized.add("");
        }
        return normalized;
    }

    private Map<String, Object> tableElement(MarkdownTable table) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int i = 0; i < table.headers().size(); i++) {
            columns.add(Map.of(
                    "name", "col_" + i,
                    "display_name", table.headers().get(i),
                    "data_type", "lark_md",
                    "width", "auto"
            ));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<String> tableRow : table.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < table.headers().size(); i++) {
                row.put("col_" + i, tableRow.get(i));
            }
            rows.add(row);
        }
        return Map.of(
                "tag", "table",
                "page_size", Math.min(10, Math.max(1, rows.size())),
                "row_height", "low",
                "header_style", Map.of("background_style", "grey", "bold", true),
                "columns", columns,
                "rows", rows
        );
    }

    private void flushPendingTable(List<String> target, List<String> tableLines) {
        target.addAll(tableLines);
        tableLines.clear();
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private String escapeInlineCode(String value) {
        return value.replace("`", "'").replace("\r\n", "\\n").replace("\n", "\\n");
    }

    private record MarkdownBlock(String text, MarkdownTable table, String code) {
    }

    private record MarkdownTable(List<String> headers, List<List<String>> rows) {
    }
}