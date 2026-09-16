# AgentPing

通用 coding-agent 任务状态推送。

服务器上的 agent 一跑任务，手机实时收到状态卡片，可回看历史。  
**只读、省电、零侵入**——与 [PiPilot](https://github.com/dilfi5h)（操控）平行独立，不复用代码。

```
agent(hook) → agent-notify → ntfy → AgentPing App
```

## 现状

| 部分 | 状态 |
|---|---|
| ntfy 消息总线 | ✅ |
| `agent-notify` reporter（Linux / Windows / macOS） | ✅ |
| Android App（时间线 / 通知 / 续传） | ✅ |
| pi 钩子 | ✅ |
| opencode 插件 | ✅ |
| zcode 钩子（Linux / Windows / macOS，bash 3.2 兼容） | ✅ |
| Claude Code 钩子 | 未接 |

当前正式版：[v0.0.6](https://github.com/dilfi5h/agentping/releases/tag/v0.0.6)

## 下载 App

- 正式版 APK：<https://github.com/dilfi5h/agentping/releases/latest>
- 开发通道（`main` 最新构建）：<https://github.com/dilfi5h/agentping/releases/tag/dev>

安装后在设置里填：

| 项 | 说明 |
|---|---|
| URL | 你的 ntfy 地址，例如 `https://ntfy.example.com` |
| Token | **只读** token（App 永远不要放写 token） |
| Topic | 默认 `agentping-all` |

首次安装请打开通知权限；建议在系统设置里给 AgentPing 电池优化白名单，避免后台 WebSocket 被掐。

## 它做什么 / 不做什么

**做：**

- 四种状态：`started` / `finished` / `failed` / `waiting`
- 一条 WebSocket 长连订阅，断线按 `since=<last_id>` 续传
- 本地 Room 历史；失败与等待走高优先级通知

**不做（V1）：**

- 任何写操作（批准 / 中止 / 回复）
- 远程终端、文件传输、桌面端、多用户 Web

说明：`started` 钩子仍可调用，但 **reporter 默认不发布**（高频无行动价值）；App 即使收到也只进时间线、不弹通知。

## 服务器侧

前置：已有可用的 ntfy（鉴权、读写 token 分离；写权限仅限 `agentping-*`）。

安装脚本按平台分开，**不要混用**：

| 平台 | 命令 | 装到哪里 |
|---|---|---|
| Linux | `sudo ./install.sh` | `/usr/local/bin/agent-notify` + pi 扩展 |
| Windows（Git Bash） | `./install-win.sh` | `~/bin/agent-notify` + python 发布辅助 + zcode hook 文件 |
| macOS | `./install-macos.sh` | `~/bin/agent-notify` + zcode hook 文件 |

### Linux

```bash
# 在仓库 server/ 目录
sudo ./install.sh
```

会安装：

- `/usr/local/bin/agent-notify`（Linux curl 版）
- `~/.pi/agent/extensions/agentping.js`（若本机用 pi）
- `~/.config/opencode/plugins/agentping.js`（若本机用 opencode）

若没有 `/etc/agentping.conf`，按提示创建（`chmod 600`）：

```bash
AGENTPING_URL=https://ntfy.example.com
AGENTPING_TOKEN=<publish-token>
# 可选：容器等场景覆盖主机名
# AGENTPING_HOST=deb
```

### Windows（Git Bash）

依赖：Git Bash、`python`、能访问你的 ntfy。

```bash
# 在仓库 server/ 目录
./install-win.sh
# 可选：./install-win.sh --host win
# 可选：./install-win.sh --skip-zcode   # 只装 reporter
```

会安装：

- `~/bin/agent-notify`（来自 `agent-notify.win`）
- `~/bin/agentping-ntfy-body.py`（UTF-8 JSON 发布，避免 Windows curl 中文 Title 乱码）
- `~/bin/agentping-zcode-hook` + 生成好的 snippet（除非 `--skip-zcode`）
- `~/bin/agentping-zcode-hook-parse.py`（hook 的 stdin JSON 解析后端；有 `jq` 时不依赖）
- 若无配置则创建 `~/.agentping.conf` 模板

然后把生成的 `~/bin/agentping-zcode-snippet.json` **手工合并**进 `~/.zcode/cli/config.json`（必须 `hooks.enabled: true`），再重开 ZCode 会话。

### macOS

依赖：系统自带 bash 3.2 即可跑 hook；`jq` 或 `python3` 任一在 PATH 上（hook 解析 stdin JSON 用，依次探测 `jq` → `python3` → `python`，都没有则钩子静默跳过、不影响 agent）。

```bash
# 在仓库 server/ 目录
./install-macos.sh
# 可选：./install-macos.sh --host mac
# 可选：./install-macos.sh --skip-zcode   # 只装 reporter
```

会安装：

- `~/bin/agent-notify`（Linux curl 版，macOS 直接可用）
- `~/bin/agentping-zcode-hook` + 生成好的 snippet（除非 `--skip-zcode`）
- `~/bin/agentping-zcode-hook-parse.py`（hook 的 stdin JSON 解析后端；有 `jq` 时不依赖）
- `~/.config/opencode/plugins/agentping.js`（若本机用 opencode）
- 若无配置则创建 `~/.agentping.conf` 模板（`chmod 600`）

然后把生成的 `~/bin/agentping-zcode-snippet.json` **手工合并**进 `~/.zcode/cli/config.json`（必须 `hooks.enabled: true`，hook 类型为 `process`：`command=/bin/bash` + 脚本绝对路径），再重开 ZCode 会话。

### 快速自测

```bash
agent-notify finished --agent zcode --task hello
# started 合法但不发布
agent-notify started --task ignored
```

### reporter CLI

```text
agent-notify <started|finished|failed|waiting> [选项]
  --agent <name>    默认 shell
  --host <name>     默认 hostname -s（Windows 会回退 hostname/COMPUTERNAME）
  --task <text>     摘要（≤80）
  --detail <text>   补充（≤500）
  --session <id>
  --dur <ms>
```

任何自身错误都 `exit 0`，不拖垮 agent。

### 已接线的 agent

| agent | 触发 | 映射 |
|---|---|---|
| **pi** | `~/.pi/agent/extensions/agentping.js` | `before_agent_start`→started（可被吞）；`agent_settled`→finished（带本轮 task）；错误→failed（task+detail） |
| **opencode** | `~/.config/opencode/plugins/agentping.js`（全局插件目录） | `chat.message`→缓存本轮 task；`session.status:busy`→started（可被吞）；`session.idle`→finished（带 task）；`session.error`→failed；`permission.ask`→waiting |
| **zcode** | `~/.zcode/cli/config.json` hooks + `agentping-zcode-hook` | `UserPromptSubmit`→started（可被吞）；`Stop`→finished；`PermissionRequest`→waiting |

pi / zcode 的 `finished` / `failed` **必须带本轮 prompt 摘要**：否则在 `started` 不发布时，通知正文只剩 `session_…`。

## 协议一览

ntfy `Title` 人类可读，例如 `[deb] pi 已完成`；`message` 是单行 JSON：

```json
{
  "v": 1,
  "agent": "pi",
  "host": "deb",
  "state": "finished",
  "task": "修复登录bug",
  "session": "sess_fe97e70b"
}
```

reporter 会双写 `agentping-<host>` 与 `agentping-all`（ntfy 发布不支持逗号多 topic）。  
App 只订 `agentping-all`。完整字段与校验见 [DESIGN.md](DESIGN.md)。

## 开发

- App：Kotlin + Compose，JDK 17，AGP 8.7.x
- 推 `main` → CI 更新 `dev` 预发布 APK
- 打 `v*` tag → CI 出正式 Release APK

更细的架构、鉴权、功耗与里程碑写在 [DESIGN.md](DESIGN.md)。

## License

未指定许可证前，默认保留所有权利。
