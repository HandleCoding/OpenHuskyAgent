package io.github.huskyagent.infra.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex-based secret redaction for logs and audit output.
 *
 * <p>Short tokens (&lt;18 chars) are fully masked as {@code ***};
 * longer tokens preserve first 6 + {@code ...} + last 4 chars for debuggability.</p>
 *
 * <p>Disabled when env var {@code HUSKY_REDACT_SECRETS} is set to {@code false/0/no/off}.</p>
 */
public final class SecretRedactor {

    private SecretRedactor() {}

    private static final boolean ENABLED = !isDisabledEnv();

    private static boolean isDisabledEnv() {
        String val = System.getenv("HUSKY_REDACT_SECRETS");
        if (val == null) return false;
        return "false0nooff".contains(val.toLowerCase().trim());
    }

    // ── Mask helper ────────────────────────────────────────────────────────────────

    private static String maskToken(String token) {
        if (token.length() < 18) return "***";
        return token.substring(0, 6) + "..." + token.substring(token.length() - 4);
    }

    // ── Known API key prefixes ────────────────────────────────────────────────────

    private static final List<Pattern> PREFIX_PATTERNS = List.of(
            // OpenAI / Anthropic / OpenRouter
            Pattern.compile("sk-[A-Za-z0-9_-]{10,}"),
            // Anthropic specific
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{10,}"),
            // GitHub tokens
            Pattern.compile("ghp_[A-Za-z0-9]{10,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{10,}"),
            Pattern.compile("gho_[A-Za-z0-9]{10,}"),
            Pattern.compile("ghs_[A-Za-z0-9]{10,}"),
            // Google API keys
            Pattern.compile("AIza[A-Za-z0-9_-]{30,}"),
            // AWS Access Key ID
            Pattern.compile("AKIA[A-Z0-9]{16}"),
            // Stripe
            Pattern.compile("sk_(?:live|test)_[A-Za-z0-9]{10,}"),
            // Slack tokens
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
            // SendGrid
            Pattern.compile("SG\\.[A-Za-z0-9_-]{10,}"),
            // HuggingFace
            Pattern.compile("hf_[A-Za-z0-9]{10,}"),
            // Brave Search
            Pattern.compile("BS[A-Za-z0-9]{20,}"),
            // Tavily
            Pattern.compile("tvly-[A-Za-z0-9]{10,}")
    );

    // ── ENV assignments ────────────────────────────────────────────────────────────

    private static final Pattern ENV_ASSIGN_RE = Pattern.compile(
            "([A-Z0-9_]{0,50}(?:API_?KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL|AUTH)[A-Z0-9_]{0,50})\\s*=\\s*(['\"]?)(\\S+)\\2"
    );

    // ── JSON field patterns ────────────────────────────────────────────────────────

    private static final Pattern JSON_FIELD_RE = Pattern.compile(
            "(\"(?:api_?[Kk]ey|token|secret|password|access_token|refresh_token|bearer|secret_value)\")\\s*:\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
    );

    // ── Authorization headers ──────────────────────────────────────────────────────

    private static final Pattern AUTH_HEADER_RE = Pattern.compile(
            "(Authorization:\\s*Bearer\\s+)(\\S+)", Pattern.CASE_INSENSITIVE
    );

    // ── Private key blocks ─────────────────────────────────────────────────────────

    private static final Pattern PRIVATE_KEY_RE = Pattern.compile(
            "-----BEGIN[A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END[A-Z ]*PRIVATE KEY-----"
    );

    // ── Database connection strings ─────────────────────────────────────────────────

    private static final Pattern DB_CONNSTR_RE = Pattern.compile(
            "((?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|amqp)://[^:]+:)([^@]+)(@)",
            Pattern.CASE_INSENSITIVE
    );

    // ── JWT tokens ──────────────────────────────────────────────────────────────────

    private static final Pattern JWT_RE = Pattern.compile(
            "eyJ[A-Za-z0-9_-]{10,}(?:\\.[A-Za-z0-9_=-]{4,}){0,2}"
    );

    // ── URL userinfo (http://user:pass@host) ───────────────────────────────────────

    private static final Pattern URL_USERINFO_RE = Pattern.compile(
            "(https?|wss?|ftp)://([^/\\s:@]+):([^/\\s@]+)@"
    );

    // ── Sensitive query params ─────────────────────────────────────────────────────

    private static final Set<String> SENSITIVE_QUERY_PARAMS = Set.of(
            "access_token", "refresh_token", "id_token", "token",
            "api_key", "apikey", "client_secret", "password", "auth", "secret", "key"
    );

