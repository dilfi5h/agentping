#!/usr/bin/env bash
# AgentPing 服务器侧一键安装（reporter 部分；ntfy 服务端见 DESIGN.md §5，已在 deb 装好）
# 用法: sudo ./install.sh
# 做的事: 装 agent-notify → /usr/local/bin；/etc/agentping.conf 不存在则提示手工填 token；
#         部署 pi 扩展 → ~/.pi/agent/extensions/agentping.js
# 主机名覆盖: 在 /etc/agentping.conf 里加 AGENTPING_HOST=<name>（容器等场景）
set -e
cd "$(dirname "$0")"

install -m 755 agent-notify /usr/local/bin/agent-notify
echo "installed: /usr/local/bin/agent-notify"

if [ ! -f /etc/agentping.conf ]; then
  echo "!! 手工创建 /etc/agentping.conf（chmod 600）:"
  echo "   AGENTPING_URL=https://ntfy.871116.xyz"
  echo "   AGENTPING_TOKEN=<publish token>"
fi

mkdir -p ~/.pi/agent/extensions
install -m 644 hooks/pi-extension.js ~/.pi/agent/extensions/agentping.js
echo "installed: ~/.pi/agent/extensions/agentping.js"
echo "done. 测试: agent-notify started --task hello"
