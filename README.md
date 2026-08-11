# TurboFieldfare for PyCharm

An offline AI assistant that lives in a PyCharm CE tool window and talks to a
[TurboFieldfare](../turbo-fieldfare) server running on `127.0.0.1`. No cloud, no
telemetry, no fallback to a remote API — if the local server is down, the plugin
says so and does nothing.

## Requirements

- PyCharm CE 2025.2.x
- A TurboFieldfare server on localhost, e.g.
  `TurboFieldfareServer --model scratch/gemma4.gturbo --port 8080 --max-context 16384`

## Safety model in one paragraph

The model is treated as untrusted. It cannot edit a file or run a command on its
own: **Plan mode** (the default for a new session) blocks all effectful tools in
deterministic Kotlin — `ToolExecutor.gate()` — and only a human click moves the
session to **Act mode**. Shell commands are refused unless they match a
user-configured allow-list of read-only commands, and any command containing
shell metacharacters (`&&`, `;`, `|`, `` ` ``, `$(`) is disqualified from
auto-approval no matter what it matches, so `git status && rm -rf /` cannot ride
in on a `git status*` rule.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

```sh
./gradlew runIde
```
