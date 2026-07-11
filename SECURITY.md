# Security Policy

## Reporting A Vulnerability

Please do not open a public GitHub issue for security vulnerabilities.

Use GitHub private vulnerability reporting if it is enabled for this repository. If it is not available, contact the maintainers privately and include:

- Affected version or commit
- Reproduction steps
- Impact and affected deployment mode
- Sanitized logs or request examples with secrets removed

Maintainers should acknowledge valid reports as soon as practical and coordinate disclosure after a fix is available.

## Supported Versions

Security fixes target the latest `main` branch and the latest published release once releases are available.

## Deployment Hardening

Husky can expose powerful tools, including terminal, file, browser, MCP, memory, and knowledge access. Before public or shared deployments:

- Replace `HUSKY_API_KEYS` with strong random values.
- Set `TUI_WS_ALLOWED_ORIGINS` to trusted origins instead of wildcard `*`.
- Keep dangerous tools out of public-facing agents unless approval and sandboxing are intentionally configured.
- Prefer a narrow agent for OpenAI-compatible `/v1/*` and Chatbot `/api/chat` traffic.
- Disable or tightly scope browser tools and MCP servers for untrusted users.
- Put Actuator endpoints behind a trusted network or authenticated reverse proxy.
- Keep `.env`, MCP configs, local databases, logs, and data directories out of source control.
- See [docs/integrators.md](docs/integrators.md) for the production checklist.

## Secrets And Logs

Never include API keys, Feishu secrets, MCP credentials, private prompts, or customer data in issues, PRs, examples, or logs. Redact sensitive values before sharing diagnostics.
