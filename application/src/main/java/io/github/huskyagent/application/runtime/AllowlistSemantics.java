package io.github.huskyagent.application.runtime;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Allowlist semantics for agent capability resources:
 * <ul>
 *   <li>null or empty → nothing allowed</li>
 *   <li>contains {@code *} or {@code all} → everything allowed</li>
 *   <li>otherwise → only listed ids</li>
 * </ul>
 */
public final class AllowlistSemantics {

    public static final String ALL_TOKEN = "*";

    private AllowlistSemantics() {
    }

    public static boolean isUnrestricted(Collection<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return false;
        }
        return allowlist.stream().anyMatch(AllowlistSemantics::isAllToken);
    }

    public static boolean isNone(Collection<String> allowlist) {
        return allowlist == null || allowlist.isEmpty();
    }

    public static boolean allows(String id, Collection<String> allowlist) {
        if (isNone(allowlist)) {
            return false;
        }
        if (isUnrestricted(allowlist)) {
            return true;
        }
        if (id == null || id.isBlank()) {
            return false;
        }
        return allowlist.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(s -> s.equals(id));
    }

    /**
     * Concrete ids for validation (excludes {@code *} / {@code all}).
     */
    public static Set<String> concreteIds(Collection<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty()) {
            return Set.of();
        }
        return allowlist.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !isAllToken(s))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static boolean isAllToken(String value) {
        if (value == null) {
            return false;
        }
        String t = value.trim();
        return ALL_TOKEN.equals(t) || "all".equalsIgnoreCase(t);
    }

    public static String normalizeToken(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        if (t.isEmpty()) {
            return t;
        }
        if (isAllToken(t)) {
            return ALL_TOKEN;
        }
        return t;
    }
}
