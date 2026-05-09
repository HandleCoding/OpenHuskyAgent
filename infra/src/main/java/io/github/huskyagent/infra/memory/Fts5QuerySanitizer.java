package io.github.huskyagent.infra.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * FTS5 查询净化器
 *
 * 将用户输入转为安全的 FTS5 MATCH 查询字符串。
 * FTS5 有自己的查询语法（"AND", "OR", "NOT", *, +, {}, (), ^ 等），
 * 直接传入原始用户输入可能导致 OperationalError。
 *
 * 对标 hermes-agent 的 _sanitize_fts5_query()
 */
public final class Fts5QuerySanitizer {

    private static final Pattern QUOTEDPhrase = Pattern.compile("\"[^\"]*\"");
    private static final Pattern FTS_SPECIAL = Pattern.compile("[+{}()\"^]");
    private static final Pattern REPEATED_STAR = Pattern.compile("\\*+");
    private static final Pattern LEADING_STAR = Pattern.compile("(^|\\s)\\*");
    private static final Pattern DANGLING_BOOL_START = Pattern.compile("(?i)^(AND|OR|NOT)\\b\\s*");
    private static final Pattern DANGLING_BOOL_END = Pattern.compile("(?i)\\s+(AND|OR|NOT)\\s*$");
    private static final Pattern HYPHEN_DOT_TERM = Pattern.compile("\\b(\\w+(?:[.-]\\w+)+)\\b");
    private static final String PLACEHOLDER_PREFIX = "___PH";

    private Fts5QuerySanitizer() {}

    public static String sanitize(String query) {
        if (query == null || query.isBlank()) return "";

        // Step 1: Extract and preserve balanced double-quoted phrases
        List<String> quotedParts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        var matcher = QUOTEDPhrase.matcher(query);
        int lastEnd = 0;
        while (matcher.find()) {
            sb.append(query, lastEnd, matcher.start());
            quotedParts.add(matcher.group());
            sb.append(PLACEHOLDER_PREFIX).append(quotedParts.size() - 1);
            lastEnd = matcher.end();
        }
        sb.append(query.substring(lastEnd));
        String sanitized = sb.toString();

        // Step 2: Strip remaining (unmatched) FTS5-special characters
        sanitized = FTS_SPECIAL.matcher(sanitized).replaceAll(" ");

        // Step 3: Collapse repeated * and remove leading *
        sanitized = REPEATED_STAR.matcher(sanitized).replaceAll("*");
        sanitized = LEADING_STAR.matcher(sanitized).replaceAll("");

        // Step 4: Remove dangling boolean operators at start/end
        sanitized = DANGLING_BOOL_START.matcher(sanitized.trim()).replaceAll("");
        sanitized = DANGLING_BOOL_END.matcher(sanitized.trim()).replaceAll("");

        // Step 5: Wrap unquoted hyphenated/dotted terms in quotes
        sanitized = HYPHEN_DOT_TERM.matcher(sanitized).replaceAll("\"$1\"");

        // Step 6: Restore preserved quoted phrases
        for (int i = 0; i < quotedParts.size(); i++) {
            sanitized = sanitized.replace(PLACEHOLDER_PREFIX + i, quotedParts.get(i));
        }

        return sanitized.trim();
    }
}