#!/usr/bin/env bash
# AgentPing macOS installer (reporter + ZCode hook files)
# Usage (from repo server/ directory):
#   ./install-macos.sh
#   ./install-macos.sh --host mac
#   ./install-macos.sh --skip-zcode
# Does NOT touch Linux paths (/usr/local/bin, /etc) and does NOT auto-edit
# ~/.zcode/cli/config.json (prints/writes a ready snippet instead).
# Reporter uses the Linux curl version (macOS curl handles UTF-8 titles fine,
# so no agentping-ntfy-body.py helper needed here).
# System bash 3.2 is enough for the hook; jq OR python3 enables stdin JSON parsing.
set -e
cd "$(dirname "$0")"

HOST_OVERRIDE=""
SKIP_ZCODE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST_OVERRIDE="$2"; shift 2 ;;
    --skip-zcode) SKIP_ZCODE=1; shift ;;
    -h|--help)
      echo "Usage: ./install-macos.sh [--host <name>] [--skip-zcode]"
      exit 0
      ;;
    *) echo "unknown arg: $1" >&2; exit 1 ;;
  esac
done

case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*)
    echo "!! detected Windows shell; use ./install-win.sh instead" >&2
    exit 1
    ;;
  Linux*)
    echo "!! detected Linux; use ./install.sh instead" >&2
    exit 1
    ;;
  Darwin)
    ;;
  *)
    echo "!! unknown platform $(uname -s 2>/dev/null); this script targets macOS" >&2
    exit 1
    ;;
esac

BIN_DIR="${HOME}/bin"
mkdir -p "$BIN_DIR"

install -m 755 agent-notify "$BIN_DIR/agent-notify"
echo "installed: $BIN_DIR/agent-notify"

CONF="${HOME}/.agentping.conf"
if [ ! -f "$CONF" ]; then
  cat > "$CONF" <<'EOF'
AGENTPING_URL=https://ntfy.example.com
AGENTPING_TOKEN=tk_replace_with_publish_token
EOF
  chmod 600 "$CONF" 2>/dev/null || true
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

if [ "$SKIP_ZCODE" -eq 0 ]; then
  install -m 755 hooks/agentping-zcode-hook "$BIN_DIR/agentping-zcode-hook"
  install -m 644 hooks/agentping-zcode-hook-parse.py "$BIN_DIR/agentping-zcode-hook-parse.py"
  echo "installed: $BIN_DIR/agentping-zcode-hook"
  echo "installed: $BIN_DIR/agentping-zcode-hook-parse.py"

  # Materialize snippet with this user's home path; process hook = /bin/bash + script path
  HOOK_PATH="$BIN_DIR/agentping-zcode-hook"
  SNIPPET_OUT="$BIN_DIR/agentping-zcode-snippet.json"
  cat > "$SNIPPET_OUT" <<EOF
{
  "hooks": {
    "enabled": true,
    "timeoutMs": 60000,
    "maxOutputBytes": 32768,
    "events": {
      "UserPromptSubmit": [
        {
          "hooks": [
            {
              "type": "process",
              "command": "/bin/bash",
              "args": [
                "$HOOK_PATH"
              ],
              "timeoutMs": 20000,
              "statusMessage": "AgentPing notify"
            }
          ]
        }
      ],
      "Stop": [
        {
          "hooks": [
            {
              "type": "process",
              "command": "/bin/bash",
              "args": [
                "$HOOK_PATH"
              ],
              "timeoutMs": 20000,
              "statusMessage": "AgentPing notify"
            }
          ]
        }
      ],
      "PermissionRequest": [
        {
          "hooks": [
            {
              "type": "process",
              "command": "/bin/bash",
              "args": [
                "$HOOK_PATH"
              ],
              "timeoutMs": 20000,
              "statusMessage": "AgentPing notify"
            }
          ]
        }
      ]
    }
  }
}
EOF
  echo "wrote: $SNIPPET_OUT"
  echo
  echo "----- merge this into ~/.zcode/cli/config.json (hooks.enabled=true) -----"
  cat "$SNIPPET_OUT"
  echo "--------------------------------------------------------------------------"
  echo
  echo "ZCode wiring:"
  echo "  1) Merge hooks from: $SNIPPET_OUT"
  echo "     into: ~/.zcode/cli/config.json  (set hooks.enabled=true)"
  echo "  2) Reopen ZCode session"
  echo "  3) Test: agent-notify finished --agent zcode --task hello"
  if command -v jq >/dev/null 2>&1; then
    echo "hook parser backend: jq"
  elif command -v python3 >/dev/null 2>&1; then
    echo "hook parser backend: python3"
  elif command -v python >/dev/null 2>&1; then
    echo "hook parser backend: python"
  else
    echo "!! no jq / python3 / python on PATH — zcode hook cannot parse stdin JSON (install jq, e.g. brew install jq)" >&2
  fi
else
  echo "skipped zcode hook files (--skip-zcode)"
fi

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "note: $BIN_DIR is not on PATH; add it to your shell profile (for manual agent-notify tests)" ;;
esac

echo "done."
