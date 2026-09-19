# AgentPing

Task-status push for coding agents, in general.

When an agent on your server starts a task, your phone receives status cards in real time, with full history to look back on.  
**Read-only, battery-friendly, zero-intrusion** — parallel to and independent of [PiPilot](https://github.com/dilfi5h) (control), with no shared code.

```
agent(hook) → agent-notify → ntfy → AgentPing App
```

## Status

| Part | State |
|---|---|
| ntfy message bus | ✅ |
| `agent-notify` reporter (Linux / Windows / macOS) | ✅ |
| Android App (timeline / notifications / resume) | ✅ |

### OS × Agent hook matrix

Installer coverage for the shipped agents. ✅ = install script drops the hook; ❌ = not wired by that platform's installer yet.

| Agent \\ OS | Linux<br>`install.sh` | macOS<br>`install-macos.sh` | Windows<br>`install-win.sh` |
|---|:---:|:---:|:---:|
| **pi** | ✅ | ✅ | ✅ |
| **opencode** | ✅ | ✅ | ✅ |

Notes:

- Claude Code / Codex / Gemini CLI are in the protocol (`agent` field) but **not shipped** yet.
- Windows hooks call Git Bash to run the bash reporter (Node cannot spawn `.sh` scripts directly on Windows).

Current release: [v0.0.13](https://github.com/dilfi5h/agentping/releases/tag/v0.0.13)

## Download the App

- Release APK: <https://github.com/dilfi5h/agentping/releases/latest>
- Dev channel (latest `main` build): <https://github.com/dilfi5h/agentping/releases/tag/dev>

After installing, fill in the settings:

| Field | Description |
|---|---|
| URL | Your ntfy address, e.g. `https://ntfy.example.com` |
| Token | A **read-only** token (never put a write token in the App) |
| Topic | Defaults to `agentping-all` |

On first install, grant the notification permission; it's also recommended to add AgentPing to the battery-optimization allowlist in system settings so the background WebSocket isn't killed. If notifications don't arrive, check "Notification diagnostics" on the settings page — you can send a local test notification (it won't enter the timeline).

## What it does / doesn't do

**Does:**

- Four states: `started` / `finished` / `failed` / `waiting`
- One long-lived WebSocket subscription; resumes after disconnects via `since=<last_id>`
- Local Room history (7 days), searchable on the timeline with host/agent chips; failures and waiting states use high-priority notifications; tapping a task notification opens that session

**Doesn't do (V1):**

- Any write operation (approve / abort / reply)
- Remote terminal, file transfer, desktop client, multi-user web

Note: hooks may still call `started`, but **the reporter doesn't publish it by default** (high frequency, no action value); even if the App receives one, it only goes to the timeline without a notification.

## Server side

Prerequisite: a working ntfy (auth enabled, read/write tokens separated; write access restricted to `agentping-*`).

The install scripts are per-platform — **don't mix them**:

| Platform | Command | Installs to |
|---|---|---|
| Linux | `sudo ./install.sh` | `/usr/local/bin/agent-notify` + pi / opencode hooks (into `SUDO_USER`'s home, not root's `~`) |
| Windows (Git Bash) | `./install-win.sh` | `~/bin/agent-notify` + python publish helper + pi / opencode hooks |
| macOS | `./install-macos.sh` | `~/bin/agent-notify` + pi / opencode hooks |

### Linux

```bash
# in the repo's server/ directory
sudo ./install.sh
```

Installs (under `sudo`, plugins go to the caller's home, not `/root`):

- `/usr/local/bin/agent-notify` (Linux curl version)
- `~/.pi/agent/extensions/agentping.js` (if pi is used on this machine)
- `~/.config/opencode/plugins/agentping.js` (if opencode is used on this machine; V1 or V2 plugin picked by `opencode --version`)

If `/etc/agentping.conf` is missing, create it as prompted (`chmod 600`):

```bash
AGENTPING_URL=https://ntfy.example.com
AGENTPING_TOKEN=<publish-token>
# optional: override the hostname for containers etc.
# AGENTPING_HOST=deb
```

### Windows (Git Bash)

Dependencies: Git Bash, `python`, access to your ntfy.

```bash
# in the repo's server/ directory
./install-win.sh
# optional: ./install-win.sh --host win
```

Installs:

- `~/bin/agent-notify` (from `agent-notify.win`)
- `~/bin/agentping-ntfy-body.py` (publishes UTF-8 JSON, avoiding Windows curl's mojibake with non-ASCII Titles)
- `~/bin/agent-notify.cmd` (Win32 launcher via Git Bash)
- `~/.pi/agent/extensions/agentping.js` (if pi is used on this machine)
- `~/.config/opencode/plugins/agentping.js` (if opencode is used on this machine; V1 or V2 plugin picked by `opencode --version`)
- A `~/.agentping.conf` template if no config exists

If OpenCode Desktop doesn't auto-load `~/.config/opencode/plugins/`, add this to `~/.config/opencode/opencode.jsonc` and reopen OpenCode:

```jsonc
"plugin": ["./plugins/agentping.js"]
```

### macOS

```bash
# in the repo's server/ directory
./install-macos.sh
# optional: ./install-macos.sh --host mac
```

Installs:

- `~/bin/agent-notify` (Linux curl version, works as-is on macOS)
- `~/.pi/agent/extensions/agentping.js` (if pi is used on this machine)
- `~/.config/opencode/plugins/agentping.js` (if opencode is used on this machine; V1 or V2 plugin picked by `opencode --version`)
- A `~/.agentping.conf` template if no config exists (`chmod 600`)

### Quick self-test

```bash
agent-notify finished --agent pi --task hello
# started is valid but not published
agent-notify started --task ignored
```

### Reporter CLI

```text
agent-notify <started|finished|failed|waiting> [options]
  --agent <name>    defaults to shell
  --host <name>     defaults to hostname -s (falls back to hostname/COMPUTERNAME on Windows)
  --task <text>     summary (≤80)
  --detail <text>   extra info (≤500)
  --session <id>
  --dur <ms>
```

Any internal error still `exit 0`s — it never drags the agent down.

### Wired-up agents

| Agent | Trigger | Mapping |
|---|---|---|
| **pi** | `~/.pi/agent/extensions/agentping.js` | `before_agent_start`→started (may be swallowed); `agent_settled`→finished (carries this round's task); errors→failed (task+detail) |
| **opencode** | `~/.config/opencode/plugins/agentping.js` (global plugin dir; installers pick the file by `opencode --version`: 1.x → `hooks/opencode-plugin.js`, 2.x → `hooks/opencode-v2-plugin.js`) | **V1 (1.x):** `chat.message`→caches this round's task; `session.status:busy`→started (may be swallowed); `session.idle`→finished (with task); `session.error`→failed; `permission.ask`→waiting. **V2 (2.x):** `session.inbox.enqueued`→caches this round's task; `session.execution.started`→started (may be swallowed); `session.execution.succeeded`→finished (with task); `session.execution.failed`/`interrupted`→failed; `permission.evaluate` with `effect=ask`→waiting. Details: [opencode2.api.md](opencode2.api.md) |

For pi / opencode, `finished` / `failed` **must carry a summary of this round's prompt**: otherwise, when `started` isn't published, the notification body is left with nothing but `session_…`.

## Protocol at a glance

The ntfy `Title` is human-readable, e.g. `[deb] pi Finished`; `message` is a single-line JSON:

```json
{
  "v": 1,
  "agent": "pi",
  "host": "deb",
  "state": "finished",
  "task": "fix login bug",
  "session": "sess_fe97e70b"
}
```

The reporter double-publishes to `agentping-<host>` and `agentping-all` (ntfy publishing doesn't support comma-separated multi-topic).  
The App only subscribes to `agentping-all`. Full fields and validation rules: see [DESIGN.md](DESIGN.md).

## Development

- App: Kotlin + Compose, JDK 17, AGP 8.7.x
- Release builds are R8-minified (`isMinifyEnabled`) with resource shrinking (`isShrinkResources`); keep rules live in `app/proguard-rules.pro`
- Push to `main` → CI updates the `dev` prerelease APK
- Tag `v*` → CI builds the release APK

Architecture, auth, battery details and milestones: see [DESIGN.md](DESIGN.md).

## License

All rights reserved by default until a license is specified.
