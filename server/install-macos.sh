#!/usr/bin/env bash
# AgentPing macOS installer (reporter + pi / opencode hooks)
# Usage (from repo server/ directory):
#   ./install-macos.sh
#   ./install-macos.sh --host mac
# Does NOT touch Linux paths (/usr/local/bin, /etc).
# Reporter uses the Linux curl version (macOS curl handles UTF-8 titles fine,
# so no agentping-ntfy-body.py helper needed here).
# Drops the pi extension into ~/.pi/agent/extensions/ and the opencode
# plugin into ~/.config/opencode/plugins/.
set -e
cd "$(dirname "$0")"

HOST_OVERRIDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST_OVERRIDE="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: ./install-macos.sh [--host <name>]"
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

mkdir -p "$HOME/.pi/agent/extensions"
install -m 644 hooks/pi-extension.js "$HOME/.pi/agent/extensions/agentping.js"
echo "installed: $HOME/.pi/agent/extensions/agentping.js"

mkdir -p "$HOME/.config/opencode/plugins"
install -m 644 hooks/opencode-plugin.js "$HOME/.config/opencode/plugins/agentping.js"
echo "installed: $HOME/.config/opencode/plugins/agentping.js"

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

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "note: $BIN_DIR is not on PATH; add it to your shell profile (for manual agent-notify tests)" ;;
esac

echo "done."
