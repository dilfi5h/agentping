# AgentPing 设计文档

> AgentPing：通用 coding-agent 任务状态推送。任何 agent 在任何服务器上跑任务，
> 手机实时收到状态卡片，可回看历史。只读、省电、零侵入。
> 与 PiPilot 平行独立（PiPilot = 操控，AgentPing = 通知），不复用代码。

状态：设计定稿 2026-09-12。进度：M1 服务器 ✅（见 §5）、M3 App MVP ✅（v0.0.1 已发，
仓库 github.com/dilfi5h/agentping，verify 走 tag→CI→deb 拉取）、M2 进行中（agent-notify ✅ +
pi 钩子 ✅，zcode/claude 未接）。
补充定稿（2026-09-12 开工会）：① topic 发现 = reporter 双写总 topic（见 §3.3/§3.4）；② waiting 是只读快照、无解除事件；③ 钩子路径不带 `dur`（仅 L2 包装提供）；④ 时间线排序一律用 ntfy 帧 `time`，`ts` 仅展示。

## 1. 目标与非目标

**目标（V1）**

- 单向只读推送：agent 任务的四种状态（started / finished / failed / waiting）
- 电池优先：稳态功耗目标 < 1%/天（无轮询、无唤醒锁滥用、一条长连做全部订阅）
- 性能优先：消息端到端延迟 < 2s（局域网质量的服务器 < 1s）；历史查询本地 Room，毫秒级
- 覆盖：有钩子的 agent 走原生集成（pi / zcode / Claude Code / Codex / OpenCode / Gemini CLI），没有的走进程包装兜底（100% 覆盖）
- 肉眼兼容：消息不用 App 也能读（ntfy 官方 App / 短信式 fallback）

**非目标（V1 明确不做）**

- ❌ 任何写操作：不批准、不中止、不回复。App 没有发布 token，服务端权限层面禁写
- ❌ 双向通道、远程终端、文件传输
- ❌ 桌面端、多用户、Web 界面
- ❌ tmux 抓屏式状态识别（V2 再议，启发式易碎）

## 2. 总体架构

```
┌─ 服务器(deb 等) ──────────────────────────┐      ┌─ 手机 ─────────────┐
│ agent(hook) ─▶ agent-notify ──HTTPS POST──┼─▶ ntfy ─WebSocket──▶ AgentPing │
│            (~30行脚本, 组JSON+curl)        │    (systemd,    foreground      │
│ agentping run <cmd> (进程包装兜底)          │     只读token)   service+Room   │
└──────────────────────────────────────────┘      └───────────────────┘
```

组件职责：

- **agent-notify**（服务器，~30 行 bash）：唯一的服务器侧程序。组装 JSON、带 token
  curl POST 到 ntfy、5 秒超时、任何错误静默退出 0（绝不影响 agent 本身）
- **ntfy**（服务器，官方单二进制 + systemd）：消息总线。自带鉴权、离线缓存、
  topic 权限、keepalive。不写任何自定义代码
- **AgentPing App**（Android）：前台服务持一条 WebSocket 订阅全部 topic；
  Room 本地历史；系统通知；卡片时间线 UI

## 3. 协议规范（v1，详细版）

### 3.1 传输载体与双格式设计

推送载体是 ntfy 的 message 字段。**一条消息两种读法**（关键设计）：

- `title` = 人类可读摘要，模板 `[<{host}>] {agent} {状态中文}`，例：`[deb] zcode 等待批准`
- `message` = **单行 JSON**（§3.2 结构化载荷）

效果：
- AgentPing 解析 message 的 JSON → 结构化卡片；解析失败（非法 JSON）→ 整条按纯文本渲染，永不崩
- 用 ntfy 官方 App 订阅同一 topic 时，title + message 也能读（message 是 JSON 串，可读性可接受）——这是调试和兜底通道

### 3.2 载荷 Schema（AgentPing v1）

