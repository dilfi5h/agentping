// AgentPing reporter for pi（DESIGN.md §3.6）
// 安装: ~/.pi/agent/extensions/agentping.js（需先装好 agent-notify + /etc/agentping.conf）
// 事件映射:
//   before_agent_start → started（task = 用户 prompt 片段）
//   agent_end(stopReason=error) → failed（随后可能重试，重试会再推 started）
//   agent_settled → finished（pi 不再自动续跑时才算完成；若本 run 已推 failed 则跳过）

let failedThisRun = false

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
    pi.exec("agent-notify", args, { timeout: 8000 }).catch(() => {})
  }

  pi.on("before_agent_start", async (event, ctx) => {
    failedThisRun = false
    const task = (event.prompt || "").replace(/\s+/g, " ").trim()
    send(ctx, "started", { "--task": task })
  })

  pi.on("agent_end", async (event, ctx) => {
    const last = event.messages?.[event.messages.length - 1]
    if (last?.stopReason === "error") {
      failedThisRun = true
      const detail = last.errorMessage || last.error || "agent run error"
      send(ctx, "failed", { "--detail": detail })
    }
  })

  pi.on("agent_settled", async (_event, ctx) => {
    if (failedThisRun) return // failed 已推，避免失败后又跟一条已完成
    send(ctx, "finished", {})
  })
}
