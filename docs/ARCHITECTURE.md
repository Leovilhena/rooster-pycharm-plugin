# Architecture

A PyCharm CE plugin that talks to a **local** TurboFieldfare server
(OpenAI-compatible API on `127.0.0.1`). Nothing leaves the machine, and there is
no cloud fallback.

This document is kept current with every phase; if code and this file disagree,
the code is the bug report.

## Module map

| Package | Responsibility |
| --- | --- |
| `client/` | `RoosterClient` — JDK `java.net.http.HttpClient`, streaming SSE; wire-format data classes (Gson) |
| `chat/` | `ChatSession` — message history for one conversation |
| `tools/` | Tool definitions (`ReadFile`, `ListFiles`, `SearchInFiles`, `ReadMemoryFile`, `ProposeEdit`, `WriteMemory`, `RunShellCommand`) and `ToolExecutor`, the client-side tool loop |
| `planmode/` | `PlanModeStateMachine` — `PLAN` / `ACT`, the enforcement point |
| `edit/` | `FileEditApplier` — `WriteCommandAction`-wrapped Document edits, undo-grouped; branches on `PathScope` |
| `memory/` | `MemorySlug` (topic-name rule), `MemoryIndex` (directory scan → the always-loaded index), `GlobalMemory` (the application-scoped memory root), `ProjectInstructions` (`ROOSTER.md`) |
| `shell/` | `ShellCommandExecutor`, `ShellAllowListMatcher` |
| `completion/` | Inline ghost-text completion provider (opt-in, off by default) |
| `ui/` | Tool window, chat panel, diff cards, approval cards (plain Swing + `com.intellij.diff.*`) |
| `settings/` | `RoosterSettings` (`PersistentStateComponent`) + `Configurable` UI |

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

- **Gradle 9.7.0** (wrapper, distribution checksum pinned), **Kotlin 2.3.21**
  compiling against language/API level 2.1 (the platform ships the Kotlin 2.1
  stdlib), **IntelliJ Platform Gradle Plugin 2.18.1**, JVM target **21**.
  IPGP 2.18 requires Gradle 9, and Gradle 9 requires a Kotlin plugin newer than
  the platform's own — hence the split between compiler and API level.
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

## Client notes

`RoosterClient` pins two things that cost real debugging time:

- **`HttpClient.Version.HTTP_1_1`.** With the JDK default (HTTP/2 with an h2c
  upgrade attempt), every request from inside the IDE timed out against the
  TurboFieldfare server while the identical call from a standalone JVM
  succeeded. The server is HTTP/1.1; the upgrade probe buys nothing and hangs.
- **`Builder.NO_PROXY`.** The IDE installs its own default `ProxySelector`. We
  only ever talk to loopback, and a proxy in that path can only break the
  connection or push the conversation off the machine.

An unreachable server is a value (`ServerStatus.Down`), never an exception: the
user not having started the server yet is the single most common state.

## Inline completion

`RoosterInlineCompletionProvider` extends the platform's
`DebouncedInlineCompletionProvider` and is registered on the
`com.intellij.inline.completion.provider` extension point (verified against the
installed PyCharm CE 2025.2.5 SDK, not assumed).

It is a deliberately separate, simpler path from the chat tool loop: short
context (1500 chars before the caret, 300 after), a small token budget, no
tools, and **no contact with the Plan/Act state machine**. The only thing it can
do is offer text the user must press Tab to accept, so it needs none of the
chat side's gating — and wiring any in would invite it to grow some.

**Off by default**, with a second switch for "automatically while typing" that
is also off: at ~5 tok/s a suggestion arrives after the user has typed past it.
Cancellation is the platform's — a keystroke cancels the coroutine, which
cancels the flow, which closes the HTTP response and stops the server.

## Plan/Act, and where it is enforced

Two states, `PLAN` and `ACT`, held by a project-level `PlanModeStateMachine`.
New sessions start in `PLAN`.

