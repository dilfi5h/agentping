package io.dilfi5h.agentping.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AgentPing v1 载荷（DESIGN.md §3.2）。App 侧宽松：字段缺失/类型不符按默认值，
 * 整体解析失败时上层降级为纯文本渲染。
 */
@Serializable
data class PingPayload(
    val v: Int = 1,
    val agent: String = "shell",
    val host: String = "",
    val state: String = "started",
    val task: String? = null,
    val detail: String? = null,
    val session: String? = null,
    val ts: Long? = null,
    val dur: Long? = null,
) {
    val stateKind: StateKind get() = StateKind.from(state)
}

enum class StateKind(val raw: String, val label: String) {
    STARTED("started", "开始运行"),
    FINISHED("finished", "已完成"),
    FAILED("failed", "失败"),
    WAITING("waiting", "等待批准");

    companion object {
        fun from(raw: String): StateKind =
            entries.firstOrNull { it.raw == raw } ?: STARTED
    }
}

/** ntfy JSON stream 的 message 帧（只取需要的字段，open/keepalive 帧直接忽略）。 */
@Serializable
data class NtfyFrame(
    val id: String = "",
    val time: Long = 0,
    val event: String = "",
    val topic: String = "",
    val title: String? = null,
    val message: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
)

private val frameJson = Json { ignoreUnknownKeys = true }

fun parseFrame(line: String): NtfyFrame? = runCatching {
    frameJson.decodeFromString<NtfyFrame>(line)
}.getOrNull()

/** message 字段按 PingPayload 解析；失败返回 null，调用方降级纯文本。 */
fun parsePayload(raw: String?): PingPayload? =
    raw?.takeIf { it.startsWith("{") }?.let {
        runCatching { frameJson.decodeFromString<PingPayload>(it) }.getOrNull()
    }
