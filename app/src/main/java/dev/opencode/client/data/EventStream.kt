package dev.opencode.client.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException

/** 订阅 GET /api/event 的 SSE 事件流。 */
class EventStream(private val server: RemoteServer) {

    // 派生自共享 client：复用连接池/线程池，仅把读超时放开（SSE 长连接）
    private val client = HttpClients.streaming

    private val json = Json { ignoreUnknownKeys = true }

    fun events(): Flow<OpenCodeEvent> = callbackFlow {
        val builder = Request.Builder()
            .url("${server.baseUrl}/api/event")
            .header("Accept", "text/event-stream")
        if (server.password.isNotEmpty()) {
            builder.header("Authorization", Credentials.basic("opencode", server.password))
        }

        val source = EventSources.createFactory(client)
            .newEventSource(builder.build(), object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String,
                ) {
                    // 实际帧格式：data: {"id":..,"created":..,"type":"...","data":{...}}
                    try {
                        val obj = json.parseToJsonElement(data) as? JsonObject ?: return
                        val eventType = obj.str("type") ?: return
                        val payload = obj["data"] as? JsonObject ?: JsonObject(emptyMap())
                        trySend(OpenCodeEvent(eventType, payload, obj.num("created"))).apply {
                            // 慢消费溢出时丢弃单帧即可
                        }
                    } catch (_: Exception) {
                        // 单帧解析失败不影响整个流
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    close(t ?: IOException("event stream failure"))
                }
            })

        awaitClose { source.cancel() }
    }.buffer(Channel.UNLIMITED)
}