    private static final Pattern URL_QUERY_RE = Pattern.compile(
            "(https?|wss?|ftp)://" +
            "([^\\s/?#]+)" +        // authority
            "([^\\s?#]*)" +          // path
            "\\?([^\\s#]+)" +        // query (required)
            "(#\\S*)?"               // optional fragment
    );

    // ── Public API ─────────────────────────────────────────────────────────────────

    /**
     * Apply all redaction patterns to a block of text.
     * Non-matching text passes through unchanged. Returns null if input is null.
     */
    public static String redact(String text) {
        if (text == null) return null;
        if (!ENABLED || text.isEmpty()) return text;

        // Known API key prefixes
        for (Pattern p : PREFIX_PATTERNS) {
            Matcher m = p.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, maskToken(m.group()));
            }
            m.appendTail(sb);
            text = sb.toString();
        }

        // ENV assignments
        Matcher envMatcher = ENV_ASSIGN_RE.matcher(text);
        StringBuffer envSb = new StringBuffer();
        while (envMatcher.find()) {
            String name = envMatcher.group(1);
            String quote = envMatcher.group(2);
            String value = envMatcher.group(3);
            envMatcher.appendReplacement(envSb, name + "=" + quote + maskToken(value) + quote);
        }
        envMatcher.appendTail(envSb);
        text = envSb.toString();

        // JSON fields
        Matcher jsonMatcher = JSON_FIELD_RE.matcher(text);
        StringBuffer jsonSb = new StringBuffer();
        while (jsonMatcher.find()) {
            String key = jsonMatcher.group(1);
            String value = jsonMatcher.group(2);
            jsonMatcher.appendReplacement(jsonSb, key + ": \"" + maskToken(value) + "\"");
        }
        jsonMatcher.appendTail(jsonSb);
        text = jsonSb.toString();

        // Authorization headers
        Matcher authMatcher = AUTH_HEADER_RE.matcher(text);
        StringBuffer authSb = new StringBuffer();
        while (authMatcher.find()) {
            authMatcher.appendReplacement(authSb, authMatcher.group(1) + maskToken(authMatcher.group(2)));
        }
        authMatcher.appendTail(authSb);
        text = authSb.toString();

        // Private key blocks
        text = PRIVATE_KEY_RE.matcher(text).replaceAll("[REDACTED PRIVATE KEY]");

        // DB connection string passwords
        Matcher dbMatcher = DB_CONNSTR_RE.matcher(text);
        StringBuffer dbSb = new StringBuffer();
        while (dbMatcher.find()) {
            dbMatcher.appendReplacement(dbSb, dbMatcher.group(1) + "***" + dbMatcher.group(3));
        }
        dbMatcher.appendTail(dbSb);
        text = dbSb.toString();

        // JWT tokens
        text = JWT_RE.matcher(text).replaceAll(mr -> maskToken(mr.group()));

        // URL userinfo
        Matcher urlMatcher = URL_USERINFO_RE.matcher(text);
        StringBuffer urlSb = new StringBuffer();
        while (urlMatcher.find()) {
            urlMatcher.appendReplacement(urlSb,
                    urlMatcher.group(1) + "://" + urlMatcher.group(2) + ":***@");
        }
        urlMatcher.appendTail(urlSb);
        text = urlSb.toString();

        // URL query params
        Matcher queryMatcher = URL_QUERY_RE.matcher(text);
        StringBuffer querySb = new StringBuffer();
        while (queryMatcher.find()) {
            String scheme = queryMatcher.group(1);
            String authority = queryMatcher.group(2);
            String path = queryMatcher.group(3);
            String query = redactQueryString(queryMatcher.group(4));
            String fragment = queryMatcher.group(5) != null ? queryMatcher.group(5) : "";
            queryMatcher.appendReplacement(querySb,
                    scheme + "://" + authority + path + "?" + query + fragment);
        }
        queryMatcher.appendTail(querySb);
        text = querySb.toString();

        return text;
    }

    private static String redactQueryString(String query) {
        if (query == null || query.isEmpty()) return query;
        String[] pairs = query.split("&");
        List<String> result = new ArrayList<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                result.add(pair);
                continue;
            }
            String key = pair.substring(0, eq);
            if (SENSITIVE_QUERY_PARAMS.contains(key.toLowerCase())) {
                result.add(key + "=***");
            } else {
                result.add(pair);
            }
        }
        return String.join("&", result);
    }
}