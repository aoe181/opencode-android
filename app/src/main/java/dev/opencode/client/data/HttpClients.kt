package dev.opencode.client.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局共享的 OkHttpClient 单例。
 *
 * 之前 OpenCodeApi 与 EventStream 各自 new 一个 OkHttpClient，等于两套连接池、
 * 两套线程池和两份 TLS 会话，内存与握手开销翻倍。这里共享一个实例，
 * 流式场景用 [streaming] 派生（共享连接池/线程池，仅放宽读超时）。
 */
object HttpClients {

    /** 普通 REST 请求：短连接超时，30s 读超时。 */
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * SSE 长连接：readTimeout 设为 0（不超时），其余配置继承 shared（复用连接池）。
     * 注意必须用 newBuilder() 派生，直接新建会丢共享。
     */
    val streaming: OkHttpClient by lazy {
        shared.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
    }
}