```json
{
  "v": 1,
  "agent": "zcode",
  "host": "deb",
  "state": "waiting",
  "task": "修复登录bug",
  "detail": "PermissionRequest: Bash(rm -rf /tmp/x)",
  "session": "sess_fe97e70b",
  "ts": 1760000000000,
  "dur": 123000
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `v` | int | ✅ | 协议版本，恒为 1。App 收到 v>1 时按能力降级渲染（未知字段一律忽略） |
| `agent` | string | ✅ | 小写标识：`pi` `zcode` `claude` `codex` `gemini` `opencode` `shell`（L2 包装默认值）或其它自定义串。App 未知 agent 按通用样式渲染 |
| `host` | string | ✅ | 机器名，reporter 用 `hostname -s` 自动取，可被 `--host` 覆盖 |
| `state` | string | ✅ | 四选一，小写：`started` / `finished` / `failed` / `waiting`。waiting 是只读快照，无解除事件（本机批准后任务继续跑，waiting 不撤回，后续 finished/failed 自然覆盖时间线）。**`started` 仍为合法 state（钩子可继续传），但 reporter 不发布**（高频无行动价值；App 若偶发收到也只进时间线不弹通知） |
| `task` | string | ❌ | 任务一句话摘要，建议 ≤80 字符。来源：钩子上下文里能拿到的 prompt 片段/文件名，拿不到就省略 |
| `detail` | string | ❌ | 补充信息 ≤500 字符：退出码、错误消息、权限请求的命令原文 |
| `session` | string | ❌ | 会话标识（原样透传，App V1 只显示不解析）。V2 远程操作的寻址钥匙 |
| `ts` | long | ❌ | 毫秒时间戳，reporter 侧时钟，**仅展示用**。缺省用 ntfy 落库时间。多服务器时钟漂移不可信，时间线排序一律用 ntfy 帧自带 `time` |
| `dur` | long | ❌ | 任务时长毫秒，仅 finished/failed 有意义。**钩子路径不提供**（钩子是无状态单次触发进程）；仅 `agentping run` 进程包装能算 | |

**校验规则**（App 侧宽松、reporter 侧严格）：

- reporter：state/agent 非法值直接拒绝发送（本地 stderr 报错）；JSON 单行，禁止换行
- App：任何字段缺失/类型不符 → 该字段按默认值，消息仍显示（title + 原文），仅 schema 校验整体失败时降级纯文本
- 版本策略：加字段 = v1 不变（向后兼容）；改字段语义 = v2，App 按 `v` 分支

### 3.3 ntfy 发布请求（reporter → ntfy）

```bash
# 双写总 topic：ntfy 发布不支持逗号多 topic（订阅才支持），所以是两次 POST
curl -m 5 -s -o /dev/null \
  -H "Authorization: Bearer tk_publish_xxx" \
  -H "Title: [deb] zcode 等待批准" \
  -H "Tags: robot,hourglass" \
  -H "Priority: high" \
  -H "Markdown: no" \
  -d '{"v":1,"agent":"zcode",...}' \
  https://ntfy.871116.xyz/agentping-deb
curl -m 5 -s -o /dev/null \
  -H "Authorization: Bearer tk_publish_xxx" -H "Title: ..." \
  -d '{"v":1,...}' \
  https://ntfy.871116.xyz/agentping-all
```

- **双写总 topic（topic 发现机制）**：reporter 对 `agentping-<host>` 和 `agentping-all` 各发一次 POST。AgentPing App 只订 `agentping-all`，加新服务器 App 零配置；`agentping-<host>` 保留给 ntfy 官方 App 按机订阅/调试
- 用 HTTP header 携带 title/tags/priority（JSON 发布体也行，二选一，统一用 header + body，body 即载荷 JSON，避免双层转义）
- **优先级映射**：started=**不推**（reporter 对 `started` 直接 exit 0）/ finished=default(3) / failed=high(4) / waiting=high(4)。urgent(5) 留给 V2 手动测试
- **tags（ntfy emoji）**：`robot` 固定带；状态附加：finished=`white_check_mark`、failed=`x`、waiting=`hourglass_flowing_sand`（started 不发布，无 tag）
- 超时 5 秒、静默失败——钩子永远不能卡住 agent

### 3.4 App 订阅协议（ntfy JSON stream）

- 连接：`WSS ntfy.871116.xyz/agentping-all/ws?since=<last_id>`（**App 只订总 topic `agentping-all`**，见 §3.3 双写机制）
  - 认证走 okhttp 请求头 `Authorization: Bearer tk_read_xxx`（无需 query 参数）
  - `since` = 本地持久化的最后一条 ntfy message id → 断线重连零丢失，不重不漏
- 流格式：换行分隔 JSON，三类帧：
  - `{"event":"open",...}` 连接就绪 → 重置退避计时器
  - `{"event":"keepalive",...}` 服务端 ~30s 心跳 → 忽略（okhttp 自带 ping 兜底 NAT）
  - `{"event":"message","id":"...","time":...,"title":...,"message":...}` → 处理
- 处理管线：id 去重（ntfy 幂等键）→ message 字段 JSON 解析 → Room 落库（IO 线程）→ StateFlow 更新 UI + 系统通知

### 3.5 reporter（agent-notify）CLI 规范

```
agent-notify <state> [选项]
  --agent <name>    默认 shell
  --host <name>     默认 hostname -s
  --task <text>     摘要
  --detail <text>   补充
  --session <id>
  --dur <ms>
