// AgentPing reporter plugin for OpenCode (DESIGN.md §3.6)
// Install: ~/.config/opencode/plugins/agentping.js  (needs agent-notify + /etc/agentping.conf)
// Event mapping:
//   chat.message        → cache task (this round's prompt; authoritative source: output.parts)
//   session.status(busy)→ started (the reporter may not publish it)
//   session.idle        → finished (with task; skipped if this session already pushed failed)
//   session.error       → failed (task + error summary)
//   permission.ask      → waiting (task + permission title/command)
// finished/failed must carry task: otherwise the notification body is just the session id.
// Swallow every exception silently; never affect opencode itself.
// Note: the UserMessage type carries no parts (the text lives outside the message event), so task
// capture must go through chat.message's output.parts, not the message.updated event payload.

import { spawn } from "node:child_process"
import { existsSync } from "node:fs"

// Resolve agent-notify: explicit env var > ~/bin (macOS install path) > /usr/local/bin (Linux install path) > PATH fallback
const HOME = process.env.HOME || ""
const NOTIFY =
  [
    process.env.AGENTPING_NOTIFY,
    HOME + "/bin/agent-notify",
    "/usr/local/bin/agent-notify",
    "/opt/homebrew/bin/agent-notify",
  ].find((p) => p && existsSync(p)) || "agent-notify"
const tasks = new Map() // sessionID → latest user prompt
const failedSent = new Set() // sessionIDs that already pushed failed

function trim(s, n) {
  return String(s == null ? "" : s).replace(/\s+/g, " ").trim().slice(0, n)
}

function send(sessionID, state, task, detail) {
  try {
    const args = [state, "--agent", "opencode"]
    if (sessionID) args.push("--session", String(sessionID))
    if (task) args.push("--task", String(task))
    if (detail) args.push("--detail", String(detail))
    const child = spawn(NOTIFY, args, { stdio: "ignore", detached: true })
    child.on("error", () => {})
    child.unref()
  } catch {}
}

export const AgentPingPlugin = async (ctx) => {
  return {
    "chat.message": async (input, output) => {
      try {
        const sid = input?.sessionID || output?.message?.sessionID || ""
        const parts = Array.isArray(output?.parts) ? output.parts : []
        const text = parts
          .filter((p) => p && p.type === "text" && !p.synthetic && p.text)
          .map((p) => p.text)
          .join(" ")
        if (trim(text, 1)) tasks.set(sid, trim(text, 200))
      } catch {}
    },
    "permission.ask": async (input) => {
      try {
        const p = input || {}
        let detail = trim(p.title, 200)
        const meta = p.metadata
        if (meta && typeof meta === "object") {
          const m = meta.command || meta.pattern || meta.description || ""
          if (m) detail = detail ? detail + " " + trim(m, 300) : trim(m, 300)
        }
        send(p.sessionID, "waiting", tasks.get(p.sessionID) || "", detail || p.type || "permission requested")
      } catch {}
    },
    event: async ({ event }) => {
      try {
        const type = event?.type || ""
        const props = event?.properties || {}
        const sid = props.sessionID || props.info?.sessionID || ""

        if (type === "session.status" && props.status && props.status.type === "busy") {
          failedSent.delete(sid)
          send(sid, "started", tasks.get(sid) || "")
          return
        }
        if (type === "session.error") {
          const e = props.error
          let detail = ""
          if (e) {
            detail = e.message || (e.data && e.data.message) || ""
            if (!detail) { try { detail = JSON.stringify(e) } catch {} }
          }
          failedSent.add(sid)
          send(sid, "failed", tasks.get(sid) || "", trim(detail, 500))
          return
        }
        if (type === "session.idle") {
          if (failedSent.has(sid)) return // failed already pushed; don't follow it with a finished
          send(sid, "finished", tasks.get(sid) || "")
          return
        }
      } catch {}
    },
  }
}
