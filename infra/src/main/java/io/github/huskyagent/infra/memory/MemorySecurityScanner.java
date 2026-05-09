package io.github.huskyagent.infra.memory;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 记忆安全扫描器
 *
 * 检测记忆内容中的潜在安全问题：
 * 1. Prompt Injection - 尝试覆盖系统指令
 * 2. 数据泄露模式 - 敏感命令执行或密钥暴露
 */
@Component
public class MemorySecurityScanner {

    // Prompt Injection 模式
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("ignore\\s+(all\\s+)?(previous|above)\\s+instructions", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ignore\\s+(all\\s+)?(prior|earlier)\\s+(instructions|context)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("system:\\s*you are now", Pattern.CASE_INSENSITIVE),
        Pattern.compile("system:\\s*forget", Pattern.CASE_INSENSITIVE),
        Pattern.compile("disregard\\s+(all\\s+)?(previous|above)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<\\|.*?\\|>"),  // 特殊 token
        Pattern.compile("\\[SYSTEM\\]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("###\\s*SYSTEM", Pattern.CASE_INSENSITIVE)
    );

    // 数据泄露模式（危险命令执行或密钥暴露）
    private static final List<Pattern> LEAK_PATTERNS = List.of(
        Pattern.compile("curl\\s+.*\\|.*sh"),
        Pattern.compile("curl\\s+.*\\|.*bash"),
        Pattern.compile("wget\\s+.*-O.*\\|"),
        Pattern.compile("wget\\s+.*\\|.*sh"),
        Pattern.compile("eval\\s*\\("),
        Pattern.compile("eval\\s+"),
        Pattern.compile("`[^`]*\\$\\{[^}]+\\}[^`]*`"),  // Command substitution with env vars
        Pattern.compile("\\$\\([^)]+\\)"),  // Command substitution
        Pattern.compile("(?s)/etc/passwd"),
        Pattern.compile("(?s)/etc/shadow"),
        Pattern.compile("~/.ssh/"),
        Pattern.compile("~/.gnupg/"),
        Pattern.compile("AWS_[A-Z_]+="),  // AWS credentials
        Pattern.compile("api[_-]?key\\s*[=:]\\s*['\"]?[a-zA-Z0-9]{20,}['\"]?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("secret[_-]?key\\s*[=:]\\s*['\"]?[a-zA-Z0-9]{20,}['\"]?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("token\\s*[=:]\\s*['\"]?[a-zA-Z0-9]{20,}['\"]?", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 扫描内容
     */
    public SecurityCheckResult scan(String content) {
        if (content == null || content.isBlank()) {
            return SecurityCheckResult.ok();
        }

        List<String> warnings = new ArrayList<>();
        boolean blocked = false;
        String blockReason = null;

        // 检查 Prompt Injection
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(content).find()) {
                warnings.add("Potential prompt injection detected: " + pattern.pattern());
                blocked = true;
                blockReason = "Prompt injection pattern detected";
                break;
            }
        }

        // 检查数据泄露模式（只警告，不 block）
        for (Pattern pattern : LEAK_PATTERNS) {
            if (pattern.matcher(content).find()) {
                warnings.add("Potential data leak pattern detected: " + pattern.pattern());
            }
        }

        return new SecurityCheckResult(warnings, blocked, blockReason);
    }
}