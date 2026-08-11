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
