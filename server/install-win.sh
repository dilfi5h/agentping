#!/usr/bin/env bash
# AgentPing Windows/Git Bash installer (reporter + optional ZCode hook files)
# Usage (from repo server/ directory):
#   ./install-win.sh
#   ./install-win.sh --host win
#   ./install-win.sh --skip-zcode
# Does NOT touch Linux paths (/usr/local/bin, /etc) and does NOT auto-edit
# ~/.zcode/cli/config.json (prints a ready snippet instead).
set -e
cd "$(dirname "$0")"

HOST_OVERRIDE=""
SKIP_ZCODE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --host) HOST_OVERRIDE="$2"; shift 2 ;;
    --skip-zcode) SKIP_ZCODE=1; shift ;;
    -h|--help)
      echo "Usage: ./install-win.sh [--host <name>] [--skip-zcode]"
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

# Git Bash path (/c/Users/...) → Windows path (C:/Users/...) for ZCode process hooks.
# Do NOT feed /c/... into Windows pathlib: it becomes \\c\\... and FileNotFoundError.
to_win_path() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$p"
    return
  fi
  case "$p" in
    /[a-zA-Z]/*)
      local drive rest
      drive=$(printf '%s' "${p:1:1}" | tr 'a-z' 'A-Z')
      rest="${p:2}"
      printf '%s:%s\n' "$drive" "$rest"
      ;;
    *)
      printf '%s\n' "$p"
      ;;
  esac
}

if [ "$SKIP_ZCODE" -eq 0 ]; then
  install -m 755 hooks/agentping-zcode-hook "$BIN_DIR/agentping-zcode-hook"
  install -m 644 hooks/agentping-zcode-hook-parse.py "$BIN_DIR/agentping-zcode-hook-parse.py"
  echo "installed: $BIN_DIR/agentping-zcode-hook"
  echo "installed: $BIN_DIR/agentping-zcode-hook-parse.py"

  # Materialize snippet with this user's home path (forward slashes for bash args)
  HOOK_PATH="$BIN_DIR/agentping-zcode-hook"
  HOOK_WIN=$(to_win_path "$HOOK_PATH")
  BASH_WIN='C:/Program Files/Git/bin/bash.exe'
  if [ ! -f "/c/Program Files/Git/bin/bash.exe" ] && [ ! -f "C:/Program Files/Git/bin/bash.exe" ]; then
    BASH_WIN='bash'
  fi
  SNIPPET_OUT="$BIN_DIR/agentping-zcode-snippet.json"
  # Python only builds JSON on stdout; bash writes the file so Git Bash paths work.
  python - <<PY > "$SNIPPET_OUT"
import json
from pathlib import Path
src = Path('hooks/zcode-snippet.json')
data = json.loads(src.read_text(encoding='utf-8'))
hook = r'''$HOOK_WIN'''
bash = r'''$BASH_WIN'''
for event, groups in data.get('hooks', {}).get('events', {}).items():
    for group in groups:
        for h in group.get('hooks', []):
            h['command'] = bash
            h['args'] = [hook]
print(json.dumps(data, ensure_ascii=False, indent=2))
PY
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
else
  echo "skipped zcode hook files (--skip-zcode)"
fi

echo "done."
