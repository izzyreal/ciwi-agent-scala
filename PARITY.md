# Ciwi Agent Parity Matrix (Go -> Scala)

Status legend:
- `Done`: implemented in Scala agent and covered by tests
- `Partial`: implemented with reduced behavior vs Go
- `Missing`: not implemented yet

## Core Runtime
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Heartbeat loop | yes | yes | Done |
| Job lease loop | yes | yes | Done |
| Stable default agent ID | yes | yes (`<workdir>/agent-id`) | Done |
| Job timeout handling | yes | yes | Done |
| Terminal status retry/backoff | yes | yes (5 attempts, exponential backoff) | Done |

## Job Execution Semantics
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Whole-script execution | yes | yes | Done |
| Source checkout (`source.repo` + `source.ref`) | yes | yes | Done |
| Step-plan-aware execution | yes | yes | Done |
| `step.started` event upload | yes | yes | Done |
| Current-step reporting | yes | yes | Done |
| Dry-run skipped step behavior | yes | basic support | Partial |
| `CIWI_AGENT_TRACE_SHELL` behavior | yes | yes (posix trace via `set -x`) | Done |

## Artifacts
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Collect + upload artifacts | yes | yes | Done |
| Dependency artifact restore | yes | yes | Done |
| Path safety checks on dep artifacts | yes | yes (unsafe paths skipped) | Done |
| Partial dep-artifact failure propagation | yes | yes | Done |

## Test & Coverage Reporting
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Parse test reports (`go-test-json`) | yes | yes | Done |
| Upload `/api/v1/jobs/{id}/tests` | yes | yes | Done |
| Coverage parsing (`go-coverprofile`, `lcov`) | yes | yes | Done |
| Failed test report affects terminal status | yes | yes | Done |
| Test upload failure handling | yes | yes (logged, terminal status preserved) | Done |

## Capabilities & Tooling
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| OS/arch/executor/shell capability report | yes | yes | Done |
| Tool version detection (`tool.*`) | yes | yes | Done |
| Platform-special capability detection | yes | no | Missing |

## Control Plane Behaviors
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Handle `refresh_tools_requested` | yes | yes (refresh capabilities + immediate follow-up heartbeat) | Done |
| Handle `restart_requested` | yes | yes (ack via restart status heartbeat; no restart action) | Done |
| Self-update flow (`update_requested`) | yes | no (intentionally out of scope) | Missing |

## Remaining High-Impact Targets
1. Add platform-special capability detection parity (for example Linux `xorg-dev`, Windows `msvc`).
2. Decide whether to keep self-update out of scope or add a constrained variant.