**Enforcement is `ToolExecutor.gate(tool, mode)`** — a pure function of the
tool's own `effectful` flag and the current mode, called immediately before the
one line that runs a tool, with nothing in between. It cannot be argued with:
there is no argument the model can pass to it, no phrasing it responds to, and
no context length at which it starts agreeing. The refusal text is fixed and is
returned to the model as the tool result, so the model knows the call did not
happen.

`PlanModeStateMachine.setByUser()` is the only mutator, and its only call site
is an `ActionListener` on a button. No tool touches the mode, and the model is
never offered one that could.

A refused edit is still *shown*: `Tool.previewEdit()` is read-only, is computed
before the gate, and produces the diff card. The card is stamped "Plan mode —
not executed. Nothing was written." with Apply disabled, because the user should
never have to infer from a missing button whether something hit the disk.

`propose_edit` replaces a whole file rather than applying a patch: two string
arguments, no patch dialect for the model to get subtly wrong, and the diff is
computed here from the real file instead of trusted from the model. The cost is
tokens, which is the right thing to spend to remove a class of silent corruption.

## Shell commands

`run_shell_command` is gated twice: `effectful = true` means Plan mode refuses it
outright, and in Act mode it still only runs if the allow-list auto-approves it
or the user clicks Approve on a card.

`ShellAllowListMatcher` applies its two rules **in this order**:

1. **Any shell metacharacter disqualifies the command**, before any pattern is
   considered — `&& || ; | &` , backtick, `$(`, `${`, `>`, `<`, newline. Commands
   run through `/bin/sh -c`, so `git status && rm -rf ~` is two commands and a
   `git status*` pattern matches the whole string. Checking the pattern first
   would auto-approve the `rm`.
2. Only then, the command must match a user pattern (glob, or `re:` regex; a
   malformed regex matches nothing rather than everything).

Failing either rule is not a refusal — it falls through to a manual approval
card. The cost of a false negative is one click; the cost of a false positive is
arbitrary code execution requested by a 4B model. Shipped defaults are read-only
commands only, and a unit test asserts none of them mentions `rm`, `curl`,
`install`, `sudo`, and friends.

Approval suspends the tool loop on a `CompletableDeferred` that only the two
buttons complete. Silence is never approval; disposing the panel cancels the
scope and abandons the call.

## Applying an edit

`FileEditApplier` writes through the **Document** inside a single
`WriteCommandAction`, never through `java.nio`. That is what makes one Cmd+Z
revert the whole edit, keeps an open editor tab in sync instead of showing stale
text, and puts the change on the IDE's own undo stack. It re-checks project
confinement even though the gate already ran — it is the last code before bytes
reach the disk, and the check is free.

It is called from exactly one place: the Apply button on a proposal card, which
only exists enabled when the gate allowed the edit.

## Tool loop

`ToolExecutor.run()` is the whole agentic loop; the server has none. One
iteration: send history + tool specs → append the assistant message **unchanged**
(including its `tool_calls`, so ids line up) → run each call in order → append one
`role: tool` message per call keyed by `tool_call_id` → resend. History is never
rewritten, which keeps the server's cached KV prefix valid and keeps the
transcript the user reads identical to the one the model saw. The loop stops
after 8 rounds — a stop, not a tuning knob.

Verified against the real server (2026-08-11, `gemma-4-26b-a4b-it`):

- Tool calls are returned with `finish_reason: "tool_calls"`, and in streaming
  mode arrive whole in a single delta. The client still reassembles by `index`,
  because the wire format permits fragmented `arguments`.
- `role: tool` results and assistant messages carrying `tool_calls` round-trip
  correctly, with or without an explicit `"content": null`.
- Plain JSON Schema (`type: object` + string properties + `required`) is
  accepted. Schemas stay within that subset — no `oneOf`/`allOf`, no
  `additionalProperties`.

### File tools are confined to the project

`ProjectFiles.resolve` is a trust boundary. Model-supplied paths are normalised
**and** resolved through symlinks before being compared to the project root, so
neither `../../.ssh/id_rsa` nor a symlink planted inside the repo can turn a
read-only tool into an exfiltration channel. Refusal returns an error string to
the model; there is no fallback path.

## Memory

Persistent facts that survive across chat sessions, in two independent scopes:

