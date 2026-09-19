// AgentPing reporter plugin for OpenCode 2.x (DESIGN.md §3.6)
// Install: ~/.config/opencode/plugins/agentping.js  (needs agent-notify + agentping.conf)
//
// OpenCode 2 plugin contract (verified on 2.0.10): the module must default-export
//   { id: string, setup(ctx) }   — or { id, effect }
// otherwise the loader rejects it with
//   "Plugin must export a default definition with an id and an effect or setup function."
// The bare specifier `@opencode/plugin` does not resolve; only node: builtins are imported.
// setup() may return a disposer, which the loader calls on unload/reload.
//
// V2 hook/event surface (verified live on 2.0.10):
//   ctx.permission.hook("evaluate", p)  p={sessionID,agent,action,resources,metadata,effect}
//       -> waiting when p.effect === "ask" (hook may set p.effect/p.message)
//   ctx.event.subscribe({ signal })     events carry `.data` (no `.properties`)
//       session.inbox.enqueued          data.item.payload.text = this round's user prompt
//       session.execution.started       -> started (the reporter may not publish it)
//       session.execution.succeeded     -> finished (with task)
//       session.execution.failed        -> failed (task + error detail)
//       session.execution.interrupted   -> failed (task + "interrupted")
//   NOTE: V1's session.idle / session.status / session.error events and the session.hook("prompt")
//   hook do NOT exist in 2.x — the prompt hook registers but never fires.
// finished/failed must carry task: otherwise the notification body is just the session id.
// Swallow every exception silently; never affect opencode itself.
// Windows: Node spawn cannot run the bash reporter directly (ENOENT), so call Git Bash with the script path.

import { spawn } from "node:child_process"
import { existsSync } from "node:fs"
import { homedir } from "node:os"

// Resolve agent-notify: explicit env var > ~/bin (macOS/Windows install path) > /usr/local/bin (Linux) > PATH fallback
const HOME = process.env.HOME || process.env.USERPROFILE || homedir() || ""

function firstExisting(paths) {
  return paths.find((p) => p && existsSync(p)) || ""
}

const NOTIFY =
  firstExisting([
    process.env.AGENTPING_NOTIFY,
    HOME + "/bin/agent-notify",
    HOME + "\\bin\\agent-notify",
    "/usr/local/bin/agent-notify",
    "/opt/homebrew/bin/agent-notify",
  ]) || "agent-notify"

const GIT_BASH =
  process.platform === "win32"
    ? firstExisting([
        process.env.AGENTPING_BASH,
        "C:\\Program Files\\Git\\bin\\bash.exe",
        "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
      ])
    : ""

const tasks = new Map() // sessionID → latest user prompt
const failedSent = new Set() // sessionIDs that already pushed failed

function trim(s, n) {
  return String(s == null ? "" : s)
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, n)
}

function send(sessionID, state, task, detail) {
  try {
    const args = [state, "--agent", "opencode"]
    if (sessionID) args.push("--session", String(sessionID))
    if (task) args.push("--task", String(task))
    if (detail) args.push("--detail", String(detail))
    // On Windows, detached spawn of a bash script fails with ENOENT; drive via Git Bash instead.
    let child
    if (GIT_BASH && NOTIFY !== "agent-notify") {
      const script = String(NOTIFY).replaceAll("\\", "/")
      child = spawn(GIT_BASH, [script, ...args], {
        stdio: "ignore",
        detached: true,
        windowsHide: true,
      })
    } else {
      child = spawn(NOTIFY, args, { stdio: "ignore", detached: true })
    }
    child.on("error", () => {})
    child.unref()
  } catch {}
}

/** Events arrive as `.data` on 2.x; tolerate the older `.properties` shape. */
function payload(event) {
  return event?.data || event?.properties || event || {}
}

/** This round's user prompt, from whatever source we captured it. */
function cacheTask(sid, text) {
  const t = trim(text, 200)
  if (sid && t) tasks.set(sid, t)
}

function errorDetail(err) {
  if (!err) return ""
  let detail = err.message || (err.data && err.data.message) || ""
  if (!detail) {
    try {
      detail = JSON.stringify(err)
    } catch {}
  }
  return trim(detail, 500)
}

export default {
  id: "agentping",
  async setup(ctx) {
    // waiting: fire only when the evaluated permission actually asks the user.
    try {
      await ctx.permission.hook("evaluate", (event) => {
        try {
          if (event?.effect !== "ask") return
          const sid = event.sessionID || ""
          const resources = Array.isArray(event.resources) ? event.resources.filter(Boolean).join(" ") : ""
          const meta = event.metadata && typeof event.metadata === "object" ? event.metadata : {}
          const metaBit = meta.command || meta.pattern || meta.description || ""
          let detail = trim(event.action, 200)
          if (resources) detail = detail ? detail + " " + trim(resources, 300) : trim(resources, 300)
          if (metaBit) detail = detail ? detail + " " + trim(metaBit, 300) : trim(metaBit, 300)
          send(sid, "waiting", tasks.get(sid) || "", detail || "permission requested")
        } catch {}
      })
    } catch {}

    const controller = new AbortController()
    void (async () => {
      try {
        for await (const event of ctx.event.subscribe({ signal: controller.signal })) {
          try {
            const type = event?.type || ""
            const props = payload(event)
            const sid = props.sessionID || ""

            // Cache this round's task as soon as the user prompt is queued.
            if (type === "session.inbox.enqueued") {
              const item = props.item || {}
              const text = item?.payload?.text ?? props.text ?? ""
              cacheTask(sid, typeof text === "string" ? text : "")
              continue
            }
            if (type === "session.execution.started") {
              failedSent.delete(sid)
              send(sid, "started", tasks.get(sid) || "")
              continue
            }
            if (type === "session.execution.succeeded") {
              send(sid, "finished", tasks.get(sid) || "")
              continue
            }
            if (type === "session.execution.failed" || type === "session.execution.interrupted") {
              failedSent.add(sid)
              send(
                sid,
                "failed",
                tasks.get(sid) || "",
                errorDetail(props.error) || trim(props.message, 500) || (type.endsWith("interrupted") ? "interrupted" : ""),
              )
              continue
            }
            // Fallback for builds that still emit the legacy event names.
            if (type === "session.status" && props.status && props.status.type === "busy") {
              failedSent.delete(sid)
              send(sid, "started", tasks.get(sid) || "")
              continue
            }
            if (type === "session.error") {
              failedSent.add(sid)
              send(sid, "failed", tasks.get(sid) || "", errorDetail(props.error))
              continue
            }
            if (type === "session.idle") {
              if (failedSent.has(sid)) continue
              send(sid, "finished", tasks.get(sid) || "")
              continue
            }
          } catch {}
        }
      } catch {}
    })()

    // Disposer: the loader invokes the function returned by setup() on unload/reload.
    return () => {
      try {
        controller.abort()
      } catch {}
    }
  },
}
