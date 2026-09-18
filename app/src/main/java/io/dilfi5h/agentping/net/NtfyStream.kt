package io.dilfi5h.agentping.net

import io.dilfi5h.agentping.data.NtfyFrame
import io.dilfi5h.agentping.data.PingSettings
import io.dilfi5h.agentping.data.parseFrame
import io.dilfi5h.agentping.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

sealed interface StreamEnd {
    /** opened=true means the connection was established at some point (used to reset reconnect backoff). */
    data class Closed(val opened: Boolean) : StreamEnd
}

class NtfyStream(
    private val scope: CoroutineScope,
    private val onFrame: suspend (NtfyFrame) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived WS connection, kept alive by ntfy's 30s keepalive
        .pingInterval(20, TimeUnit.SECONDS) // a VPN killing the socket often sends no RST/FIN; pings are the only probe, 20s catches dead connections fast
        .build()

    /** Whether the current connection is a history replay (first connect after reinstall / 12h pull-to-refresh): when true, onFrame only fills the timeline without notifying. */
    @Volatile
    var backfillMode: Boolean = false
        private set

    /** Server time (millis, from the handshake response's Date header, 0=unknown) when the connection opened.
     *  Messages published before it are recovered from the outage; later ones are real-time — aligned by the server clock, immune to local clock drift. */
    @Volatile
    var openServerTimeMillis: Long = 0L
        private set

    /** Opens the subscription and suspends until the connection fails/closes. message frames call onFrame asynchronously within scope. */
    suspend fun connectOnce(settings: PingSettings, since: String?, backfill: Boolean): StreamEnd {
        backfillMode = backfill
        openServerTimeMillis = 0L
        val done = CompletableDeferred<StreamEnd>()
        var didOpen = false

        var url = settings.wsUrl
        if (!since.isNullOrBlank()) url += "?since=$since"
        AppLog.log("WS", "connect $url")

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.token}")
            .build()

        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                didOpen = true
                openServerTimeMillis = response.headers["Date"]?.let {
                    runCatching {
                        java.time.ZonedDateTime.parse(it, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                } ?: 0L
                AppLog.log("WS", "open http=${response.code} serverTime=$openServerTimeMillis")
                onState("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = parseFrame(text)
                if (frame == null) {
                    AppLog.log("WS", "unparseable frame: ${text.take(120)}")
                    return // drop malformed frames outright, never crash
                }
                if (frame.event == "open") {
                    AppLog.log("WS", "stream open frame")
                    if (!didOpen) { didOpen = true; onState("Connected") }
                    return
                }
                if (frame.event != "message") return // ignore keepalive etc.
                AppLog.log("WS", "msg id=${frame.id} title=${frame.title?.take(60)}")
                scope.launch {
                    try {
                        onFrame(frame)
                    } catch (e: Exception) {
                        // an error handling a single message must not crash the coroutine root (otherwise the whole process dies and all notifications stop)
                        AppLog.log("WS", "onFrame error ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLog.log("WS", "failure opened=$didOpen ${t.javaClass.simpleName}: ${t.message} http=${response?.code}")
                // 401=invalid token; 403=valid token but no read permission (typical: pasted the publish token by mistake)
                onState(
                    when (response?.code) {
                        401 -> "Invalid token (401); check that you pasted it in full"
                        403 -> "Token has no read permission (403) — did you paste the publish token? The App only accepts a read token"
                        else -> if (didOpen) "Connection lost" else "Connection failed (network?)"
                    }
                )
                done.complete(StreamEnd.Closed(didOpen))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.log("WS", "closed code=$code reason=$reason")
                onState("Connection closed")
                done.complete(StreamEnd.Closed(didOpen))
            }
        })

        return try {
            done.await()
        } finally {
            ws.cancel()
        }
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
    }
}
