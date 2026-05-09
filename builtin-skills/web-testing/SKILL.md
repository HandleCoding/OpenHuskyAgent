---
name: web-testing
description: Use browser tools to test and inspect interactive web pages.
version: 1.0.0
author: Husky Agent
license: MIT
requires_toolsets: [BROWSER]
metadata:
  husky:
    tags: [browser, web-testing, playwright]
---

# Web Testing

Use this skill when a task requires interacting with a dynamic web page, validating UI behavior, filling forms, clicking controls, or inspecting content that depends on JavaScript.

## Workflow

1. Use `browser_navigate` first. It returns a compact snapshot with `@eN` element refs.
2. Use `browser_snapshot` when the page changes or when a ref appears stale.
3. Use refs only from the latest snapshot; refs are session-local and short-lived.
4. Prefer `browser_type` for inputs, `browser_click` for buttons/links, and `browser_press` for keyboard-driven flows.
5. Use `browser_scroll` to reveal off-screen content before interacting with it.
6. Prefer `web_fetch` for static page content; use browser tools when interaction or JavaScript rendering matters.

## Reporting

After each meaningful interaction, inspect the refreshed snapshot and report what changed. If an element ref fails, refresh with `browser_snapshot` and retry with the new ref.
