# Contributing

## Documentation convention (not optional)

Three rules, enforced per commit — a change that skips them is incomplete, not
"to be documented later":

1. **`docs/ARCHITECTURE.md` is updated in the same commit** as any change to the
   module map, a trust boundary, or the build setup. It also carries the phase
   status table and a "Known Deviations from Plan" section.
2. **`CHANGELOG.md`'s `[Unreleased]` section is updated in the same commit** as
   any user-visible change (Keep a Changelog format). Internal refactors that a
   user cannot observe do not need an entry.
3. **KDoc is required on the trust-boundary code**: everything in `tools/`,
   `planmode/`, and `shell/`, and most especially `ToolExecutor.gate()`. The KDoc
   must say *why* the check exists and what an attacker or a confused model would
   do without it — a restatement of the method name is not documentation.

## Build

```sh
./gradlew build          # compile + unit tests
./gradlew runIde         # sandbox PyCharm CE with the plugin loaded
./gradlew test           # unit tests only
```

The build resolves the IntelliJ Platform from the locally installed PyCharm CE
(`platformLocalPath` in `gradle.properties`) and its bundled JBR 21. No separate
JDK or IDE download is needed.

## Testing

- Plain JUnit for anything that does not need the IDE: `ShellAllowListMatcher`
  (including every metacharacter-rejection case), `PlanModeStateMachine`
  transitions, wire-format JSON round-trips, localhost validation, and
  `ToolExecutor.gate()` against a stubbed state machine.
- `BasePlatformTestCase` only where PSI/Document context is unavoidable.
- Manual checks run against a real local TurboFieldfare server; there is no mock
  server.
