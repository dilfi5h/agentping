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
    /** opened=true 表示曾成功建立（用于重置重连退避）。 */
    data class Closed(val opened: Boolean) : StreamEnd
}

class NtfyStream(
    private val scope: CoroutineScope,
    private val onFrame: suspend (NtfyFrame) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS 长连接，靠 ntfy 30s keepalive 保活
        .pingInterval(45, TimeUnit.SECONDS)
        .build()

    /** 打开订阅并挂起直到连接失败/关闭。message 帧在 scope 里异步回调 onFrame。 */
    suspend fun connectOnce(settings: PingSettings, since: String?): StreamEnd {
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
                AppLog.log("WS", "open http=${response.code}")
                onState("已连接")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = parseFrame(text)
                if (frame == null) {
                    AppLog.log("WS", "unparseable frame: ${text.take(120)}")
                    return // 非法帧直接丢，永不崩
                }
                if (frame.event == "open") {
                    AppLog.log("WS", "stream open frame")
                    if (!didOpen) { didOpen = true; onState("已连接") }
                    return
                }
                if (frame.event != "message") return // keepalive 等忽略
                AppLog.log("WS", "msg id=${frame.id} title=${frame.title?.take(60)}")
                scope.launch { onFrame(frame) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppLog.log("WS", "failure opened=$didOpen ${t.javaClass.simpleName}: ${t.message} http=${response?.code}")
                // 401=token 无效；403=token 有效但没读权限（典型：误填发布 token）
                onState(
                    when (response?.code) {
                        401 -> "token 无效（401），请检查是否粘贴完整"
                        403 -> "token 无读权限（403）——是不是把发布 token 填进来了？App 只能填 read token"
                        else -> if (didOpen) "连接中断" else "连接失败（网络?）"
                    }
                )
                done.complete(StreamEnd.Closed(didOpen))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppLog.log("WS", "closed code=$code reason=$reason")
                onState("连接关闭")
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
