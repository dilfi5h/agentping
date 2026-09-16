// AgentPing reporter plugin for OpenCode (DESIGN.md §3.6)
// Install: ~/.config/opencode/plugins/agentping.js  (needs agent-notify + /etc/agentping.conf)
// Event mapping:
//   chat.message        → cache task（本轮 prompt，权威来源：output.parts）
//   session.status(busy)→ started（reporter 可能不发布）
//   session.idle        → finished（带 task；本 session 已推 failed 则跳过）
//   session.error       → failed（task + 错误摘要）
//   permission.ask      → waiting（task + 权限标题/命令）
// finished/failed 必须带 task：否则通知正文只剩 session id。
// 任何异常静默吞掉，绝不影响 opencode 本身。
// 注：UserMessage 类型本身不含 parts（文本在消息事件之外），所以 task 捕获必须走
// chat.message 的 output.parts，不能依赖 message.updated 事件载荷。

import { spawn } from "node:child_process"
import { existsSync } from "node:fs"

// 解析 agent-notify：显式环境变量 > ~/bin（macOS 安装路径）> /usr/local/bin（Linux 安装路径）> PATH 兜底
const HOME = process.env.HOME || ""
const NOTIFY =
  [
    process.env.AGENTPING_NOTIFY,
    HOME + "/bin/agent-notify",
    "/usr/local/bin/agent-notify",
    "/opt/homebrew/bin/agent-notify",
  ].find((p) => p && existsSync(p)) || "agent-notify"
const tasks = new Map() // sessionID → 最近一轮用户 prompt
const failedSent = new Set() // 已推过 failed 的 sessionID

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
          if (failedSent.has(sid)) return // failed 已推，避免失败后又跟一条已完成
          send(sid, "finished", tasks.get(sid) || "")
          return
        }
      } catch {}
    },
  }
}
