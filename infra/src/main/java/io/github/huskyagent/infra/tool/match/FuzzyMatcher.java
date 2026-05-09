package io.github.huskyagent.infra.tool.match;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 9-level fuzzy matching strategy chain for find-and-replace operations.
 *
 * Ported from Hermes fuzzy_match.py. Strategies tried in order:
 * 1. exact — literal string match
 * 2. line_trimmed — strip each line's leading/trailing whitespace
 * 3. whitespace_normalized — collapse multiple spaces/tabs to single space
 * 4. indentation_flexible — strip all leading whitespace per line
 * 5. escape_normalized — convert \\n/\\t escape sequences
 * 6. trimmed_boundary — trim first and last line whitespace only
 * 7. unicode_normalized — smart quotes → ASCII equivalents
 * 8. block_anchor — match first+last lines exactly, middle lines by similarity ≥ 0.50
 * 9. context_aware — 50% of lines must have similarity ≥ 0.80
 */
public class FuzzyMatcher {

    public record MatchResult(
        String newContent,
        int matchCount,
        String strategy,
        String error
    ) {}

    private static final Map<Character, String> UNICODE_MAP = Map.of(
        '\u201c', "\"", '\u201d', "\"",   // smart double quotes
        '\u2018', "'", '\u2019', "'",      // smart single quotes
        '\u2014', "--", '\u2013', "-",      // em/en dashes
        '\u2026', "...", '\u00a0', " "      // ellipsis and non-breaking space
    );

    public static MatchResult findAndReplace(String content, String oldString, String newString,
                                              boolean replaceAll) {
        return findAndReplace(content, oldString, newString, replaceAll, List.of(
            new ExactStrategy(),
            new LineTrimmedStrategy(),
            new WhitespaceNormalizedStrategy(),
            new IndentationFlexibleStrategy(),
            new EscapeNormalizedStrategy(),
            new TrimmedBoundaryStrategy(),
            new UnicodeNormalizedStrategy(),
            new BlockAnchorStrategy(),
            new ContextAwareStrategy()
        ));
    }

    public static MatchResult findAndReplaceStrict(String content, String oldString, String newString,
                                                   boolean replaceAll) {
        return findAndReplace(content, oldString, newString, replaceAll, List.of(
            new ExactStrategy(),
            new LineTrimmedStrategy(),
            new IndentationFlexibleStrategy(),
            new EscapeNormalizedStrategy(),
            new TrimmedBoundaryStrategy(),
            new UnicodeNormalizedStrategy()
        ));
    }

    private static MatchResult findAndReplace(String content, String oldString, String newString,
                                              boolean replaceAll, List<Strategy> strategies) {
        if (oldString == null || oldString.isEmpty()) {
            return new MatchResult(content, 0, null, "old_string cannot be empty");
        }
        if (oldString.equals(newString)) {
            return new MatchResult(content, 0, null, "old_string and new_string are identical");
        }

        String replacement = newString != null ? newString : "";

        for (Strategy strategy : strategies) {
            List<MatchPos> matches = strategy.find(content, oldString);

            if (!matches.isEmpty()) {
                if (matches.size() > 1 && !replaceAll) {
                    return new MatchResult(content, 0, null,
                        "Found " + matches.size() + " matches for old_string. "
                        + "Provide more context to make it unique, or use replace_all=true.");
                }

                String newContent = applyReplacements(content, matches, replacement);
                return new MatchResult(newContent, matches.size(), strategy.name(), null);
            }
        }

        return new MatchResult(content, 0, null, "old_string not found — no matching strategy succeeded");
    }

    // Replace from end to start to preserve earlier positions
    private static String applyReplacements(String content, List<MatchPos> matches, String replacement) {
        String result = content;
        // Sort descending by start position
        List<MatchPos> sorted = matches.stream()
            .sorted((a, b) -> Integer.compare(b.start, a.start))
            .collect(java.util.stream.Collectors.toList());
        for (MatchPos m : sorted) {
            result = result.substring(0, m.start) + replacement + result.substring(m.end);
        }
        return result;
    }