- **Project** — `<project root>/.rooster/memory/<slug>.md`, resolved by the
  existing `ProjectFiles.resolve()`. It is just another project-relative path;
  no new confinement logic exists for it.
- **Global** — `<PathManager.getConfigPath()>/rooster/memory/<slug>.md`.
  That is the same config directory `RoosterSettings` persists into
  (verified on this machine: the settings file is at
  `…/PC-2025.2.5/config/options/rooster.xml`, and `getOptionsPath()` is
  `getConfigPath() + "/options"`, so the memory directory sits beside it). It is
  outside every project root, so `ProjectFiles` cannot confine it — see below.

Deliberately **not** RAG. The corpus is curated by construction, not
accumulated: an embedding model would cost real RAM on an 8GB machine already
holding Gemma4, and a 4B-active-parameter model is worse than a big one at
judging whether a retrieved chunk is relevant — noise would cost more than
retrieval buys. See "Deliberate simplifications" at the end of this section.

**No persisted index.** The index is computed by scanning the two directories.
Cheap at this corpus size, and there is no manifest that can drift out of sync
with the files it describes.

**No frontmatter, no `type` taxonomy.** The two directories *are* the type
system. A `type:` field the model has to remember to set correctly — and could
set to something contradicting the file's own location — buys nothing the
directory doesn't already give for free.

### The slug rule is the confinement mechanism for global scope

`isValidSlug` (`memory/MemorySlug.kt`) accepts `^[a-z0-9]+(-[a-z0-9]+)*$` up to
60 characters, and the plugin appends `.md` itself. A filename is never accepted
from the model. This matters because global memory lives outside every project
root, where `ProjectFiles`'s symlink-aware check does not apply: a slug contains
no `/`, no `.` and no `..`, so traversal is not *refused*, it is unrepresentable.

### File format, and hand-authored files

Plain markdown: `# One-line title` first, free-form body after. The write tool
always emits that line itself from a separate `title` argument rather than
trusting the model to include it. A file missing the line still works — the
index falls back to the filename — so a user can write memory files in their own
editor and the plugin treats them as first-class.

### Loading: `ROOSTER.md` and the index are message 0

`RoosterPanel.loadSystemContext` builds one `system` message at panel
construction, before any user turn: the project's `ROOSTER.md` first, then the
memory index, joined by a blank line. Either half is omitted when it has nothing
to say, and the message is skipped entirely when both do.

`ROOSTER.md` is a fixed, root-relative file the user writes by hand — house
rules, in the CLAUDE.md/AGENTS.md sense. It leads because it is directive and
the index is reference: "follow these" before "here is what you can look up".
It is deliberately *not* memory. Memory is proposed by the model and approved a
card at a time; this file has no tool and no card, because there is nothing in
it for the model to change. `ProjectInstructions.read` also needs no
`ProjectFiles` confinement — that machinery exists to stop a model-supplied path
escaping the project, and this path is a constant.

The index half scans both memory directories once — topic names and one-line
titles only, never bodies. That is the whole point of the split: the
fixed per-session cost stays a few hundred tokens no matter how much memory
accumulates, and the model spends the rest only on the topic it decides it
needs. A line in the transcript says how many topics were loaded, because
context spent without the user typing anything, and an answer shaped by a
memory file they forgot writing, should not look like the model inventing house
rules.

The snapshot is taken once and never rewritten, matching `ChatSession`'s "no
summarisation, no truncation, no reordering" rule. Only the *list* can go stale,
and only against a hand-edit made mid-session — `read_memory_file` always reads
live from disk, so nothing fetched is ever stale, and the rare case is already
covered by starting a new chat.

### Fetching

`read_memory_file(scope, topic)` is `effectful = false`, so it runs in Plan mode
as well as Act — fetching a fact the user recorded themselves is the same class
of action as `read_file`. It always reads live from disk, so nothing it returns
can be stale, and it is capped at 8000 characters (lower than `read_file`'s 60k:
this content lands in a conversation that already holds the index and the
question, and a topic long enough to hit the cap should have been split).

Both scopes share one resolver, `resolveMemoryFile()`, so a read and its matching
write cannot disagree about where a topic lives — that disagreement would be a
memory that saves successfully and is never found again.

