# Architecture

A PyCharm CE plugin that talks to a **local** TurboFieldfare server
(OpenAI-compatible API on `127.0.0.1`). Nothing leaves the machine, and there is
no cloud fallback.

This document is kept current with every phase; if code and this file disagree,
the code is the bug report.

## Module map

| Package | Responsibility |
| --- | --- |
| `client/` | `TurboFieldfareClient` — JDK `java.net.http.HttpClient`, streaming SSE; wire-format data classes (Gson) |
| `chat/` | `ChatSession` — message history for one conversation |
| `tools/` | Tool definitions (`ReadFile`, `ListFiles`, `SearchInFiles`, `ProposeEdit`, `RunShellCommand`) and `ToolExecutor`, the client-side tool loop |
| `planmode/` | `PlanModeStateMachine` — `PLAN` / `ACT`, the enforcement point |
| `edit/` | `FileEditApplier` — `WriteCommandAction`-wrapped Document edits, undo-grouped |
| `shell/` | `ShellCommandExecutor`, `ShellAllowListMatcher` |
| `completion/` | Inline ghost-text completion provider (opt-in, off by default) |
| `ui/` | Tool window, chat panel, diff cards, approval cards (plain Swing + `com.intellij.diff.*`) |
| `settings/` | `TurboFieldfareSettings` (`PersistentStateComponent`) + `Configurable` UI |

## Trust boundaries

Three packages are trust boundaries and carry KDoc explaining *why* each check
exists, not just what it does:

- `tools/` — everything the model can cause to happen goes through `ToolExecutor.gate()`.
- `planmode/` — the PLAN/ACT state. Only a human click moves PLAN → ACT.
- `shell/` — the allow-list. Metacharacters disqualify a command from auto-approval.

The rule behind all three: the model is a 4B-class local model over a long
context. It is treated as untrusted input, never as a policy enforcer. Anything
that matters is deterministic Kotlin.

## Build setup

- **Gradle 8.14.3** (wrapper, distribution checksum pinned), **Kotlin 2.1.21**,
  **IntelliJ Platform Gradle Plugin 2.18.1**, JVM target **21**.
- The platform dependency is the **locally installed** PyCharm CE
  (`platformLocalPath` in `gradle.properties`, default `/Applications/PyCharm CE.app`)
  rather than a downloaded IDE distribution. This machine has 8GB of RAM and a
  local LLM already resident; a ~1GB IDE download and second copy on disk buys
  nothing when the target IDE is already installed. Change that property to build
  against a different install.
- **JDK**: no separate JDK install is required. The build's Java toolchain is
  satisfied by PyCharm's bundled JBR 21 (registered via
  `org.gradle.java.installations.paths`). The system JDK is 22, which the
  platform does not target.
- Kotlin stdlib and `kotlinx-coroutines` are `compileOnly` / not bundled — the
  platform ships both, and shipping a second copy breaks at runtime.

## Phase status

| Phase | State |
| --- | --- |
| 0. Scaffolding | done |
| 1. HTTP client + health check | not started |
| 2. Basic non-tool chat | not started |
| 3. Settings | not started |
| 4. Read-only tools + tool loop | not started |
| 5. Plan/Act + ProposeEdit (preview only) | not started |
| 6. Act-mode edit execution + undo | not started |
| 7. Shell tool + allow-list | not started |
| 8. Inline completion | not started |
| 9. Polish | not started |

## Known Deviations from Plan

- **Platform dependency is `local(...)`, not a downloaded `pycharmCommunity(...)`.**
  Reason: disk and bandwidth on this machine; the target IDE is installed already.
- **Toolchain JDK is PyCharm's bundled JBR 21**, rather than a
  `brew install openjdk@21`. Reason: it is a real JDK 21, already on disk, and
  exactly the runtime the plugin will actually run on.
