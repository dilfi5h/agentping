// AgentPing reporter for pi (DESIGN.md §3.6)
// Install: ~/.pi/agent/extensions/agentping.js
// Requires agent-notify on PATH (or ~/bin|/usr/local/bin|/opt/homebrew/bin)
// plus /etc/agentping.conf or ~/.agentping.conf
// Event mapping:
//   before_agent_start → started (task = snippet of the user prompt; the reporter may not publish started)
//   agent_end(stopReason=error) → failed (detail=error text, task=this round's prompt)
//   agent_settled → finished (counts as done only when pi stops auto-continuing; skipped if this run already pushed failed; task=this round's prompt)
// task must ride on finished/failed: started may be swallowed by the reporter, otherwise the notification is just a session id

import { existsSync } from "node:fs"

const HOME = process.env.HOME || ""
const NOTIFY =
  [
    process.env.AGENTPING_NOTIFY,
    HOME + "/bin/agent-notify",
    "/usr/local/bin/agent-notify",
    "/opt/homebrew/bin/agent-notify",
  ].find((p) => p && existsSync(p)) || "agent-notify"

let failedThisRun = false
let taskThisRun = ""

export default function (pi) {
  const send = (ctx, state, fields) => {
    const args = [state, "--agent", "pi"]
    try {
      const sid = ctx?.sessionManager?.getSessionId?.()
      if (sid) args.push("--session", sid)
    } catch {}
    for (const [flag, value] of Object.entries(fields)) {
      if (value) args.push(flag, String(value))
    }
    pi.exec(NOTIFY, args, { timeout: 8000 }).catch(() => {})
  }

  pi.on("before_agent_start", async (event, ctx) => {
    failedThisRun = false
    taskThisRun = (event.prompt || "").replace(/\s+/g, " ").trim()
    send(ctx, "started", { "--task": taskThisRun })
  })

  pi.on("agent_end", async (event, ctx) => {
    const last = event.messages?.[event.messages.length - 1]
    if (last?.stopReason === "error") {
      failedThisRun = true
      const detail = last.errorMessage || last.error || "agent run error"
      send(ctx, "failed", { "--task": taskThisRun, "--detail": detail })
    }
  })

  pi.on("agent_settled", async (_event, ctx) => {
    if (failedThisRun) return // failed already pushed; don't follow it with a finished
    send(ctx, "finished", { "--task": taskThisRun })
  })
}
