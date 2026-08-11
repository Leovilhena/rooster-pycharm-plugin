# TurboFieldfare for PyCharm

[![CI](https://github.com/Leovilhena/turbofieldfare-pycharm-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/Leovilhena/turbofieldfare-pycharm-plugin/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF.svg?logo=kotlin)](https://kotlinlang.org)
[![Platform](https://img.shields.io/badge/PyCharm%20CE-2025.2.x-000000.svg?logo=pycharm)](https://www.jetbrains.com/pycharm/)

An offline AI assistant that lives in a PyCharm CE tool window and talks to a
[TurboFieldfare](../turbo-fieldfare) server running on `127.0.0.1`. No cloud, no
telemetry, no fallback to a remote API — if the local server is down, the plugin
says so and does nothing.

## Requirements

- PyCharm CE 2025.2.x
- A TurboFieldfare server on localhost, e.g.
  ```sh
  .build/release/TurboFieldfareServer --model scratch/gemma4.gturbo --port 8080 --max-context 16384
  ```

## What it does

- **Chat** in a side tool window, streaming, cancellable mid-answer.
- **Reads your project** on request: `read_file`, `list_files`, `search_in_files`,
  all confined to the open project directory.
- **Proposes edits** as a diff card — see the change in PyCharm's own diff viewer
  before anything is written, and apply it with one click and one undo.
- **Runs shell commands**, either from your allow-list or with a per-command
  approval card.
- **Inline ghost-text completion**, off by default (see below).

## Safety model

The model is treated as untrusted input, not as a policy enforcer. Three rules,
all of them deterministic Kotlin rather than instructions in a prompt:

1. **Plan mode is the default.** In Plan mode, file edits and shell commands are
   refused before they run. The refusal happens in `ToolExecutor.gate()`, a pure
   function of the tool and the current mode. Only a human clicking the toggle
   moves the session to Act mode — no tool can, and none is offered.
2. **Shell commands ask by default.** A command runs unattended only if it
   matches your allow-list *and* contains no shell metacharacter. That order
   matters: commands run through `/bin/sh -c`, so `git status && rm -rf ~` would
   otherwise match a `git status*` rule. Failing either check is not a refusal —
   it asks you.
3. **The server must be on this machine.** Any non-loopback host is rejected,
   both in Settings and when read from a hand-edited settings file. The server
   has no authentication and no TLS; there is no override.

File paths from the model are resolved through symlinks and checked against the
project root before any read or write, so neither `../../.ssh/id_rsa` nor a
symlink planted in the repo escapes the project.

## Inline completion

Off by default, and worth leaving off unless you know what you are opting into:
this hardware decodes at roughly 5 tokens/second, so an automatic suggestion
tends to arrive after you have typed past it. Two switches in Settings — enable
it at all, and enable it *while typing* — plus a debounce. With the second one
off, completions appear only when you ask for them explicitly.

Only fires for Python and shell files (`.py`, `.pyi`, `.sh`, `.bash`, `.zsh`) —
every other file type is skipped before a request is ever sent. The prompt
sent per request is also intentionally short (600 characters before the
cursor, 150 after): fewer prompt tokens means less prefill time before decode
even starts, which is most of the latency that's actually addressable on a
local ~5 tok/s decoder.

## Settings

**Settings → Tools → TurboFieldfare**: server host and port, model id, Plan-mode
default, the shell allow-list, inline completion, the server's context window
(used only to warn you before a chat outgrows it), and the shell timeout.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

```sh
./gradlew build                                          # compile + unit tests
./gradlew runIde -PrunIdeProject=/path/to/some/project    # sandbox IDE
```

## Not in v1

Multi-session chat tabs, repo-wide RAG or embeddings, history summarisation, a
JCEF chat UI, and mock-server CI tests. See the testing notes in
[CONTRIBUTING.md](CONTRIBUTING.md).