Verified against the real server (2026-08-11, `gemma-4-26b-a4b-it`): given only
the index message as message 0 and the question "How should I write tests in
this project?", the model emitted
`read_memory_file {"scope":"project","topic":"testing-conventions"}` unprompted,
and answered from the returned file on the next turn. The design's one real
uncertainty — whether a 4B-active-parameter model would use an index it was
merely handed — is therefore measured rather than assumed.

### Writing

`write_memory(scope, topic, title, content)` is `effectful = true`, so Plan mode
refuses it through the existing gate with no new gating logic. In Act mode it
still writes nothing itself: it produces an `EditPreview`, the transcript shows
the same diff card `propose_edit` uses, and a human clicking Apply is what puts
bytes on disk. **Every write, project or global, goes through that click.**
Memory is the last thing that should accumulate silently — a fact the model
decided to keep, that the user never saw, would shape every later session
invisibly.

It reuses the *edit* approval pattern, not the *shell* one: a memory write has
the same risk shape as `propose_edit`, not that of a one-shot irreversible
external action, so there is no reason to suspend the model's turn waiting for a
human the way `run_shell_command` does.

The `# Title` line is written by the tool from the separate `title` argument,
never taken from the body. The index reads that line, so leaving it to model
discipline would produce index entries that silently disagree with their files.

### `PathScope`, and why the applier branches

`EditPreview` carries a `scope: PathScope` (`Project` by default, so
`propose_edit` is unchanged). It is not a formatting hint — it selects which
trust boundary the path is checked against:

| Scope | `relativePath` holds | Checked by |
| --- | --- | --- |
| `Project` | a project-relative path | `ProjectFiles.resolve()` — normalise, resolve symlinks, must be under the root |
| `GlobalMemory` | a bare topic slug | `GlobalMemory.resolve()` — must still validate as a slug |

`FileEditApplier.apply()` branches on that scope to pick the resolver **and
re-derives the path in both branches, immediately before the write**. It
deliberately does not accept an already-resolved `Path` from its caller: the
entire value of this class being the last stop before disk is that it does the
check itself, and its caller is UI code reacting to a button on a card built
from model output. A global-scope write that skipped the check would mean a
crafted "topic" could write anywhere the IDE can reach, with nothing left to
say no.

Both branches are covered by `FileEditApplierTest` through a root-relative
overload, the same testability trick `ProjectFiles` and `GlobalMemory` use — a
check that cannot be tested is a check nobody notices losing.

`MemoryRoundTripTest` covers everything on either side of the Document write:
the bytes the tool produces, where they land, and that the next session's index
and `read_memory_file` both agree with them. A write that saved correctly but
indexed under a different title, or under a name the read tool cannot ask for,
is the failure that test exists to catch.

**Still unverified by hand:** clicking Apply on a *global* memory card. The
write goes through `VfsUtil.createDirectories` and `FileDocumentManager` on a
path in the IDE's own config directory rather than in a project, and that
specific combination has not been exercised against a running IDE. The
project-scope path is the one `propose_edit` has always used.

### Budget

The index is capped at `MemoryIndex.MAX_INDEX_CHARS` (~2000 chars, ~500 tokens
on the existing chars/4 estimate, ~3% of a 16K window) with an "N more topics
not shown" note past it, so a hand-filled directory cannot silently evict the
user's question. A realistic two-topic index measured ~380 characters, ~95
tokens — under 1% of the window.

**No new budget logic.** The index is an ordinary message in `ChatSession`, so
`approximateTokens()` counts it and the existing 75% warning already accounts
for it. That is load-bearing rather than incidental: an index carried outside
the session, or injected at request time, would be spent but invisible, and the
warning would fire late on exactly the conversations where memory made things
tight. `MemoryContextBudgetTest` pins it — the index-carrying session's estimate
exceeds an identical one without it by precisely the index's own cost.

## Settings and the localhost rule

`RoosterSettings` is an application-level `PersistentStateComponent` (one
local server, not one per project). `LocalhostOnlyValidator` is the only thing
allowed to say a host is acceptable, and it is applied in **two** places:

