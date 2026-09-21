// AgentPing reporter plugin for OpenCode 2.x (DESIGN.md §3.6)
// Install: ~/.config/opencode/plugins/agentping.js  (needs agent-notify + agentping.conf)
//
// OpenCode 2 plugin contract (verified on 2.0.10 / 2.0.11): the module must default-export
//   { id: string, setup(ctx) }   — or { id, effect }
// otherwise the loader rejects it with
//   "Plugin must export a default definition with an id and an effect or setup function."
// The bare specifier `@opencode/plugin` does not resolve; only node: builtins are imported.
// setup() may return a disposer, which the loader calls on unload/reload.
//
// V2 hook/event surface:
//   ctx.permission.hook("evaluate", p)  p={sessionID,agent,action,resources,metadata,effect}
//       -> waiting when p.effect === "ask" (hook may set p.effect/p.message)
//   ctx.event.subscribe({ signal })     events carry `.data` (no `.properties`)
//       session.inbox.enqueued          data.item.payload.text = this round's user prompt
//       session.step.started            -> started (the reporter may not publish it)
//       session.step.ended              finish=tool-calls: more tool rounds coming, ignore
//                                       finish=stop|length|unknown: this turn finished
//                                       finish=error|content-filter: this turn failed
//       session.step.failed             -> failed (task + error detail)
//       session.execution.started       -> started (fallback; TUI interactive runner stays
//                                         alive across turns, so this fires once per session)
//       session.execution.succeeded     -> finished if this turn has not already notified
//                                         (covers `opencode run` one-shot sessions)
//       session.execution.failed        -> failed (task + error detail)
//       session.execution.interrupted   -> failed (task + "interrupted")
//   NOTE: V1's session.idle / session.status / session.error events and the session.hook("prompt")
//   hook do NOT exist in 2.x — the prompt hook registers but never fires.
//   2.0.11 TUI: the Session runner only publishes session.execution.succeeded when the
//   whole session settles, not after each user turn. Per-turn notifications must use
//   session.step.ended (verified live on 2.0.11).
// finished/failed must carry task: otherwise the notification body is just the session id.
// Swallow every exception silently; never affect opencode itself.
// Windows: Node spawn cannot run the bash reporter directly (ENOENT), so call Git Bash with the script path.
// Duplicate fan-out: the plugin is loaded once per location and the event bus is global, so
// filter by event.location.directory and collapse same-process repeats via once().
// Subagent sessions (session.parentID set) are skipped: the user cannot intervene there.

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
const failedSent = new Set() // sessionIDs that already pushed failed this turn
const turnDone = new Set() // sessionIDs that already pushed finished/failed this turn
const recentlySent = new Map() // dedupeKey → timestamp (same-process multi-location fan-out)
const childSessions = new Map() // sessionID → true if this is a subagent (has parentID)

function trim(s, n) {
  return String(s == null ? "" : s)
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, n)
}

function once(key, ms = 2000) {
  const now = Date.now()
  const prev = recentlySent.get(key) || 0
  if (now - prev < ms) return false
  recentlySent.set(key, now)
  if (recentlySent.size > 200) {
    for (const [k, ts] of recentlySent) {
      if (now - ts > ms) recentlySent.delete(k)
    }
  }
  return true
}

function send(sessionID, state, task, detail) {
  try {
    const args = [state, "--agent", "opencode"]
    if (sessionID) args.push("--session", String(sessionID))
    if (task) args.push("--task", String(task))
    if (detail) args.push("--detail", String(detail))
    const dedupe = [sessionID, state, task || "", detail || ""].join("\0")
    if (!once(dedupe)) return
    const env = { ...process.env }
    if (HOME && !env.HOME) env.HOME = HOME
    if (HOME && !env.USERPROFILE) env.USERPROFILE = HOME
    // On Windows, detached spawn of a bash script fails with ENOENT; drive via Git Bash instead.
    let child
    if (GIT_BASH && NOTIFY !== "agent-notify") {
      const script = String(NOTIFY).replaceAll("\\", "/")
      child = spawn(GIT_BASH, [script, ...args], {
        stdio: "ignore",
        detached: true,
        windowsHide: true,
        env,
      })
    } else {
      child = spawn(NOTIFY, args, { stdio: "ignore", detached: true, env })
    }
    child.on("error", () => {})
    child.unref()
  } catch {}
}

