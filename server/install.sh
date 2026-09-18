#!/usr/bin/env bash
# AgentPing one-shot server-side installer for Linux (reporter part; for the ntfy server see DESIGN.md §5)
# Usage: sudo ./install.sh
# What it does: installs agent-notify → /usr/local/bin; if /etc/agentping.conf is missing, prompts to fill in the token manually;
#               deploys pi / opencode / zcode hooks into the home dir of the *user running sudo* (not root's ~).
# Hostname override: add AGENTPING_HOST=<name> to /etc/agentping.conf (containers etc.)
#
# On Windows/Git Bash use ./install-win.sh in the same directory (do not use this script).
set -e
cd "$(dirname "$0")"

case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*)
    echo "!! detected Windows shell; use ./install-win.sh instead" >&2
    exit 1
    ;;
esac

# Under sudo, plugins must land in SUDO_USER's home, not /root
INSTALL_USER=""
USER_HOME="$HOME"
if [ "$(id -u)" -eq 0 ] && [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != "root" ]; then
  INSTALL_USER="$SUDO_USER"
  USER_HOME=$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6)
  [ -n "$USER_HOME" ] || USER_HOME=$(eval echo "~$SUDO_USER")
fi

INSTALL_GROUP=""
if [ -n "$INSTALL_USER" ]; then
  INSTALL_GROUP=$(id -gn "$INSTALL_USER" 2>/dev/null || true)
fi

# System binaries belong to root; user-side files use install -o to land in SUDO_USER's hands, so ~/.pi doesn't become root-owned
install_user() {
  local mode="$1" src="$2" dest="$3" dir d
  dir=$(dirname "$dest")
  mkdir -p "$dir"
  if [ -n "$INSTALL_USER" ]; then
    if [ -n "$INSTALL_GROUP" ]; then
      install -m "$mode" -o "$INSTALL_USER" -g "$INSTALL_GROUP" "$src" "$dest"
    else
      install -m "$mode" -o "$INSTALL_USER" "$src" "$dest"
    fi
    # mkdir -p may have created .pi / .pi/agent etc. along the way; fixing only the leaf directory's owner isn't enough
    d="$dir"
    while [ -n "$d" ] && [ "$d" != "/" ] && [ "$d" != "$USER_HOME" ]; do
      chown "$INSTALL_USER${INSTALL_GROUP:+:$INSTALL_GROUP}" "$d" 2>/dev/null || true
      d=$(dirname "$d")
    done
  else
    install -m "$mode" "$src" "$dest"
  fi
}

install -m 755 agent-notify /usr/local/bin/agent-notify
echo "installed: /usr/local/bin/agent-notify"

if [ ! -f /etc/agentping.conf ]; then
  echo "!! Create /etc/agentping.conf manually (chmod 600):"
  echo "   AGENTPING_URL=https://ntfy.example.com"
  echo "   AGENTPING_TOKEN=<publish token>"
  echo "   # optional: see etc-agentping.conf.example"
fi

install_user 644 hooks/pi-extension.js "$USER_HOME/.pi/agent/extensions/agentping.js"
echo "installed: $USER_HOME/.pi/agent/extensions/agentping.js"

install_user 644 hooks/opencode-plugin.js "$USER_HOME/.config/opencode/plugins/agentping.js"
echo "installed: $USER_HOME/.config/opencode/plugins/agentping.js"

BIN_DIR="$USER_HOME/bin"
install_user 755 hooks/agentping-zcode-hook "$BIN_DIR/agentping-zcode-hook"
install_user 644 hooks/agentping-zcode-hook-parse.py "$BIN_DIR/agentping-zcode-hook-parse.py"
echo "installed: $BIN_DIR/agentping-zcode-hook"
echo "installed: $BIN_DIR/agentping-zcode-hook-parse.py"

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
if [ -n "$INSTALL_USER" ]; then
  chown "$INSTALL_USER${INSTALL_GROUP:+:$INSTALL_GROUP}" "$SNIPPET_OUT" 2>/dev/null || true
fi
echo "wrote: $SNIPPET_OUT"
echo
echo "----- merge this into ~/.zcode/cli/config.json (hooks.enabled=true) -----"
cat "$SNIPPET_OUT"
echo "--------------------------------------------------------------------------"
echo
echo "ZCode wiring:"
echo "  1) Merge hooks from: $SNIPPET_OUT"
echo "     into: $USER_HOME/.zcode/cli/config.json  (set hooks.enabled=true)"
echo "  2) Reopen ZCode session"
echo "  3) Test: agent-notify finished --agent zcode --task hello"

echo "done. Test: agent-notify finished --task hello  (started is valid but not published)"