1. `RoosterConfigurable.apply()` — the path a human takes, where the
   rejection also has to explain itself.
2. `RoosterSettings.loadState()` — because `rooster.xml` is an
   ordinary file a user can hand-edit, and settings on disk are not trusted
   input. A non-loopback host found there is reset to the default.

There is deliberately **no override flag**. Validation is on the literal string,
with no DNS lookup: a name that resolves to loopback today can resolve elsewhere
tomorrow.

## Phase status

| Phase | State |
| --- | --- |
| 0. Scaffolding | done |
| 1. HTTP client + health check | done |
| 2. Basic non-tool chat | done |
| 3. Settings | done |
| 4. Read-only tools + tool loop | done |
| 5. Plan/Act + ProposeEdit (preview only) | done |
| 6. Act-mode edit execution + undo | done |
| 7. Shell tool + allow-list | done |
| 8. Inline completion | done |
| 9. Polish | done |

### Memory feature

| Phase | State |
| --- | --- |
| M1. Slug + index, pure | done |
| M2. Global memory root | done |
| M3. `read_memory_file` | done |
| M4. Index injection | done |
| M5. `write_memory` preview (Plan mode) | done |
| M6. `write_memory` apply (Act mode) | done — verified in a running IDE, both scopes |
| M7. Context-budget check | done |

## Error messages and budgets

- `RoosterClient.explain()` rewrites the server's terse 4xx bodies into
  something actionable: a context overflow says to start a new chat or raise
  `--max-context`; a rejected tool schema says so explicitly; an unknown model
  points at the model id setting.
- The panel warns once when the conversation passes 75% of the configured
  context window. Without it, the failure mode is a sudden HTTP 400 several
  turns later, which reads as "the plugin broke" rather than "this chat got
  long". The estimate is characters/4 — crude on purpose; a 20% error does not
  change the advice, and a real tokenizer would mean shipping the vocabulary.
- `finish_reason: "length"` is surfaced in the transcript, so a truncated answer
  is never mistaken for a complete one.

## Known Deviations from Plan

- **Platform dependency is `local(...)`, not a downloaded `pycharmCommunity(...)`.**
  Reason: disk and bandwidth on this machine; the target IDE is installed already.
- **The long-running shell command "prompt" is a configurable timeout, not a
  dialog.** The plan called for prompting the user when a command runs long. A
  fixed timeout that is visible and adjustable in Settings, and whose expiry is
  reported to both the user and the model with a pointer to that setting, does
  the same job without a modal appearing over the editor while a command runs.
- **`FileEditApplier` has no `BasePlatformTestCase`.** The plan called for
  IntelliJ test fixtures here. Adding `testFramework(TestFrameworkType.Platform)`
  broke the plain-JUnit test runtime (the platform's JUnit5 session listener
  fails to instantiate), and the cost of unpicking that outweighed the coverage:
  the behaviour that matters — edit applies, one Cmd+Z reverts it completely, the
  file on disk matches its original checksum afterwards — is on the manual
  checklist and was verified that way.
- **`EditProposalCard` did change, by one method.** The memory plan said the card
  was already generic over `EditPreview` and needed no edit. It is generic, but a
  global memory topic's `relativePath` is a bare slug, so the card would have
  read "New file: prefers-early-returns" with nothing saying the bytes land
  outside the project in a directory every other project reads. The card is the
  approval, so it now names the scope. Adding to what an approval discloses is
  the one direction this change can safely go.
- **`FileEditApplier.resolve` gained a root-relative overload.** Not in the plan;
  added because the plan's own emphasis — that the global-scope branch must
  re-validate rather than be bypassed — was otherwise untestable without a
  platform fixture this repo does not use. The overload mirrors the one
  `ProjectFiles.resolve` already has, and `FileEditApplierTest` covers both
  branches through it.
