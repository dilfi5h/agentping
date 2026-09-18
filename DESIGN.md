# AgentPing Design Document

> AgentPing: general task-status push for coding agents. Any agent running a task on any
> server sends status cards to your phone in real time, with look-back history.
> Read-only, battery-friendly, zero-intrusion.
> Parallel to and independent of PiPilot (PiPilot = control, AgentPing = notification); no shared code.

Status: design finalized 2026-09-12. Progress: M1 server ✅ (§5), M3 App MVP ✅ (v0.0.1 released,
repo github.com/dilfi5h/agentping, verification via tag→CI→deb fetch), M2 in progress (agent-notify ✅ +
pi hook ✅ + zcode Windows/macOS ✅, claude not wired).
Additional decisions (2026-09-12 kickoff): ① topic discovery = reporter double-writes to the catch-all topic (§3.3/§3.4); ② waiting is a read-only snapshot with no resolution event; ③ hook paths don't carry `dur` (only the L2 wrapper provides it); ④ timeline sorting always uses the ntfy frame's `time`; `ts` is display-only.

## 1. Goals and Non-Goals

**Goals (V1)**

- One-way read-only push: four states of an agent task (started / finished / failed / waiting)
- Battery first: steady-state power target < 1%/day (no polling, no wake-lock abuse, one long connection for all subscriptions)
- Performance first: end-to-end message latency < 2s (< 1s on a LAN-quality server); history queries hit local Room, in milliseconds
- Coverage: agents with hooks get native integrations (pi / zcode / Claude Code / Codex / OpenCode / Gemini CLI); the rest fall back to process wrapping (100% coverage)
- Human-readable at a glance: messages are readable without the App (official ntfy App / SMS-style fallback)

**Non-Goals (explicitly not in V1)**

- ❌ Any write operation: no approving, no aborting, no replying. The App holds no publish token; the server denies writes at the permission layer
- ❌ Two-way channels, remote terminal, file transfer
- ❌ Desktop client, multi-user, web UI
- ❌ tmux screen-scraping state detection (revisit in V2; heuristics are fragile)

## 2. Overall Architecture

```
┌─ server (deb etc.) ────────────────────────┐      ┌─ phone ──────────────┐
│ agent(hook) ─▶ agent-notify ──HTTPS POST──┼─▶ ntfy ─WebSocket─▶ AgentPing │
│            (~30-line script: JSON + curl)   │    (systemd,    foreground    │
│ agentping run <cmd> (process-wrap fallback) │    read-only token) service+Room │
└────────────────────────────────────────────┘      └────────────────────────┘
```

Component responsibilities:

- **agent-notify** (server, ~30 lines of bash): the only server-side program. Assembles the
  JSON, curl POSTs to ntfy with a token, 5s timeout, exits 0 silently on any error
  (never affects the agent itself)
- **ntfy** (server, official single binary + systemd): the message bus. Ships auth, offline
  cache, topic permissions, keepalive. Zero custom code
- **AgentPing App** (Android): a foreground service holding one WebSocket subscription for all
  topics; Room local history; system notifications; card timeline UI

## 3. Protocol Specification (v1, detailed)

### 3.1 Transport Carrier and Dual-Format Design

The push carrier is ntfy's message field. **One message, two readings** (the key design):

- `title` = human-readable summary, template `[<{host}>] {agent} {state label}`, e.g. `[deb] zcode Awaiting approval`
- `message` = **single-line JSON** (§3.2 structured payload)

Effects:
- AgentPing parses the message's JSON → structured card; on parse failure (invalid JSON) → the whole message renders as plain text, never crashes
- Subscribing to the same topic with the official ntfy App still reads title + message fine (the message is a JSON string, acceptable readability) — that's the debugging and fallback channel

### 3.2 Payload Schema (AgentPing v1)