/** Events arrive as `.data` on 2.x; tolerate the older `.properties` shape. */
function payload(event) {
  return event?.data || event?.properties || event || {}
}

function eventDirectory(event) {
  return event?.location?.directory || event?.directory || ""
}

function isLocalEvent(event, here) {
  if (!here) return true
  const dir = eventDirectory(event)
  if (!dir) return true
  return dir === here
}

/** This round's user prompt, from whatever source we captured it. */
function cacheTask(sid, text) {
  const t = trim(text, 200)
  if (sid && t) tasks.set(sid, t)
}

function beginTurn(sid) {
  if (!sid) return
  failedSent.delete(sid)
  turnDone.delete(sid)
}

function markTurnDone(sid) {
  if (sid) turnDone.add(sid)
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

const STEP_FAILED_FINISH = new Set(["error", "content-filter"])

function parentIdOf(info) {
  return info?.parentID || info?.parent_id || info?.parentSessionID || ""
}

export default {
  id: "agentping",
  async setup(ctx) {
    const here = ctx?.location?.directory || ""

    // Subagent sessions have parentID. The user cannot intervene there, so skip all
    // notifications (finished/failed/waiting). Lookup is cached; get() failure → notify
    // (fail-open so a parent session is never dropped because of a transient miss).
    async function isChildSession(sid) {
      if (!sid) return false
      if (childSessions.has(sid)) return childSessions.get(sid)
      let child = false
      try {
        const info = await ctx.session.get({ sessionID: sid })
        const row = info && typeof info === "object" && info.data ? info.data : info
        child = !!parentIdOf(row)
      } catch {}
      childSessions.set(sid, child)
      return child
    }

    // waiting: fire only when the evaluated permission actually asks the user.
    try {
      await ctx.permission.hook("evaluate", async (event) => {
        try {
          if (event?.effect !== "ask") return
          const sid = event.sessionID || ""
          if (await isChildSession(sid)) return
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
            if (!isLocalEvent(event, here)) continue
            const type = event?.type || ""
            const props = payload(event)
            const sid = props.sessionID || ""
            if (sid && (await isChildSession(sid))) continue

            // Cache this round's task as soon as the user prompt is queued.
            if (type === "session.inbox.enqueued") {
              const item = props.item || {}
              const text = item?.payload?.text ?? props.text ?? ""
              cacheTask(sid, typeof text === "string" ? text : "")
              beginTurn(sid)
              continue
            }
            if (type === "session.step.started" || type === "session.execution.started") {
              beginTurn(sid)
              send(sid, "started", tasks.get(sid) || "")
              continue
            }
            // Interactive TUI (2.0.11): one execution spans many user turns.
            // A step that ends with tool-calls is mid-turn; stop/length/unknown is the turn finishing.
            if (type === "session.step.ended") {
              const finish = props.finish || ""
              if (finish === "tool-calls") continue
              if (turnDone.has(sid)) continue
              markTurnDone(sid)
              if (STEP_FAILED_FINISH.has(finish)) {
                failedSent.add(sid)
                send(sid, "failed", tasks.get(sid) || "", errorDetail(props.error) || finish)
              } else {
                send(sid, "finished", tasks.get(sid) || "", finish === "length" ? "length" : "")
              }
              continue
            }
            if (type === "session.step.failed") {
              if (turnDone.has(sid)) continue
              markTurnDone(sid)
              failedSent.add(sid)
              send(sid, "failed", tasks.get(sid) || "", errorDetail(props.error) || trim(props.message, 500))
              continue
            }
            if (type === "session.execution.succeeded") {
              if (turnDone.has(sid)) continue
              markTurnDone(sid)
              send(sid, "finished", tasks.get(sid) || "")
              continue
            }
            if (type === "session.execution.failed" || type === "session.execution.interrupted") {
              if (turnDone.has(sid) && failedSent.has(sid)) continue
              markTurnDone(sid)
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
              beginTurn(sid)
              send(sid, "started", tasks.get(sid) || "")
              continue
            }
            if (type === "session.error") {
              if (turnDone.has(sid) && failedSent.has(sid)) continue
              markTurnDone(sid)
              failedSent.add(sid)
              send(sid, "failed", tasks.get(sid) || "", errorDetail(props.error))
              continue
            }
            if (type === "session.idle") {
              if (failedSent.has(sid) || turnDone.has(sid)) continue
              markTurnDone(sid)
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