配置来源(优先级): 环境变量 AGENTPING_URL / AGENTPING_TOKEN > /etc/agentping.conf (KEY=VALUE)
行为约束: 任何自身错误 → stderr 一行 + exit 0；永不阻塞、永不影响 agent 退出码
started: 合法 state，钩子可继续调用；reporter 静默 exit 0，不 POST（统一 choke point，各 agent 钩子不必改）
```

### 3.6 各 agent 钩子接线（M2 范围：pi + zcode + Claude Code）

| agent | 触发机制 | 事件 → 状态映射 |
|---|---|---|
| **pi** | extension `~/.pi/agent/extensions/agentping.js`（✅ 2026-09-12 已落地并真机验证；API：`before_agent_start`/`agent_end`/`agent_settled`，`pi.exec` 调 agent-notify） | `before_agent_start`→started(task=prompt片段；reporter 可能不发布)，缓存本轮 task；`agent_end`(stopReason=error)→failed(task+detail=错误原文)，`agent_settled`→finished(task=本轮 prompt；本 run 已推 failed 则跳过)。**finished/failed 必须带 task**，否则 started 被吞后通知正文只剩 session id |
| **zcode** | `~/.zcode/cli/config.json` 顶层 `hooks`（⚠ 必须 `enabled:true`，默认禁用） | `SessionStart`→started, `Stop`→finished, `PostToolUseFailure`→不推(噪音)，`PermissionRequest`→waiting；matcher 注意大小写敏感正则；command 型钩子 timeout 单位是秒 |
| **Claude Code** | `~/.claude/settings.json` hooks | `UserPromptSubmit`→started, `Stop`→finished, `Notification`→waiting（CC 的权限提醒走这个事件） |
| **Codex** | `~/.codex/config.toml` 的 `notify` | agent-start/agent-end JSON 参数 → started/finished |
| **OpenCode / Gemini CLI** | plugin / hooks（开工时查当前版本文档） | 同型映射 |
| **其它一切**（L2 兜底） | `agentping run -- <cmd>` | 启动→started；退出码 0→finished(dur)，非 0→failed(dur, detail=stderr 尾部) |

## 4. App 架构（省电与性能的具体决策）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 连接方式 | 前台服务 + **单条 WebSocket** | 一个连接订阅 `agentping-all`（总 topic，见 §3.3）；ntfy keepalive 保 NAT；无任何 HTTP 轮询。Android 14+ 需声明 FGS type `dataSync` 并在设置页说明用途 |
| 丢消息保护 | `since=<last_id>` 续传 | 断线不丢、不重；last_id 存 Room，重启后接着收 |
| 重连策略 | 指数退避 1s→2s→…→60s 封顶；`open` 帧重置；网络可用性回调触发立即重连 | 省电与实时的平衡 |
| Doze/后台 | 前台服务 notification 常驻（silent、min-importance channel 可关） | Android 对后台网络的限制用 FGS 合法绕开，不用 wake-lock |
| 开机自启 | `BOOT_COMPLETED` 接收器重启服务，设置页可关 | 服务器重启后手机自动恢复订阅 |
| 通知分级 | 3 个 channel：状态(默认无声) / 失败与等待(有声+横幅) / 服务运行(最低重要性) | 只读场景的干扰控制 |
| 数据流 | WS 线程 → kotlinx-serialization 流式解析 → Room(IO) → StateFlow → Compose | 单向数据流；解析在流上做，不整段缓冲 |
| UI | 单 Activity + Compose：时间线(卡片流，按 agent/host 过滤 chips) / 设置 / 关于 | 零自定义 View |
| 依赖 | okhttp、room、kotlinx-serialization、compose——不引第三方推送/大库 | PiPilot 教训：依赖要过代理 |

**稳态功耗估算**：1 条 WS + 30s 心跳 ≈ 几 KB/小时；无 GPS/无唤醒/无轮询 → 预期 <1%/天。

## 5. 服务器侧部署（deb）——✅ 2026-09-12 已落地（联调最小配置）

实际部署（与原计划的差异已核实）：

- ntfy v2.28.0 官方单二进制 → `/usr/local/bin/ntfy`，配置 `/etc/ntfy/server.yml`，systemd `ntfy.service`（专用用户 `ntfy`），监听 `127.0.0.1:2586`
- nginx 是**源码编译版**：`/usr/local/nginx/`，vhost 目录 `/usr/local/nginx/conf/vhost/`（已加 `ntfy.871116.xyz.conf`，带 WS 升级头；管理路径 `/-/` 与 `/v1/` 仅限 127.0.0.1）
- 证书：deb 上已有泛域名证书 `*.871116.xyz`（`/root/sh/cert.pem`，2026-11-28 到期），ntfy 子域直接复用，无需 certbot
- 鉴权：`auth-default-access: deny-all`；用户/ACL/token：
  - `agentping-pub` / `tk_publish_*`：仅写 `agentping-*`（token 在 deb `/etc/agentping.conf`，M2 钩子直接用）
  - `agentping-sub` / `tk_read_*`：仅读 `agentping-*`（App 用）。**App 侧永远拿不到写 token——服务端层面实现"只读"承诺**
- 缓存：`cache-duration: 12h`；`keepalive-interval: 30s`
- ⚠ ntfy 发布不支持逗号多 topic（订阅才支持），双写必须是两次 POST，见 §3.3
- 备份要求：无状态可重建，配置文件入 dotfiles 即可

## 6. 安全

- 全链路 TLS；token 最小权限（读/写分离，见 §5）
- detail 字段可能含命令片段——钩子侧截断 500 字符；App 渲染为纯文本（无 Markdown/HTML 注入面）
- ntfy 面板/账号不暴露公网（只开 ws/publish 所需路径），管理 API 仅 localhost
- App 不申请 INTERNET 之外的危险权限（通知权限除外）

## 7. 工程与仓库

```
agentping/                    ← C:\Users\Administrator\agentping
├── DESIGN.md                 ← 本文档
├── docs/protocol.md          ← §3 单独成文（给以后接新 agent 的人看）
├── server/
│   ├── agent-notify          ← bash 脚本（唯一服务器侧程序）
│   ├── etc-agentping.conf.example
│   ├── ntfy/server.yml + systemd/ntfy.service
│   ├── hooks/{pi-extension.js, zcode-snippet.json, claude-snippet.json, codex-snippet.toml}
│   └── install.sh            ← deb 上一键装 ntfy+脚本+钩子
├── app/                      ← Android 工程（Kotlin+Compose，从零建，不复制 PiPilot 代码）
└── .github/workflows/release.yml
```

- 独立 GitHub 仓库（新建，public，同 dilfi5h 账号）
- 构建：JDK 17 + AGP 8.7.x + Compose BOM，本地 Gradle 8.9 直调（同 PiPilot 环境）；CI 沿用 setup-java/gradle + actions 模板
- 签名：**独立新 keystore**（不与 PiPilot 共用——两个 app 两个身份），开工生成，上传 GH Secrets

## 8. 开发流程（用户定版）

1. 每个功能：本地构建 verify 包 → scp deb `/dl/`（小包直传 / 大包走 CI-release+deb 拉取的老规矩）→ **真机验证**
2. 真机 OK + 用户点头 → 才 commit/push/打 tag（`v0.0.x` 语义化：状态推送为 0.1.0 起步？不——沿用 PiPilot 手工 0.0.x 习惯，versionCode 每个验证包 +1）
3. verify 中间版用 `v0.0.x-verifyN` 临时 tag + 临时 Release 通道，正式后即删（PiPilot v0.0.11 已验证的流程）
4. CI 未改动不盯 CI

## 9. 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M1 服务器通 | ntfy 上线 + 子域名 + 双 token；curl 发→手机 ntfy 官方 App 收 | 端到端 <2s |
| M2 钩子 | agent-notify 定稿 + pi/zcode/claude 三家接线 | 真实任务四态全收到 |
| M3 App MVP | WS 订阅 + 通知 + 时间线 + 设置页 | 真机装、杀进程重启自愈、断网重连 |
| M4 打磨 | 过滤/历史搜索/开机自启/通知分级 | 发 v0.0.1 正式版 |