    // --- Helper: calculate line character positions from line indices ---
    static int[] calculateLinePositions(String[] lines, int startLine, int endLine) {
        int startPos = 0;
        for (int i = 0; i < startLine; i++) {
            startPos += lines[i].length() + 1; // +1 for newline
        }
        int endPos = startPos;
        for (int i = startLine; i < endLine; i++) {
            endPos += lines[i].length() + 1;
        }
        // Trim trailing newline if not at end of content
        if (endLine < lines.length && endPos > 0) {
            endPos -= 1;
        }
        return new int[]{startPos, endPos};
    }

    // --- Helper: line similarity ratio ---
    static double lineSimilarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        String sa = a.strip();
        String sb = b.strip();
        if (sa.equals(sb)) return 1.0;

        // Simple Levenshtein-based ratio (like Python's SequenceMatcher)
        int maxLen = Math.max(sa.length(), sb.length());
        if (maxLen == 0) return 1.0;
        int editDist = levenshteinDistance(sa, sb);
        return 1.0 - (double) editDist / maxLen;
    }

    static int levenshteinDistance(String a, String b) {
        int m = a.length();
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];

        for (int j = 0; j <= n; j++) prev[j] = j;

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(
                    Math.min(prev[j] + 1, curr[j - 1] + 1),
                    prev[j - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[n];
    }

    // --- Unicode normalization ---
    static String unicodeNormalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            String repl = UNICODE_MAP.get(c);
            sb.append(repl != null ? repl : c);
        }
        return sb.toString();
    }

    // --- Whitespace normalization: collapse runs of spaces/tabs to single space ---
    static String whitespaceNormalize(String text) {
        return Pattern.compile("[ \\t]+").matcher(text).replaceAll(" ");
    }

    // --- Escape normalization: convert literal \\n \\t \\r ---
    static String escapeNormalize(String text) {
        return text.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r");
    }

    // =====================================================================
    // Strategy interface and implementations
    // =====================================================================

    record MatchPos(int start, int end) {}

    interface Strategy {
        String name();
        List<MatchPos> find(String content, String pattern);
    }

    // 1. Exact match
    static class ExactStrategy implements Strategy {
        @Override public String name() { return "exact"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            List<MatchPos> matches = new ArrayList<>();
            int idx = 0;
            while ((idx = content.indexOf(pattern, idx)) != -1) {
                matches.add(new MatchPos(idx, idx + pattern.length()));
                idx += 1; // allow overlapping matches for dedup
            }
            return matches;
        }
    }

    // 2. Line-trimmed: strip each line then match blocks
    static class LineTrimmedStrategy implements Strategy {
        @Override public String name() { return "line_trimmed"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            String[] contentLines = content.split("\n", -1);
            String[] patternLines = pattern.split("\n", -1);
            String[] contentTrimmed = new String[contentLines.length];
            for (int i = 0; i < contentLines.length; i++) contentTrimmed[i] = contentLines[i].strip();
            String[] patternTrimmed = new String[patternLines.length];
            for (int i = 0; i < patternLines.length; i++) patternTrimmed[i] = patternLines[i].strip();
            String patternNorm = String.join("\n", patternTrimmed);

            List<MatchPos> matches = new ArrayList<>();
            int n = patternLines.length;
            for (int i = 0; i <= contentTrimmed.length - n; i++) {
                StringBuilder block = new StringBuilder();
                for (int j = 0; j < n; j++) {
                    if (j > 0) block.append("\n");
                    block.append(contentTrimmed[i + j]);
                }
                if (block.toString().equals(patternNorm)) {
                    int[] pos = calculateLinePositions(contentLines, i, i + n);
                    matches.add(new MatchPos(pos[0], pos[1]));
                }
            }
            return matches;
        }
    }

    // 3. Whitespace normalized: collapse multiple spaces/tabs
    static class WhitespaceNormalizedStrategy implements Strategy {
        @Override public String name() { return "whitespace_normalized"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            String normContent = whitespaceNormalize(content);
            String normPattern = whitespaceNormalize(pattern);
            List<MatchPos> normMatches = new ExactStrategy().find(normContent, normPattern);
            if (normMatches.isEmpty()) return List.of();
            // Map back to original positions using character-by-character alignment
            return mapNormalizedPositions(content, normContent, normMatches);
        }
    }

    // 4. Indentation flexible: strip all leading whitespace per line
    static class IndentationFlexibleStrategy implements Strategy {
        @Override public String name() { return "indentation_flexible"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            String[] contentLines = content.split("\n", -1);
            String[] patternLines = pattern.split("\n", -1);
            String[] contentStripped = new String[contentLines.length];
            for (int i = 0; i < contentLines.length; i++) contentStripped[i] = contentLines[i].stripLeading();
            String[] patternStripped = new String[patternLines.length];
            for (int i = 0; i < patternLines.length; i++) patternStripped[i] = patternLines[i].stripLeading();
            String patternNorm = String.join("\n", patternStripped);

            List<MatchPos> matches = new ArrayList<>();
            int n = patternLines.length;
            for (int i = 0; i <= contentStripped.length - n; i++) {
                StringBuilder block = new StringBuilder();
                for (int j = 0; j < n; j++) {
                    if (j > 0) block.append("\n");
                    block.append(contentStripped[i + j]);
                }
                if (block.toString().equals(patternNorm)) {
                    int[] pos = calculateLinePositions(contentLines, i, i + n);
                    matches.add(new MatchPos(pos[0], pos[1]));
                }
            }
            return matches;
        }
    }

    // 5. Escape normalized
    static class EscapeNormalizedStrategy implements Strategy {
        @Override public String name() { return "escape_normalized"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            String unescaped = escapeNormalize(pattern);
            if (unescaped.equals(pattern)) return List.of(); // no escapes to convert, skip
            return new ExactStrategy().find(content, unescaped);
        }
    }

    // 6. Trimmed boundary: trim first and last lines only
    static class TrimmedBoundaryStrategy implements Strategy {
        @Override public String name() { return "trimmed_boundary"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            String[] patternLines = pattern.split("\n", -1);
            if (patternLines.length == 0) return List.of();

            // Trim only first and last
            patternLines[0] = patternLines[0].strip();
            if (patternLines.length > 1) patternLines[patternLines.length - 1] = patternLines[patternLines.length - 1].strip();
            String modifiedPattern = String.join("\n", patternLines);

            String[] contentLines = content.split("\n", -1);
            int n = patternLines.length;
            List<MatchPos> matches = new ArrayList<>();

            for (int i = 0; i <= contentLines.length - n; i++) {
                String[] checkLines = new String[n];
                for (int j = 0; j < n; j++) checkLines[j] = contentLines[i + j];
                checkLines[0] = checkLines[0].strip();
                if (n > 1) checkLines[n - 1] = checkLines[n - 1].strip();
                if (String.join("\n", checkLines).equals(modifiedPattern)) {
                    int[] pos = calculateLinePositions(contentLines, i, i + n);
                    matches.add(new MatchPos(pos[0], pos[1]));
                }
            }
            return matches;
        }
    }

    // 7. Unicode normalized: smart quotes → ASCII
    static class UnicodeNormalizedStrategy implements Strategy {
        @Override public String name() { return "unicode_normalized"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            String normContent = unicodeNormalize(content);
            String normPattern = unicodeNormalize(pattern);
            if (normContent.equals(content) && normPattern.equals(pattern)) return List.of();

            List<MatchPos> normMatches = new ExactStrategy().find(normContent, normPattern);
            if (normMatches.isEmpty()) {
                normMatches = new LineTrimmedStrategy().find(normContent, normPattern);
            }
            if (normMatches.isEmpty()) return List.of();

            // Map back to original positions using char-by-char alignment
            return mapNormalizedPositions(content, normContent, normMatches);
        }
    }

    // 8. Block anchor: first+last lines exact match, middle lines by similarity
    static class BlockAnchorStrategy implements Strategy {
        @Override public String name() { return "block_anchor"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            String normPattern = unicodeNormalize(pattern);
            String normContent = unicodeNormalize(content);
            String[] patternLines = normPattern.split("\n", -1);
            if (patternLines.length < 2) return List.of();

            String firstLine = patternLines[0].strip();
            String lastLine = patternLines[patternLines.length - 1].strip();

            String[] normContentLines = normContent.split("\n", -1);
            String[] origContentLines = content.split("\n", -1);
            int n = patternLines.length;

            // Find candidate positions where first and last lines match
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i <= normContentLines.length - n; i++) {
                if (normContentLines[i].strip().equals(firstLine)
                    && normContentLines[i + n - 1].strip().equals(lastLine)) {
                    candidates.add(i);
                }
            }

            double threshold = candidates.size() == 1 ? 0.50 : 0.70;
            List<MatchPos> matches = new ArrayList<>();

            for (int i : candidates) {
                double similarity;
                if (n <= 2) {
                    similarity = 1.0;
                } else {
                    StringBuilder contentMiddle = new StringBuilder();
                    StringBuilder patternMiddle = new StringBuilder();
                    for (int j = 1; j < n - 1; j++) {
                        if (j > 1) contentMiddle.append("\n");
                        contentMiddle.append(normContentLines[i + j]);
                        patternMiddle.append(patternLines[j]);
                    }
                    similarity = lineSimilarity(contentMiddle.toString(), patternMiddle.toString());
                }
                if (similarity >= threshold) {
                    int[] pos = calculateLinePositions(origContentLines, i, i + n);
                    matches.add(new MatchPos(pos[0], pos[1]));
                }
            }
            return matches;
        }
    }

    // 9. Context aware: 50% of lines must have similarity ≥ 0.80
    static class ContextAwareStrategy implements Strategy {
        @Override public String name() { return "context_aware"; }
        @Override
        public List<MatchPos> find(String content, String pattern) {
            String[] patternLines = pattern.split("\n", -1);
            String[] contentLines = content.split("\n", -1);
            if (patternLines.length == 0) return List.of();

            int n = patternLines.length;
            List<MatchPos> matches = new ArrayList<>();

            for (int i = 0; i <= contentLines.length - n; i++) {
                int highSimCount = 0;
                for (int j = 0; j < n; j++) {
                    double sim = lineSimilarity(patternLines[j], contentLines[i + j]);
                    if (sim >= 0.80) highSimCount++;
                }
                if (highSimCount >= n * 0.5) {
                    int[] pos = calculateLinePositions(contentLines, i, i + n);
                    matches.add(new MatchPos(pos[0], pos[1]));
                }
            }
            return matches;
        }
    }

    // --- Map normalized positions back to original ---
    static List<MatchPos> mapNormalizedPositions(String original, String normalized, List<MatchPos> normMatches) {
        if (normMatches.isEmpty()) return List.of();

        // Build orig-to-norm position map
        int[] origToNorm = new int[original.length() + 1];
        int normPos = 0;
        for (int i = 0; i < original.length(); i++) {
            origToNorm[i] = normPos;
            if (i < normalized.length() && original.charAt(i) == normalized.charAt(normPos)) {
                normPos++;
            } else if (i < normalized.length() && original.charAt(i) == ' ' && normalized.charAt(normPos) == ' ') {
                normPos++;
            }
            // Skip extra whitespace in original
            while (i < original.length() - 1 && (original.charAt(i + 1) == ' ' || original.charAt(i + 1) == '\t')
                   && normPos < normalized.length() && origToNorm[i] == normPos - 1) {
                i++;
                origToNorm[i] = normPos;
            }
        }
        origToNorm[original.length()] = normPos;

        // Reverse map: norm → orig
        Map<Integer, Integer> normToOrigStart = new LinkedHashMap<>();
        for (int i = 0; i < original.length(); i++) {
            if (!normToOrigStart.containsKey(origToNorm[i])) {
                normToOrigStart.put(origToNorm[i], i);
            }
        }

        List<MatchPos> results = new ArrayList<>();
        for (MatchPos nm : normMatches) {
            Integer origStart = normToOrigStart.get(nm.start);
            if (origStart == null) continue;

            // Walk forward to find origEnd
            int origEnd = origStart;
            while (origEnd < original.length() && origToNorm[origEnd] < nm.end) origEnd++;
            results.add(new MatchPos(origStart, Math.min(origEnd, original.length())));
        }
        return results;
    }
}