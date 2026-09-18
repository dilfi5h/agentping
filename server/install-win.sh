#!/usr/bin/env bash
# AgentPing Windows/Git Bash installer (reporter)
# Usage (from repo server/ directory):
#   ./install-win.sh
#   ./install-win.sh --host win
# Does NOT touch Linux paths (/usr/local/bin, /etc).
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

BIN_DIR="${HOME}/bin"
mkdir -p "$BIN_DIR"

install -m 755 agent-notify.win "$BIN_DIR/agent-notify"
install -m 644 agentping-ntfy-body.py "$BIN_DIR/agentping-ntfy-body.py"
if [ -f agent-notify.cmd.example ]; then
  # optional helper; keep example name unless user already has one
  if [ ! -f "$BIN_DIR/agent-notify.cmd" ]; then
    install -m 644 agent-notify.cmd.example "$BIN_DIR/agent-notify.cmd"
  fi
fi
echo "installed: $BIN_DIR/agent-notify"
echo "installed: $BIN_DIR/agentping-ntfy-body.py"

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

echo "done."
