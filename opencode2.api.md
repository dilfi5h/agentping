# OpenCode 2.x 实测笔记（2.0.10 / 2.0.11, macOS）

> 2026-09-19 调试 agentping 插件 + 模型配置时实测整理；2026-09-20 在 2.0.11 TUI 上补了
> 「交互会话不按轮发布 `session.execution.*`」的结论。标注「二进制」的还交叉核对了
> `~/.opencode/bin/opencode` 内嵌源码，标注「实测」的跑过真实会话。
> 官方文档（opencode.ai/docs）目前仍描述 1.x 的 hooks API，**与 2.x 实际行为不符**，以本文为准。

## 0. 环境与常见坑

- 本机同时存在两套安装：
  - `~/.opencode/bin/opencode` → v2.0.11（`opencode --version`；2026-09-19 验证时是 2.0.10）
  - 全局 npm `@opencode-ai/cli` → `opencode2`（beta-18684，旧）
- 两套抢同一个后台服务端口（49374），后来者报：
  `Managed service port 49374 on 127.0.0.1 is already in use … Configure another port with opencode service set port <port>`
  症状是命令行随机 `Timed out waiting for the background service to start`。退掉多余实例即可。
- 日志：`~/.local/share/opencode/log/opencode.log`（文本格式，`grep` 友好）。
- `opencode debug config` 有时连接到已运行的后台服务，改了配置/环境变量看不到效果时，
  用带 `--standalone` 的命令（如 `opencode models --standalone --print-logs`）验证。
- `opencode plugin list` 在 reload 过程中会短暂输出 `No plugins found`，等几秒再查。
- 远程托管配置拉取失败属正常噪音（不影响自定义 provider）：
  `failed to load OpenCode provider config … 401/502 GET https://opencode.ai/console/api/v2/config`

## 1. 配置文件：输入格式 ≠ 归一化输出格式

**这是升级后「模型配置失效」的根因。** `opencode debug config` 打印的是**归一化后的内部形态**，
不能把它当配置文件的写法抄回去 —— 那样整个 provider 会被丢弃，日志里出现：

```
configuration normalization diagnostic path=$.providers.<id> kind=invalid action="skipped malformed recognized value"
```

随后任何会话报 `SessionRunnerModel.ModelUnavailableError: Model unavailable: <provider>/<model>`。

正确写法以 `https://opencode.ai/config.json`（$schema）和 /docs/config、/docs/providers 为准：

| 归一化输出（❌ 别写进配置文件） | 配置文件输入（✅） |
|---|---|
| `providers: { id: {...} }` | `provider: { id: {...} }` |
| `package: "aisdk:@ai-sdk/openai-compatible"` | `npm: "@ai-sdk/openai-compatible"` |
| `settings: { baseURL, apiKey }` | `options: { baseURL, apiKey }` |
| `models.m.capabilities.tools: true` | `models.m.tool_call: true` |
| `variants: [{ id: "xhigh", settings: {} }]` | `variants: { xhigh: {} }` |
| `agents: { plan: { system } }` | `agent: { plan: { prompt } }` |
| `permissions: [{action,resource,effect}]` | `permission: { edit: "deny", bash: "deny" }` |
| `mcp: { servers: { name: {...} } }` | `mcp: { name: {...} }`（扁平） |
| mcp `disabled: false` | `enabled: true` |

其他实测点：

- `enabled_providers` 仍有效，归一化成 `experimental.policies`（`provider.use` 的 deny-*/allow 列表）。
- 模型级 `reasoning` / `temperature` 会被静默丢弃：
  `kind=unsupported action="omitted unsupported legacy setting"`（无害，但会刷 WARN，建议不写）。
  `tool_call` 会被保留（映射成 `capabilities.tools`）。
- 每模型覆盖 SDK：`provider.<id>.models.<m>.provider.npm`。

