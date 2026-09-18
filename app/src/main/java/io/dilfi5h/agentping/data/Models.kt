package io.dilfi5h.agentping.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AgentPing v1 payload (DESIGN.md §3.2). Lenient on the App side: missing fields / type
 * mismatches fall back to defaults; on a full parse failure the caller degrades to plain text.
 */
@Serializable
data class PingPayload(
    val v: Int = 1,
    val agent: String = "shell",
    val host: String = "",
    val state: String = "",
    val task: String? = null,
    val detail: String? = null,
    val session: String? = null,
    val ts: Long? = null,
    val dur: Long? = null,
) {
    val stateKind: StateKind get() = StateKind.from(state)
}

enum class StateKind(val raw: String, val label: String) {
    STARTED("started", "Started"),
    FINISHED("finished", "Finished"),
    FAILED("failed", "Failed"),
    WAITING("waiting", "Awaiting approval"),
    UNKNOWN("unknown", "Unknown");

    companion object {
        fun from(raw: String): StateKind =
            entries.firstOrNull { it != UNKNOWN && it.raw == raw } ?: UNKNOWN
    }
}

/** The message frame of the ntfy JSON stream (only fields we need; open/keepalive frames are ignored). */
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

/** Parses the message field as PingPayload; returns null on failure so the caller degrades to plain text. */
fun parsePayload(raw: String?): PingPayload? =
    raw?.trim()?.takeIf { it.startsWith("{") }?.let {
        runCatching { frameJson.decodeFromString<PingPayload>(it) }.getOrNull()
    }
