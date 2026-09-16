#!/usr/bin/env bash
# AgentPing Linux 服务器侧一键安装（reporter 部分；ntfy 服务端见 DESIGN.md §5）
# 用法: sudo ./install.sh
# 做的事: 装 agent-notify → /usr/local/bin；/etc/agentping.conf 不存在则提示手工填 token；
#         部署 pi / opencode / zcode 钩子到 *调用 sudo 的用户* 家目录（不是 root 的 ~）。
# 主机名覆盖: 在 /etc/agentping.conf 里加 AGENTPING_HOST=<name>（容器等场景）
#
# Windows/Git Bash 请用同目录 ./install-win.sh（不要用本脚本）。
set -e
cd "$(dirname "$0")"

case "$(uname -s 2>/dev/null || echo unknown)" in
  MINGW*|MSYS*|CYGWIN*)
    echo "!! detected Windows shell; use ./install-win.sh instead" >&2
    exit 1
    ;;
esac

# sudo 时插件必须落到 SUDO_USER 的 home，而不是 /root
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

# 系统二进制归 root；用户侧文件用 install -o 落到 SUDO_USER，避免 ~/.pi 变成 root 所有
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
    # mkdir -p 可能沿途建了 .pi / .pi/agent 等，不能只改叶子目录属主
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
  echo "!! 手工创建 /etc/agentping.conf（chmod 600）:"
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

echo "done. 测试: agent-notify finished --task hello  （started 合法但不发布）"
