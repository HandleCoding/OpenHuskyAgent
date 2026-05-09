package io.github.huskyagent.infra.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {

    @Test
    void redactsOpenAIKey() {
        String input = "API key is " + "sk" + "-proj-abcdef1234567890xyz";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("abcdef1234567890"));
        assertTrue(result.contains("..."));
    }

    @Test
    void redactsAnthropicKey() {
        String input = "key=" + "sk" + "-ant-api03-1234567890abcdef1234567890";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("1234567890abcdef1234567890"));
        assertTrue(result.contains("sk-ant"));
    }

    @Test
    void redactsGitHubToken() {
        String input = "ghp" + "_ABCDEFGHIJKLMNOPQRSTUVWXYZ1234";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertTrue(result.contains("ghp_AB"));
    }

    @Test
    void redactsGoogleApiKey() {
        String input = "AIza" + "SyA1234567890abcdefghijklmnopqrstuvwx";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("1234567890abcdefghijklmnopqrst"));
        assertTrue(result.contains("AIzaSy"));
    }

    @Test
    void redactsAWSAccessKey() {
        String input = "AKIA" + "IOSFODNN7EXAMPLE";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("IOSFODNN7EXAMPLE"));
    }

    @Test
    void shortTokenIsFullyMasked() {
        // sk-short12: pattern requires 10+ chars after sk-, "short12" has only 7
        // so this won't match PREFIX_PATTERNS. Need a shorter explicit token.
        // Use a token that IS shorter than 18 chars and DOES match a prefix pattern.
        String input = "sk" + "-abcdefghij"; // 14 chars, but pattern requires 10+ after sk-
        String result = SecretRedactor.redact(input);
        // 14 chars < 18 → fully masked as ***
        assertTrue(result.contains("***"));
    }

    @Test
    void redactsEnvAssignment() {
        String input = "OPENAI_API_KEY=" + "sk" + "-proj-abcdef1234567890xyz";
        String result = SecretRedactor.redact(input);
        assertTrue(result.contains("OPENAI_API_KEY="));
        assertFalse(result.contains("abcdef1234567890"));
    }

    @Test
    void redactsJsonField() {
        String input = "{\"apiKey\": \"" + "sk" + "-proj-abcdef1234567890xyz\"}";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("abcdef1234567890"));
        // The key is already redacted by PREFIX_PATTERNS before JSON_FIELD_RE runs
        // so JSON_FIELD_RE sees the redacted value and may not match again
        assertTrue(result.contains("\"apiKey\""));
    }

    @Test
    void redactsAuthHeader() {
        String input = "Authorization: Bearer " + "sk" + "-proj-abcdef1234567890xyz";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("abcdef1234567890"));
        assertTrue(result.contains("Authorization: Bearer"));
    }

    @Test
    void redactsPrivateKeyBlock() {
        String input = "-----" + "BEGIN RSA " + "PRIV" + "ATE KEY-----\nMIIEpAIBAAKCAQEA...\n-----END RSA " + "PRIV" + "ATE KEY-----";
        String result = SecretRedactor.redact(input);
        assertTrue(result.contains("[REDACTED PRIVATE KEY]"));
        assertFalse(result.contains("MIIEpAIBAAKCAQEA"));
    }

    @Test
    void redactsDbConnectionString() {
        String input = "postgres://admin:secretpassword@db.example.com:5432/mydb";
        String result = SecretRedactor.redact(input);
        assertTrue(result.contains("postgres://admin:***@"));
        assertFalse(result.contains("secretpassword"));
    }

    @Test
    void redactsJwtToken() {
        String input = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
    }

    @Test
    void redactsUrlUserinfo() {
        String input = "https://admin:secretpassword@api.example.com/v1/data";
        String result = SecretRedactor.redact(input);
        assertTrue(result.contains("https://admin:***@"));
        assertFalse(result.contains("secretpassword"));
    }

    @Test
    void redactsSensitiveQueryParams() {
        String input = "https://api.example.com/auth?access_token=abc123&state=xyz";
        String result = SecretRedactor.redact(input);
        assertTrue(result.contains("access_token=***"));
        assertTrue(result.contains("state=xyz"));
    }

    @Test
    void preservesNonSensitiveText() {
        String input = "The agent processed the request successfully in 120ms";
        assertEquals(input, SecretRedactor.redact(input));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(SecretRedactor.redact(null));
    }

    @Test
    void returnsEmptyForEmptyInput() {
        assertEquals("", SecretRedactor.redact(""));
    }

    @Test
    void redactsStripeKey() {
        String input = "sk" + "_live_abcdef1234567890xyz123456";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("abcdef1234567890xyz123456"));
        assertTrue(result.contains("sk_liv"));
    }

    @Test
    void redactsSlackToken() {
        String input = "xoxb" + "-1234567890123-abcdef1234567890";
        String result = SecretRedactor.redact(input);
        assertFalse(result.contains("xoxb" + "-1234567890123-abcdef1234567890"));
    }
}