- 验证配置是否被接受的最快方法（不动真实文件）：

  ```bash
  OPENCODE_CONFIG_CONTENT="$(cat candidate.json)" opencode models --standalone --print-logs 2>&1 \
    | grep 'normalization diagnostic' | grep -v 'opencode.json'
  # 有 source=OPENCODE_CONFIG_CONTENT 的行 = 该写法被拒
  ```

## 2. 插件契约（2.x）

加载器只接受**默认导出** `{ id, effect }` 或 `{ id, setup }`，否则：

```
PluginModule.LoadError: Plugin must export a default definition with an id and an effect or setup function.
(cause: SchemaError(Missing key at ["default"]))
```

- `@opencode/plugin` 这个包**不存在**（`Cannot find package '@opencode/plugin'`）。
  1.x 文档里的 `export const MyPlugin = async (ctx) => ({ "chat.message": ... })` 形态在 2.x 也不加载
  （`chat.message` / `tool.execute.before` / `permission.ask` 这些 hook 名在 2.0.10 二进制中出现 0 次）。
- 不需要任何 import，纯 `node:` 内置模块即可；最简骨架：

  ```js
  export default {
    id: "my-plugin",
    async setup(ctx) {
      /* 注册 hook / 订阅事件 */
      return () => { /* disposer，unload/reload 时被调用 */ }
    },
  }
  ```

- `setup()` 返回的函数会被 loader 作为 disposer 调用（用来中断事件订阅等）。
- `@opencode-ai/plugin/v2/promise` 的 `define()` 是恒等函数（`return plugin`），可用可不用；
  版本需与 `~/.config/opencode/package.json` 声明的一致。
- 本地插件放 `~/.config/opencode/plugins/`（全局）或 `<project>/.opencode/plugins/`，
  自动发现，无需在配置里写 `plugin` 数组；外部依赖靠配置目录下的 `package.json` + 启动时 `bun install`。
- 插件里**不要指望报错能冒出来**：自己写的 try/catch 会把所有失败吞掉，表象就是「加载正常但什么都没发生」。

### 2.1 插件上下文（`setup(ctx)` 的 ctx，实测 keys）

```
app, location, options, agent, aisdk, command, event, experimental, generate, model,
provider, integration, mcp, permission, plugin, reference, rpc, skill, storage, tool,
vcs, websearch, worktree, session, shell
```

常用域（二进制 + 实测）：

```js
ctx.session    // hook(name, fn, opts?), create, get, prompt, generate, interrupt, wait, context, ...
ctx.permission // hook("evaluate", fn), list, get, reply
ctx.event      // subscribe(optionsOrSignal) → async iterator
ctx.tool       // hook, transform, list, reload
ctx.storage    // get/set/remove/scan（同服务内共享）
ctx.agent / ctx.model / ctx.provider / ctx.mcp / ctx.skill / ...
```

`permission.hook("evaluate", p)` 的 payload（二进制内触发点）：
`{ sessionID, agent, action, resources, metadata, source, effect }`，
hook 内可改 `p.effect`（"deny"/"allow"）与 `p.message`。只有 `effect === "ask"` 时才真正在等用户。

### 2.2 事件流（`ctx.event.subscribe()`）

- 每条事件形如 `{ id, created, type, location: {directory}, data, durable? }`，负载在 **`.data`**（不是 1.x 的 `.properties`）。
- 订阅会收到**所有** location 的事件（全局广播），不只当前目录。
- 一次真实会话（`opencode run "..."`）观察到的事件：
  `session.inbox.enqueued/delivered, session.instructions.updated, session.execution.started/succeeded,
   session.step.started/streamed/ended, session.text.started/delta/ended,
   session.reasoning.started/delta/ended, session.usage.updated, provider.updated, model.updated,
   agent.updated, command.updated, skill.updated, websearch.updated, reference.updated,
   plugin.updated, mcp.status.changed, mcp.resources.changed, integration.updated`
