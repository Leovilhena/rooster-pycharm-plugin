# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Plugin scaffolding: a **TurboFieldfare** tool window on the right-hand side of
  PyCharm CE 2025.2, currently an empty placeholder panel.
- Server status line in the tool window: green "Connected" with the served model
  ids in the tooltip when a local TurboFieldfare server answers, grey "Not
  connected" with the reason spelled out inline when it does not. Polled every
  5 seconds; a missing server is never an error dialog or a stack trace.
- Streaming chat in the tool window: type a message, press Enter (Shift+Enter for
  a newline) and the reply streams in token by token. The Send button becomes
  Cancel while generating; cancelling drops the connection so the server stops
  generating instead of finishing an answer nobody is waiting for.
- Settings panel under **Settings → Tools → TurboFieldfare**: server host and
  port, model id override, and whether new chats start in Plan mode. Changing
  the port takes effect immediately, with no IDE restart.
- The server host is restricted to loopback. A non-local host is refused with an
  explanation, both when typed into Settings and when found in a hand-edited
  settings file — the server has no authentication or TLS, so the plugin will
  not send code anywhere else.
- Read-only tools the assistant can call while answering: `read_file`,
  `list_files` and `search_in_files`, each confined to the open project. The
  transcript shows every tool call as it happens.
- **Plan / Act modes.** New sessions start in Plan mode, where file edits are
  refused outright. A `Plan mode` / `Act mode` toggle in the tool window header
  is the only way to switch, and only a click can do it.
- `propose_edit`: the assistant can propose replacing a file's contents. The
  proposal appears in the transcript as a card with the changed line counts, a
  **Show diff** button that opens PyCharm's diff viewer, and an Apply button.
  In Plan mode the card is stamped "not executed, nothing was written" and Apply
  is disabled.
- In Act mode, **Apply** writes the proposed change through the IDE's document
  and undo stack: an open editor updates in place, and a single Cmd+Z reverts the
  whole edit.
- `run_shell_command`, with an approval card. Commands matching the allow-list
  run straight away; anything else asks first, and the card shows the command
  verbatim plus why it was not auto-approved. Refused in Plan mode entirely.
- Shell allow-list in Settings, pre-filled with read-only commands
  (`git status*`, `ls*`, `cat *`, …). Any command containing a shell
  metacharacter always asks, whatever it matches — `git status && rm -rf ~`
  cannot ride in on a `git status*` rule.
- Inline ghost-text completion from the local model, **off by default**, with a
  separate switch for suggesting while typing and a configurable debounce.
  Suggestions insert with Tab; continued typing cancels the in-flight request
  rather than queuing it.
- Readable errors: a conversation that outgrows the server's context window, a
  rejected tool schema, and an unknown model id each say what to do about it.
- A one-time warning when a chat passes 75% of the configured context window.
- Configurable shell command timeout (default 60s); a killed command tells both
  the user and the model, and points at the setting.
