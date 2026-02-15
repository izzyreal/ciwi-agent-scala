# Ciwi Agent Parity Matrix (Go -> Scala)

Status legend:
- `Done`: implemented in Scala agent
- `Partial`: implemented with reduced behavior
- `Missing`: not implemented yet

## Core Runtime
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Heartbeat loop | yes | yes | Done |
| Job lease loop | yes | yes | Done |
| Stable default agent ID | yes | yes (`<workdir>/agent-id`) | Done |
| Job timeout handling | yes | yes | Done |
| Terminal status retry/backoff | yes | yes | Done |

## Job Execution Semantics
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Whole-script execution | yes | yes | Done |
| Step-plan-aware execution | yes | yes | Done |
| `step.started` event upload | yes | yes | Done |
| Current-step reporting | yes | yes | Done |
| Dry-run skipped step behavior | yes | basic support | Partial |

## Artifacts
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Collect + upload artifacts | yes | yes | Done |
| Dependency artifact restore | yes | yes | Done |
| Path safety checks on dep artifacts | yes | yes | Done |

## Test & Coverage Reporting
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Parse test reports (`go-test-json`, etc.) | yes | no | Missing |
| Upload `/api/v1/jobs/{id}/tests` | yes | no | Missing |
| Coverage parsing + upload | yes | no | Missing |

## Capabilities & Tooling
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| OS/arch/executor/shell capability report | yes | yes | Done |
| Tool version detection (`tool.*`) | yes | no | Missing |
| Platform-special capability detection | yes | no | Missing |

## Control Plane Behaviors
| Area | Go Agent | Scala Agent | Status |
|---|---|---|---|
| Handle `refresh_tools_requested` | yes | no | Missing |
| Handle `restart_requested` | yes | no | Missing |
| Self-update flow (`update_requested`) | yes | no (intentionally out of scope) | Missing |

## Next High-Impact Targets
1. Implement `/tests` upload (test + coverage report payloads).
2. Add tool capability discovery parity (`tool.*` metadata).
3. Handle heartbeat control flags (`refresh_tools_requested`, `restart_requested`) with safe no-op/ack behavior.
