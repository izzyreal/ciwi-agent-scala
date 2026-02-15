# ciwi-agent-scala

Scala 3 agent-only implementation for ciwi.

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
