package io.github.huskyagent.domain.prompt.section;

import io.github.huskyagent.domain.prompt.AbstractPromptSection;
import io.github.huskyagent.domain.prompt.PromptContext;

import java.util.List;

/**
 * 工具使用强制指导 Section
 *
 * <p>对标 Hermes TOOL_USE_ENFORCEMENT_GUIDANCE + 模型级别指导。
 * 防止 LLM 只描述意图而不实际调用工具。</p>
 *
 * <p>注入逻辑：</p>
 * <ul>
 *   <li>通用强制指导 — 所有触发 enforcement 的模型</li>
 *   <li>Google 模型指导 (Gemini/Gemma) — 绝对路径、并行调用、先验验证</li>
 *   <li>OpenAI 模型指导 (GPT/Codex) — 工具持久性、强制工具使用、验证闭环</li>
 * </ul>
 */
public class ToolUseEnforcementSection extends AbstractPromptSection {

    private static final List<String> DEFAULT_ENFORCEMENT_MODELS = List.of(
            "gpt", "codex", "gemini", "gemma", "grok"
    );

    private final String modelName;
    private final Object enforcementConfig;

    public ToolUseEnforcementSection(String modelName, Object enforcementConfig) {
        this.modelName = modelName != null ? modelName : "";
        this.enforcementConfig = enforcementConfig;
    }

    @Override
    public String getName() {
        return "tool_use_enforcement";
    }

