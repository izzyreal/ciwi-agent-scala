# ciwi-agent-scala

Scala 3 agent-only implementation for [ciwi](https://github.com/izzyreal/ciwi).

## Scope
- Heartbeat: `POST /api/v1/heartbeat`
- Lease: `POST /api/v1/agent/lease`
- Execute leased script jobs with timeout and env
- Report status: `POST /api/v1/jobs/{id}/status`
- Upload job artifacts: `POST /api/v1/jobs/{id}/artifacts`
- Restore dependency artifacts from upstream jobs

Not included intentionally:
- ciwi server
- UI
- self-update / installer / service integration

## Run
```bash
export CIWI_SERVER_URL=http://127.0.0.1:8112
export CIWI_AGENT_ID=scala-local
export CIWI_AGENT_WORKDIR=.ciwi-agent

sbt run
```

## Notes
- Shell selection uses required capability `shell` when provided (`posix`, `cmd`, `powershell`).
- Default shell is `posix` on non-Windows and `powershell` on Windows.
- If `CIWI_AGENT_ID` is not set, the agent persists a stable ID in `<workdir>/agent-id`.

## Pure vs Impure Architecture

This project treats **pure logic** and **side effects** as separate layers.

### Pure Layer

Pure code means deterministic logic: same input => same output, no IO.

- `AgentCore.scala`
  - boolean/env parsing rules
  - capability map composition
  - heartbeat directive decisions
  - tool-version string parsing (`parseToolVersionFromOutput`)
- `JobReducer.scala`
  - `runJob` state transitions
  - command-result reduction
  - test/coverage aggregation to `JobExecutionTestReport`

These files are the main place for business rules.

### Impure Layer

Impure code performs effects: network, filesystem, process execution, time, env, logging.

- `ApiClient.scala` (HTTP)
- `CommandRunner.scala` (step script execution)
- `CheckoutRunner.scala` / `GitCheckoutRunner` (git clone/fetch/checkout)
- `ToolVersionProbe.scala` / `DefaultToolVersionProbe` (tool process probing)
- `AgentRuntime.scala` / `SystemAgentRuntime` (env/time/sleep/host/system env + agent-id persistence)
- `AgentLogger.scala` / `StdoutAgentLogger` (logging sink)
- `DepArtifacts.scala`, `ArtifactCollector.scala`, `FileUtil.scala` (artifact/fs effects)

### Orchestration Layer

- `CiwiAgent.scala` is the orchestrator and wiring point.
- It uses injected interfaces (`AgentRuntime`, `CheckoutRunner`, `ToolVersionProbe`, `CapabilityProvider`, `AgentLogger`) and delegates decision logic to pure helpers.
- Default runtime path still uses system adapters, so external behavior remains unchanged.

### Dependency Sketch

```text
                     +-------------------+
                     |   CiwiAgent       |
                     | (orchestration)   |
                     +---------+---------+
                               |
          +--------------------+--------------------+
          |                    |                    |
          v                    v                    v
   +-------------+      +-------------+      +-------------+
   | AgentCore   |      | JobReducer  |      | Protocol    |
   | (pure)      |      | (pure)      |      | Models/JSON |
   +-------------+      +-------------+      +-------------+
          ^
          |
          +----------------------------------------------+
                                                         |
                     +-----------------------------------+---------------------------+
                     |                                   |                           |
                     v                                   v                           v
             +---------------+                   +---------------+           +---------------+
             | AgentRuntime  |                   | ApiClient     |           | AgentLogger   |
             | (env/time/fs) |                   | (HTTP)        |           | (stdout/etc)  |
             +-------+-------+                   +---------------+           +---------------+
                     |
                     +--------+--------------------+-------------------+
                              |                    |                   |
                              v                    v                   v
                      +---------------+     +---------------+   +---------------+
                      | CheckoutRunner|     | CommandRunner |   | ToolVersion   |
                      | (git ops)     |     | (script exec) |   | Probe         |
                      +---------------+     +---------------+   +---------------+
```

### Practical Rule

When adding behavior:

1. Put decision logic in `AgentCore` or `JobReducer` first.
2. Keep effectful operations behind an interface in the impure layer.
3. Wire through `CiwiAgent` by dependency injection, not by direct `System.*`/`println` calls.

### Why this matters

- Easier testing: pure rules can be unit tested without filesystem/network/processes.
- Safer refactors: orchestration can change without rewriting business rules.
- Clear parity work: protocol/behavior decisions are isolated from runtime mechanics.
