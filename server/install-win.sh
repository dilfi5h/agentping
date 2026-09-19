#!/usr/bin/env bash
# AgentPing Windows/Git Bash installer (reporter + pi / opencode hooks)
# Usage (from repo server/ directory):
#   ./install-win.sh
#   ./install-win.sh --host win
# Does NOT touch Linux paths (/usr/local/bin, /etc).
# Hooks call Git Bash to run the bash reporter (Node spawn cannot exec bash scripts on Windows).
set -e
cd "$(dirname "$0")"

HOST_OVERRIDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST_OVERRIDE="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: ./install-win.sh [--host <name>]"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

if command -v python >/dev/null 2>&1; then
  :
else
  echo "!! python not found on PATH; Windows reporter needs python" >&2
  exit 1
fi

if [ ! -x "/c/Program Files/Git/bin/bash.exe" ] && [ ! -x "/c/Program Files (x86)/Git/bin/bash.exe" ]; then
  echo "!! Git Bash not found under Program Files; hooks need it to spawn agent-notify" >&2
  echo "   Install Git for Windows, or set AGENTPING_BASH to bash.exe" >&2
fi

BIN_DIR="${HOME}/bin"
mkdir -p "$BIN_DIR"

install -m 755 agent-notify.win "$BIN_DIR/agent-notify"
install -m 644 agentping-ntfy-body.py "$BIN_DIR/agentping-ntfy-body.py"
if [ -f agent-notify.cmd.example ]; then
  install -m 644 agent-notify.cmd.example "$BIN_DIR/agent-notify.cmd"
fi
echo "installed: $BIN_DIR/agent-notify"
echo "installed: $BIN_DIR/agentping-ntfy-body.py"
echo "installed: $BIN_DIR/agent-notify.cmd"

mkdir -p "$HOME/.pi/agent/extensions"
install -m 644 hooks/pi-extension.js "$HOME/.pi/agent/extensions/agentping.js"
echo "installed: $HOME/.pi/agent/extensions/agentping.js"

mkdir -p "$HOME/.config/opencode/plugins"
# Pick the plugin matching the installed OpenCode major version:
#   1.x           -> hooks/opencode-plugin.js     (V1 hooks API: chat.message / event / permission.ask)
#   2.x / unknown -> hooks/opencode-v2-plugin.js  (V2: export default { id, setup } + session.execution.* events)
OC_MAJOR=0
OC_BIN=""
if command -v opencode >/dev/null 2>&1; then
  OC_BIN="opencode"
elif [ -x "$HOME/.opencode/bin/opencode" ]; then
  OC_BIN="$HOME/.opencode/bin/opencode"
fi
if [ -n "$OC_BIN" ]; then
  OC_MAJOR="$("$OC_BIN" --version 2>/dev/null | head -n1 | sed -E 's/[^0-9]*([0-9]+).*/\1/')" || true
fi
[ -n "$OC_MAJOR" ] || OC_MAJOR=0
case "$OC_MAJOR" in
  1) OC_SRC="hooks/opencode-plugin.js" ;;
  *) OC_SRC="hooks/opencode-v2-plugin.js" ;;
esac

install -m 644 "$OC_SRC" $HOME/.config/opencode/plugins/agentping.js
echo "installed: $HOME/.config/opencode/plugins/agentping.js (OpenCode major=${OC_MAJOR:-0})"

# OpenCode loads plugins as ESM; ensure package.json declares module type when we create/patch it.
OC_PKG="$HOME/.config/opencode/package.json"
if [ ! -f "$OC_PKG" ]; then
  printf '{\n  "type": "module",\n  "dependencies": {\n    "@opencode-ai/plugin": "1.18.31"\n  }\n}\n' > "$OC_PKG"
  echo "installed: $OC_PKG"
elif command -v python >/dev/null 2>&1; then
  python - "$OC_PKG" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    data = json.load(f)
if data.get("type") != "module":
    data["type"] = "module"
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=2)
        f.write("\n")
    print(f"updated: {path} (type=module)")
else:
    print(f"keep existing: {path}")
PY
fi

CONF="${HOME}/.agentping.conf"
if [ ! -f "$CONF" ]; then
  if [ -f etc-agentping.conf.example ]; then
    install -m 600 etc-agentping.conf.example "$CONF"
  else
    cat > "$CONF" <<'EOF'
AGENTPING_URL=https://ntfy.example.com
AGENTPING_TOKEN=tk_replace_with_publish_token
EOF
    chmod 600 "$CONF" 2>/dev/null || true
  fi
  if [ -n "$HOST_OVERRIDE" ]; then
    printf '\nAGENTPING_HOST=%s\n' "$HOST_OVERRIDE" >> "$CONF"
  fi
  echo "!! created $CONF — fill AGENTPING_URL / AGENTPING_TOKEN (chmod 600)"
else
  echo "keep existing: $CONF"
  if [ -n "$HOST_OVERRIDE" ]; then
    if grep -q '^AGENTPING_HOST=' "$CONF" 2>/dev/null; then
      echo "!! $CONF already has AGENTPING_HOST; not overriding"
    else
      printf '\nAGENTPING_HOST=%s\n' "$HOST_OVERRIDE" >> "$CONF"
      echo "appended AGENTPING_HOST=$HOST_OVERRIDE"
    fi
  fi
fi

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "note: $BIN_DIR is not on PATH; add it to your shell profile (for manual agent-notify tests)" ;;
esac

echo
echo "OpenCode plugin load (if Desktop doesn't pick up ~/.config/opencode/plugins automatically):"
echo "  add to ~/.config/opencode/opencode.jsonc :"
echo '    "plugin": ["./plugins/agentping.js"]'
echo "  then reopen OpenCode."
echo
echo "done."