- **没有** `session.idle` / `session.status` / `session.error`（V1 的名字）。
  `session.execution.*` 全集（二进制）：`started` / `succeeded` / `failed` / `interrupted`。
- **2.0.11 交互 TUI（实测）**：Session runner 在整段会话期间保持 active，`session.execution.started`
  只在会话开始发一次，`session.execution.succeeded` 要等到整段 settle（退出 / idle）才发。
  每一轮用户回合的结束信号是 `session.step.ended`（`finish` 枚举：`stop` / `length` /
  `tool-calls` / `content-filter` / `error` / `unknown`）。`finish=tool-calls` 表示还要继续调工具，
  不是这一轮结束。agentping 因此用 `session.step.*` 做按轮通知，`session.execution.*` 只给
  `opencode run` 这类一次性会话兜底。
- `session.hook("prompt")` 能注册成功但**从不触发**；真正会触发的 hook 名（二进制内被触发的）：
  session 域 `context` / `compaction` / `generate` / `title` / `retry` / `model.request` / `http.request` /
  `http.response` / `experimental.ws.*`，tool 域 `execute.before` / `execute.after`，shell 域 `create.before`。
  注册不存在的名字不报错，只是永远不回调 —— 排查时务必实测。

### 2.3 V1 → V2 映射（agentping 插件实际采用的）

| 目的 | V1 | V2 |
|---|---|---|
| 缓存本轮 prompt | `chat.message` hook / `session.hook("prompt")` | 事件 `session.inbox.enqueued` → `data.item.payload.text` |
| started | 事件 `session.status`(busy) | 事件 `session.step.started`（`session.execution.started` 兜底；交互 TUI 整段会话只发一次） |
| finished | 事件 `session.idle` | 事件 `session.step.ended` 且 `finish` 为 `stop` / `length` / `unknown`（`session.execution.succeeded` 兜底一次性 `opencode run`） |
| failed | 事件 `session.error` | 事件 `session.step.ended` 且 `finish` 为 `error` / `content-filter`，或 `session.step.failed`（`session.execution.failed` / `interrupted` 兜底） |
| waiting | hook `permission.ask` | hook `permission.hook("evaluate")` 且 `effect === "ask"` |

## 3. 排查手法（可复用）

1. **探针插件**：往 `~/.config/opencode/plugins/zz-probe.js` 放一个把 `ctx` keys、hook 注册结果、
   事件流全部 `appendFileSync("/tmp/probe.log", ...)` 的插件，`opencode reload` 后跑 `opencode run "hi"`
   再看日志。比读源码快，也不会被 try/catch 骗。
2. **验证插件是否真的调用外部命令**：临时把目标命令换成 wrapper
   （`mv agent-notify agent-notify.real` + 一个 `echo "$*" >> /tmp/x.log; exec ...real "$@"` 的 sh），
   跑一次会话看日志，**记得还原**。
3. 二进制里找权威 API：`strings -a ~/.opencode/bin/opencode > /tmp/oc.txt`，然后
   `grep -o -E 'hook\("[a-z_.]+"' /tmp/oc.txt | sort | uniq -c`、
   `grep -o -E 'trigger\("[a-z]+","[a-z._]+"' /tmp/oc.txt | sort | uniq -c`，
   能直接列出所有 hook / 事件名（比翻文档准）。
4. 配置校验：`OPENCODE_CONFIG_CONTENT=... opencode models --standalone --print-logs`（见 §1）。

## 4. 已知副作用

- 插件是全局的：**每个**正在运行的 opencode 进程都会订阅同一事件流并对同一会话各发一次通知，
  未缓存到 task 的实例发出的通知只有 session id（task 为空）。多会话并存时需要跨进程去重
  （可用 `ctx.storage`），或保证只跑一个实例。
- 同一事件在同进程内会被多个插件实例（多 location）重复投递，回调里自己做幂等。
  agentping 按 `event.location.directory === ctx.location.directory` 过滤，再对
  `(session, state, task, detail)` 做短窗口去重。