    @Override
    public int getPriority() {
        return 510;
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public String build(PromptContext context) {
        if (!shouldInject()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(TOOL_USE_ENFORCEMENT_GUIDANCE);

        String modelLower = modelName.toLowerCase();

        if (modelLower.contains("gemini") || modelLower.contains("gemma")) {
            sb.append(GOOGLE_MODEL_OPERATIONAL_GUIDANCE);
        }

        if (modelLower.contains("gpt") || modelLower.contains("codex")) {
            sb.append(OPENAI_MODEL_EXECUTION_GUIDANCE);
        }

        return buildWithTitle("Tool-use Enforcement", sb.toString());
    }

    private boolean shouldInject() {
        if (enforcementConfig == null) {
            return false;
        }

        if (enforcementConfig instanceof Boolean b) {
            return b;
        }

        if (enforcementConfig instanceof String s) {
            String lower = s.toLowerCase();
            if (lower.equals("true") || lower.equals("always") || lower.equals("yes") || lower.equals("on")) {
                return true;
            }
            if (lower.equals("false") || lower.equals("never") || lower.equals("no") || lower.equals("off")) {
                return false;
            }
            // "auto" or unrecognized — use default model matching
            return matchDefaultModels();
        }

        if (enforcementConfig instanceof List<?> list) {
            String modelLower = modelName.toLowerCase();
            return list.stream()
                    .filter(item -> item instanceof String)
                    .map(item -> ((String) item).toLowerCase())
                    .anyMatch(modelLower::contains);
        }

        return false;
    }

    private boolean matchDefaultModels() {
        String modelLower = modelName.toLowerCase();
        return DEFAULT_ENFORCEMENT_MODELS.stream().anyMatch(modelLower::contains);
    }

    // ── Prompt 内容常量 ──────────────────────────────────────────────────────

    private static final String TOOL_USE_ENFORCEMENT_GUIDANCE = """
            Use tools when the user has asked you to act, verify mutable state, inspect files, \
            run commands, or complete an implementation task. Do not describe tool work that \
            you could perform now; if the action is clearly requested and low-risk, perform it \
            in the same response.

            Do not turn exploratory conversation into file changes. Questions like 'what do you \
            think?', 'is this over-action?', 'how should we design this?', or 'compare X with Y' \
            should be answered with recommendations unless the user explicitly asks you to edit, \
            implement, run, commit, or otherwise change state.

            Before creating, modifying, deleting, moving, overwriting, installing, reconfiguring, \
            pushing, posting externally, or triggering other side-effectful actions, confirm the \
            scope unless the user's request explicitly authorizes that action. Reading files, \
            searching code, checking status, and running targeted tests are low-risk actions.

            Every response should either (a) make appropriate progress with allowed tool calls, \
            (b) ask the single missing question needed before a side effect, or (c) deliver a final \
            result to the user. Do not end with vague promises of future action when a safe next \
            step is available now.""";

    private static final String GOOGLE_MODEL_OPERATIONAL_GUIDANCE = """

            # Google model operational directives
            Follow these operational rules strictly:
            - **Absolute paths:** Always construct and use absolute file paths for all \
            file system operations. Combine the project root with relative paths.
            - **Verify first:** Use read_file/search_files to check file contents and \
            project structure before making changes. Never guess at file contents.
            - **File editing:** Before creating, modifying, deleting, moving, or overwriting \
            files, confirm scope unless the user explicitly requested that edit. Use edit_file \
            for small unique replacements, apply_patch for multi-line or multi-file changes, \
            and write_file only for new files or full rewrites.
            - **Dependency checks:** Never assume a library is available. Check \
            package.json, requirements.txt, Cargo.toml, etc. before importing.
            - **Conciseness:** Keep explanatory text brief — a few sentences, not \
            paragraphs. Focus on actions and results over narration.
            - **Parallel tool calls:** When you need to perform multiple independent \
            operations (e.g. reading several files), make all the tool calls in a \
            single response rather than sequentially.
            - **Non-interactive commands:** Use flags like -y, --yes, --non-interactive \
            to prevent CLI tools from hanging on prompts.
            - **Keep going:** Work autonomously until the task is fully resolved. \
            Don't stop with a plan — execute it.""";

    private static final String OPENAI_MODEL_EXECUTION_GUIDANCE = """

            # Execution discipline

            <tool_persistence>
            - Use tools whenever they improve correctness, completeness, or grounding.
            - Do not stop early when another tool call would materially improve the result.
            - If a tool returns empty or partial results, retry with a different query or \
            strategy before giving up.
            - Keep calling tools until: (1) the task is complete, AND (2) you have verified \
            the result.
            </tool_persistence>

            <mandatory_tool_use>
            NEVER answer these from memory or mental computation — use a tool when the user's \
            request requires current state, mutable files, system inspection, or verifiable \
            execution results:
            - Arithmetic, math, calculations → use terminal or execute_code
            - Hashes, encodings, checksums → use terminal (e.g. sha256sum, base64)
            - Current time, date, timezone → use terminal (e.g. date)
            - System state: OS, CPU, memory, disk, ports, processes → use terminal
            - File contents, sizes, line counts → use read_file, search_files, or terminal
            - Git history, branches, diffs → use terminal
            - Current facts (weather, news, versions) → use web_search
            File writes are not mandatory just because they are possible. For small file \
            replacements use edit_file, for multi-line or multi-file edits use apply_patch, and \
            for new files or full rewrites use write_file only after the user explicitly requests \
            the change or confirms the scope.
            Your memory and user profile describe the USER, not the system you are \
            running on. The execution environment may differ from what the user profile \
            says about their personal setup.
            </mandatory_tool_use>

            <act_dont_ask>
            When a low-risk lookup has an obvious default interpretation, act on it immediately \
            instead of asking for clarification. Examples:
            - 'Is port 443 open?' → check THIS machine (don't ask 'open where?')
            - 'What OS am I running?' → check the live system (don't use user profile)
            - 'What time is it?' → run `date` (don't guess)
            Do not apply this rule to file creation/modification, dependency installation, \
            configuration changes, external posts, pushes, or destructive operations. For those \
            actions, ask first unless the user's wording explicitly authorizes the side effect.
            Only ask for clarification when the ambiguity genuinely changes what tool \
            you would call or what side effect would happen.
            </act_dont_ask>

            <prerequisite_checks>
            - Before taking an action, check whether prerequisite discovery, lookup, or \
            context-gathering steps are needed.
            - Do not skip prerequisite steps just because the final action seems obvious.
            - If a task depends on output from a prior step, resolve that dependency first.
            </prerequisite_checks>

            <verification>
            Before finalizing your response:
            - Correctness: does the output satisfy every stated requirement?
            - Grounding: are factual claims backed by tool outputs or provided context?
            - Formatting: does the output match the requested format or schema?
            - Safety: if the next step has side effects (file writes, commands that change \
            state, external API calls), confirm scope before executing unless it was explicitly \
            requested.
            </verification>

            <missing_context>
            - If required context is missing, do NOT guess or hallucinate an answer.
            - Use the appropriate lookup tool when missing information is retrievable \
            (search_files, web_search, read_file, etc.).
            - Ask a clarifying question only when the information cannot be retrieved by tools.
            - If you must proceed with incomplete information, label assumptions explicitly.
            </missing_context>""";
}