```json
{
  "v": 1,
  "agent": "zcode",
  "host": "deb",
  "state": "waiting",
  "task": "fix login bug",
  "detail": "PermissionRequest: Bash(rm -rf /tmp/x)",
  "session": "sess_fe97e70b",
  "ts": 1760000000000,
  "dur": 123000
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `v` | int | ✅ | Protocol version, always 1. On v>1 the App renders with capability degradation (unknown fields are ignored) |
| `agent` | string | ✅ | Lowercase identifier: `pi` `zcode` `claude` `codex` `gemini` `opencode` `shell` (L2 wrapper default) or any custom string. The App renders unknown agents with a generic style |
| `host` | string | ✅ | Machine name; the reporter takes `hostname -s` automatically, overridable via `--host` |
| `state` | string | ✅ | One of four, lowercase: `started` / `finished` / `failed` / `waiting`. waiting is a read-only snapshot with no resolution event (after approving locally the task keeps running; the waiting entry isn't retracted and later finished/failed naturally supersede it in the timeline). **`started` remains a legal state (hooks may keep sending it), but the reporter doesn't publish it** (high frequency, no action value; if the App ever receives one it only enters the timeline without a notification) |
| `task` | string | ❌ | One-line task summary, ≤80 chars recommended. Source: prompt snippets / file names available in the hook context; omit if unavailable |
| `detail` | string | ❌ | Extra info ≤500 chars: exit code, error message, the exact command of a permission request |
| `session` | string | ❌ | Session identifier (passed through verbatim; the App V1 only displays it). The addressing key for V2 remote operations |
| `ts` | long | ❌ | Millisecond timestamp on the reporter's clock, **display only**. Defaults to ntfy's store time. Multi-server clock drift is untrusted; timeline sorting always uses the ntfy frame's own `time` |
| `dur` | long | ❌ | Task duration in ms, meaningful only for finished/failed. **Not provided on the hook path** (hooks are stateless single-shot processes); only the `agentping run` process wrapper can compute it |

**Validation rules** (lenient on the App side, strict on the reporter side):

- reporter: illegal state/agent values refuse to send (local stderr error); JSON on a single line, no newlines
- App: any missing field / type mismatch → that field takes its default and the message still displays (title + raw text); degrade to plain text only when schema validation fails outright
- Versioning: adding fields = still v1 (backward compatible); changing semantics = v2, the App branches on `v`

### 3.3 ntfy Publish Request (reporter → ntfy)

```bash
# Double-write to the catch-all topic: ntfy publishing doesn't support comma-separated multi-topic (only subscribing does), so it's two POSTs
curl -m 5 -s -o /dev/null \
  -H "Authorization: Bearer tk_publish_xxx" \
  -H "Title: [deb] zcode Awaiting approval" \
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

