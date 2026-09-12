# AgentPing 本次开工计划：App M3 MVP 为主，服务器只做联调最小配置

## 1. 设计文档定稿（把已确认决策写回 DESIGN.md）

- Topic 发现 = 双写总 topic：reporter POST 到 `/agentping-<host>,agentping-all`；**App 只订 `agentping-all`**
- waiting 是只读快照，无解除事件
- 钩子不带 dur（仅 `agentping run` 包装提供）
- 时间线排序用 ntfy 帧 `time`；§4 补 Android 14+ FGS `dataSync` 类型声明

## 2. 服务器最小配置（SSH 到 deb，约 30 分钟，只为 App 联调铺路）

- 检查 ntfy 是否已装；没有则装官方单二进制 + systemd：监听 `127.0.0.1:2586`、`auth-default-access: deny_all`、`cache-duration: 12h`、`keepalive-interval: 30s`
- 建双 token：`tk_publish_*`（仅写 agentping-*）/ `tk_read_*`（仅读 agentping-*）
- nginx 配 `ntfy.871116.xyz` vhost 反代（用户确认证书已就绪，直接复用），**必须带 WS 升级头**；管理路径不暴露公网
- 验证：带 token curl 发 `/agentping-deb,agentping-all` 成功、无 token 403、wss 可握手

## 3. Android 工程脚手架（app/ 从零建，不复制 PiPilot 代码）

- Kotlin + Compose，JDK 17 + AGP 8.7.x + Gradle 8.9 本地直调，Compose BOM；依赖仅 okhttp / room / kotlinx-serialization / compose
- 包名占位 `io.dilfi5h.agentping`，minSdk 29；开工生成独立新 keystore（不与 PiPilot 共用）

## 4. App 核心实现（M3 MVP 范围）

- **连接**：前台服务（FGS type `dataSync`）持单条 WebSocket `wss://ntfy.871116.xyz/agentping-all/ws?since=<last_id>`，okhttp 头带 Bearer token；指数退避 1s→60s 封顶、`open` 帧重置、网络可用回调立即重连
- **解析**：NDJSON 三类帧（open/keepalive/message）流式解析；message 字段 JSON → 结构化卡片，解析失败整条降级纯文本，永不崩；id 去重
- **存储**：Room 落消息 + last_id（IO 线程），重启续传零丢失
- **通知**：3 channel（状态无声 / 失败与等待有声横幅 / 服务运行最低）；状态→通知映射按 §3.3 优先级
- **UI**：单 Activity Compose——时间线（卡片流，agent 状态色/host/task/detail/相对时间）+ 设置页（URL、token、topic 默认 agentping-all）；过滤 chips/搜索/自启留 M4
- **只读**：App 内无任何发布能力，仅存 read token

## 5. 验证与交付流程（按 §8 老规矩）

- 本地 gradle verify 构建 → scp 到 deb `/dl/` → 真机验证：收消息 <2s、杀进程重启后 since 续传不丢、断网重连自愈、错 token 被拒
- 真机 OK + 用户点头后才 commit/push/打 tag；本次不动 CI（release.yml 留 M4）