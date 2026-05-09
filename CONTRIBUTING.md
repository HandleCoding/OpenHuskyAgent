# Contributing to Husky

Thanks for helping improve Husky. This project is a Java 17, Maven-wrapper based agent runtime with separate service, application, domain, infra, and client modules.

## Development Setup

Requirements:

- JDK 17 or newer
- Git
- The included Maven wrapper (`./mvnw`)

Useful commands:

```bash
./mvnw -B -ntp test
./mvnw -B -ntp -DskipTests package
./mvnw -B -ntp test -pl service
./mvnw -B -ntp test -Dtest=LocalDocsKnowledgeProviderTest -pl infra
```

The default test command must pass without external LLM credentials or local browser binaries. Tests that call real OpenAI-compatible APIs or require Playwright browser binaries are gated behind explicit profiles:

```bash
OPENAI_API_KEY=... ./mvnw -B -ntp test -P live-api-tests
./mvnw -B -ntp test -P browser-tests
```

Install Playwright Chromium before running browser tests:

```bash
./mvnw -B -ntp exec:java -pl infra -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

## Pull Requests

Before opening a PR:

- Keep scope tight and avoid unrelated refactors.
- Run `./mvnw -B -ntp test` unless the change is documentation-only.
- Update `.env.example`, README, and docs when behavior or configuration changes.
- Do not commit `.env`, credentials, local databases, IDE state, or generated build output.
- For tool, MCP, browser, terminal, file, memory, or storage changes, describe the security and scene-policy impact.

## Runtime And Configuration Changes

Husky is metadata-driven: channel, scene, principal, toolsets, memory, knowledge, MCP visibility, workspace, and checkpoint policy should flow through runtime metadata instead of global mutable state. Preserve local personal-assistant defaults unless a change explicitly targets a different deployment mode.

Unknown non-local storage/provider types should fail fast. Do not silently fall back to local storage for enterprise or remote-storage configuration errors.

## Reporting Security Issues

Do not report vulnerabilities in public issues. See `SECURITY.md` for the current reporting process.