- **Double-write to the catch-all topic (the topic-discovery mechanism)**: the reporter POSTs once each to `agentping-<host>` and `agentping-all`. The AgentPing App subscribes only to `agentping-all`, so adding a new server needs zero App configuration; `agentping-<host>` stays available for the official ntfy App to subscribe per machine / debug
- Carry title/tags/priority in HTTP headers (a JSON publish body works too — pick one; we standardize on header + body, where the body is the payload JSON, avoiding double escaping)
- **Priority mapping**: started=**don't push** (the reporter exits 0 directly on `started`) / finished=default(3) / failed=high(4) / waiting=high(4). urgent(5) is reserved for V2 manual testing
- **tags (ntfy emoji)**: `robot` always; per state: finished=`white_check_mark`, failed=`x`, waiting=`hourglass_flowing_sand` (started isn't published, no tag)
- 5s timeout, silent failure — a hook must never stall the agent

### 3.4 App Subscription Protocol (ntfy JSON stream)

- Connect: `WSS ntfy.871116.xyz/agentping-all/ws?since=<last_id>` (**the App subscribes only to the catch-all topic `agentping-all`**, see §3.3 double-write)
  - Auth via the okhttp request header `Authorization: Bearer tk_read_xxx` (no query parameter needed)
  - `since` = locally persisted id of the last **processed** ntfy message (a Settings prefs cursor, decoupled from the timeline's `time`) → zero loss on reconnect, no duplicates and no gaps
- Stream format: newline-delimited JSON, three frame types:
  - `{"event":"open",...}` connection ready → reset the backoff timer
  - `{"event":"keepalive",...}` server heartbeat ~30s → ignore (okhttp's own ping covers NAT)
  - `{"event":"message","id":"...","time":...,"title":...,"message":...}` → process
- Processing pipeline: dedupe by id (ntfy's idempotent key) → parse the message field as JSON → store in Room (IO thread) → StateFlow updates the UI + system notification

### 3.5 Reporter (agent-notify) CLI Specification

```
agent-notify <state> [options]
  --agent <name>    defaults to shell
  --host <name>     defaults to hostname -s
  --task <text>     summary
  --detail <text>   extra info
  --session <id>
  --dur <ms>
Config sources (priority): env vars AGENTPING_URL / AGENTPING_TOKEN > /etc/agentping.conf (KEY=VALUE)
Behavior contract: any internal error → one stderr line + exit 0; never blocks, never changes the agent's exit code
started: a legal state, hooks may keep calling; the reporter exits 0 silently without POSTing (one choke point, so each agent's hook needs no change)
```

### 3.6 Per-Agent Hook Wiring (M2 scope: pi + zcode + Claude Code)

| Agent | Trigger | Event → state mapping |
|---|---|---|
| **pi** | extension `~/.pi/agent/extensions/agentping.js` (✅ shipped and device-verified 2026-09-12; API: `before_agent_start`/`agent_end`/`agent_settled`, calls agent-notify via `pi.exec`) | `before_agent_start`→started (task=prompt snippet; the reporter may not publish it), caches this round's task; `agent_end`(stopReason=error)→failed (task+detail=error text); `agent_settled`→finished (task=this round's prompt; skipped if this run already pushed failed). **finished/failed must carry task**, otherwise once started is swallowed the notification body is just the session id |
| **zcode** | top-level `hooks` in `~/.zcode/cli/config.json` (⚠ must set `enabled:true`, disabled by default; on Windows use `server/hooks/agentping-zcode-hook`, `install-win.sh` generates a mergeable snippet; on macOS the same script is bash 3.2 compatible (mapfile replaced by line-by-line array reads); `install-macos.sh` generates the snippet: a `process` hook with `command=/bin/bash` + absolute script path) | Recommended: `UserPromptSubmit`→started (may be swallowed), `Stop`→finished (with task), `PermissionRequest`→waiting; `PostToolUseFailure`→don't push (noise). The Windows process hook invokes Git Bash; watch for CR in the stdin JSON |
| **Claude Code** | hooks in `~/.claude/settings.json` | `UserPromptSubmit`→started, `Stop`→finished, `Notification`→waiting (CC routes permission reminders through this event) |
| **opencode** | plugin `~/.config/opencode/plugins/agentping.js` (✅ shipped and device-verified 2026-09-16, v1.18.31; hooks: `chat.message`/`event`/`permission.ask`, spawns agent-notify) | `chat.message`→cache task (the text lives in `output.parts`, **not** in the `message.updated` payload); `session.status:busy`→started (may be swallowed); `session.idle`→finished (with task; skipped if failed was pushed); `session.error`→failed (task+detail); `permission.ask`→waiting (title+metadata.command) |
| **Codex** | `notify` in `~/.codex/config.toml` | agent-start/agent-end JSON args → started/finished |
| **OpenCode / Gemini CLI** | plugin / hooks (check current-version docs at kickoff) | Same-shape mapping |
| **Everything else** (L2 fallback) | `agentping run -- <cmd>` | launch→started; exit 0→finished(dur), non-zero→failed(dur, detail=stderr tail) |

## 4. App Architecture (concrete decisions for battery and performance)

| Decision | Choice | Rationale |
|---|---|---|
| Connection | Foreground service + **single WebSocket** | One connection subscribes to `agentping-all` (catch-all topic, §3.3); ntfy keepalive holds NAT open; zero HTTP polling. Android 14+ requires declaring FGS type `dataSync` and stating the purpose in the settings page |
| Message-loss protection | Resume via `since=<last_id>` | No loss, no duplicates on disconnect; last_id is the ntfy cursor stored in Settings prefs (decoupled from the timeline's `time`); if no cursor exists from before an upgrade, fall back to the newest Room id |
| Reconnect policy | Exponential backoff 1s→2s→…→60s cap; reset on the `open` frame; network-availability callback triggers immediate retry | Balance of battery vs responsiveness |
| Doze/background | Persistent foreground-service notification (silent, min-importance channel, dismissible) | FGS legally sidesteps Android's background-network limits; no wake-locks |
| Start on boot | `BOOT_COMPLETED` receiver restarts the service; can be turned off in settings | The phone resumes subscribing automatically after a server reboot |
| Notification tiers | 3 channels: task status (default, silent) / failure & waiting (sound + banner) / service running (min importance) | Interruption control for a read-only scenario |
| Data flow | WS thread → kotlinx-serialization streaming parse → Room (IO) → StateFlow → Compose | Unidirectional data flow; parse on the stream, never buffer whole |
| UI | Single Activity + Compose: timeline (card stream with agent/host filter chips) / settings / about | Zero custom Views |
| Dependencies | okhttp, room, kotlinx-serialization, compose — no third-party push libs or heavyweight libraries | PiPilot lesson: dependencies must survive the proxy |

**Steady-state power estimate**: 1 WS + 30s heartbeats ≈ a few KB/hour; no GPS / no wakeups / no polling → expected < 1%/day.

## 5. Server-Side Deployment (deb) — ✅ shipped 2026-09-12 (minimal integration config)

Actual deployment (deviations from the original plan verified):

- ntfy v2.28.0 official single binary → `/usr/local/bin/ntfy`, config `/etc/ntfy/server.yml`, systemd `ntfy.service` (dedicated `ntfy` user), listening on `127.0.0.1:2586`
- nginx is a **source-compiled build**: `/usr/local/nginx/`, vhost dir `/usr/local/nginx/conf/vhost/` (`ntfy.871116.xyz.conf` added, with WS upgrade headers; admin paths `/-/` and `/v1/` restricted to 127.0.0.1)
- Cert: deb already has a wildcard cert `*.871116.xyz` (`/root/sh/cert.pem`, expires 2026-11-28); the ntfy subdomain reuses it, no certbot needed
- Auth: `auth-default-access: deny-all`; users/ACL/tokens:
  - `agentping-pub` / `tk_publish_*`: write to `agentping-*` only (token in deb's `/etc/agentping.conf`, used directly by M2 hooks)
  - `agentping-sub` / `tk_read_*`: read `agentping-*` only (for the App). **The App can never hold a write token — the "read-only" promise is enforced at the server layer**
- Cache: `cache-duration: 12h`; `keepalive-interval: 30s`
- ⚠ ntfy publishing doesn't support comma-separated multi-topic (only subscribing does); the double-write must be two POSTs, see §3.3
- Backup requirement: stateless and rebuildable; tracking the config files in dotfiles suffices

## 6. Security

- TLS end to end; least-privilege tokens (read/write separated, §5)
- The detail field may contain command snippets — truncated to 500 chars on the reporter side; the App renders it as plain text (no Markdown/HTML injection surface)
- The ntfy panel/accounts aren't exposed publicly (only ws/publish paths are opened); the admin API is localhost-only
- The App requests no dangerous permissions beyond INTERNET (plus the notification permission)

## 7. Engineering and Repository

```
agentping/
├── DESIGN.md
├── README.md
├── server/
│   ├── agent-notify              ← Linux reporter (curl)
│   ├── agent-notify.win          ← Windows/Git Bash reporter shell
│   ├── agentping-ntfy-body.py    ← Windows UTF-8 JSON publish helper
│   ├── agent-notify.cmd.example  ← optional Win32 launcher example
│   ├── etc-agentping.conf.example
│   ├── install.sh                ← Linux: /usr/local/bin + pi extension
│   ├── install-win.sh            ← Windows: ~/bin + zcode hook files
│   ├── install-macos.sh          ← macOS: ~/bin + zcode hook files (reuses the Linux reporter)
│   └── hooks/
│       ├── pi-extension.js
│       ├── opencode-plugin.js
│       ├── agentping-zcode-hook
│       ├── agentping-zcode-hook-parse.py
│       └── zcode-snippet.json    ← placeholder snippet; claude/codex deferred
├── app/
└── .github/workflows/release.yml
```

Notes: the ntfy `server.yml` / systemd unit, `docs/protocol.md`, and claude/codex snippets can still be added later; **three install scripts** for Windows / Linux / macOS (macOS reuses the Linux reporter) rather than one auto-mixing script.
- Standalone GitHub repo (new, public, same dilfi5h account)
- Build: JDK 17 + AGP 8.7.x + Compose BOM, local Gradle 8.9 invoked directly (same environment as PiPilot); CI follows the setup-java/gradle + actions template
- Signing: **an independent new keystore** (not shared with PiPilot — two apps, two identities); generated at kickoff, uploaded to GH Secrets

## 8. Development Flow (user-approved)

1. Each feature: build a verification package locally → scp to deb's `/dl/` (small packages transfer directly / large ones go through CI-release+deb fetch, the old rules) → **verify on a real device**
2. Only after a real device passes + the user approves → commit/push/tag (semantic `v0.0.x`: start status push at 0.1.0? No — keep PiPilot's manual 0.0.x convention, versionCode +1 per verification build)
3. Intermediate verification builds use a temporary `v0.0.x-verifyN` tag + temporary Release channel, deleted once the formal one is out (flow already proven on PiPilot v0.0.11)
4. Don't watch CI when CI hasn't changed

## 9. Milestones

| Milestone | Content | Acceptance |
|---|---|---|
| M1 server up | ntfy live + subdomain + two tokens; curl publishes → official ntfy App on the phone receives | End-to-end < 2s |
| M2 hooks | agent-notify finalized + pi/zcode/claude wired | All four states received for real tasks |
| M3 App MVP | WS subscribe + notifications + timeline + settings page | Installs on device, self-heals after process kill, reconnects after network loss |
| M4 polish | Filters / history search / start on boot / notification tiers | Ship v0.0.1 stable |