- **`ROOSTER.md`'s ordering was unverifiable in this environment, confirmed by a
  human afterward.** `ProjectInstructions.read` is covered by
  `ProjectInstructionsTest` (present, absent, blank, no-root, directory-in-the-way),
  but the agent building this feature had no screen recording permission
  (`screencapture` returns a black frame) or usable accessibility tree, so it
  could not open or read the tool window itself. A human ran the actual
  sandbox afterward: asking "what are the house rules for this project"
  answered directly from `ROOSTER.md` with no tool call, and asking "how
  should I write tests here" correctly triggered `read_memory_file` for the
  separate `testing-conventions` topic — confirming both that instructions
  load ahead of the index in what the server receives, and that the two are
  genuinely on different paths (always-loaded vs. fetched on demand), not
  just positionally distinguishable in code.
- **The rename to Rooster drops the state it used to read, on purpose.** The
  settings file moved from `turbofieldfare.xml` to `rooster.xml`, the tool
  window id from `TurboFieldfare` to `Rooster`, and both memory directories
  from `turbofieldfare`/`.turbofieldfare` to `rooster`/`.rooster`. Nothing
  migrates. What is lost is two booleans in a sandbox, a remembered window
  position, and a memory directory that exists on exactly one machine — all
  reconfigured in less time than reviewing the migration code would take, and
  the plugin has never been released, so no one else has any of it. Migration
  code for a pre-release single-machine state is a permanent maintenance
  liability bought with a one-time inconvenience.
- **Toolchain JDK is PyCharm's bundled JBR 21**, rather than a
  `brew install openjdk@21`. Reason: it is a real JDK 21, already on disk, and
  exactly the runtime the plugin will actually run on.

## Roadmap

Not built yet; captured here so the design thinking isn't lost before it is.

### Skills

The memory mechanism — a directory of named markdown files, indexed by scan
rather than a manifest, fetched on demand by a `(scope, topic)`-shaped
read-only tool — generalises to bundled "skills" without changing anything
about the mechanism: a skill is structurally just another named file in a
third directory with its own index section and a `read_skill_file` tool of
identical shape. What is genuinely new, and deliberately not designed here, is
*invocation*: a skill has to be told to the model as "follow this", not merely
offered as a fact. Not guessed at until it is built.

### MCP client (library ingestion + fetch_url)

Two existing MCP servers — `library` (ingests programming books) and
`fetch_url` (fetches Python docs, PyPI pages, etc.) — are candidates for
wiring into the tool loop. TurboFieldfareServer already speaks OpenAI-style
function-calling, which is structurally compatible with MCP tool shapes; the
actual gap is that nothing today translates between the two. The work is an
adapter inside `tools/ToolExecutor`: connect to each MCP server, call
`tools/list` to discover its tools, convert each tool's JSON Schema into the
function-tool schema sent to the model, and on a matching `tool_call`, proxy
to the real MCP server and return its result as the tool message.

Design constraints to resolve before building, not after:

- **Context budget.** Every exposed tool's schema costs tokens on *every*
  request against a 16K window, whether or not it's used that turn. Expose a
  small, tightly-described tool surface rather than everything both servers
  offer.
- **Result truncation.** A fetched docs page or an ingested book chunk can be
  large. Needs the same kind of cap `shell/` already applies to command
  output (4000 chars) — uncapped, one tool call could blow the context.
  RAG genuinely earns its keep *inside* the `library` MCP server itself
  (a book corpus doesn't fit in context no matter how it's phrased) — that's
  a different problem from plugin-side memory, see above.
- **Safety gating.** `fetch_url` reaching arbitrary URLs and `library`
  presumably writing an index/cache are both effectful in the same sense
  `RunShellCommand` is. They should route through the same `PlanModeStateMachine`
  gate — blocked in Plan mode, approved/allow-listed in Act mode — not get a
  free pass because they arrived via MCP instead of a built-in tool.
- **Schema compatibility.** TurboFieldfareServer 400s on `oneOf`/`allOf`/mixed-type
  unions in tool schemas (see `docs/OPENAI_SERVER.md` upstream). Check both
  MCP servers' actual tool schemas against that constraint before assuming
  they pass through unmodified — they may need reshaping.
- **Process lifecycle.** MCP servers are typically separate processes (stdio).
  The plugin would own starting them when needed and stopping them on IDE
  close — new operational surface beyond anything that exists today.
