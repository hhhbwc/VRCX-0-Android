package com.vrcx0.android.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class StreamState { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

/**
 * Server-pushed events over `/v1/stream`.
 *
 * The token travels as a query parameter, not a header: WebSocket clients
 * cannot set headers, which is why the server accepts it there.
 *
 * Two behaviours matter for correctness:
 *  - frames are FLAT (`{kind, event, payload, seq}`), and mis-reading that
 *    shape drops every event silently instead of failing;
 *  - the server does not replay events, so after `lagged` or a reconnect the
 *    caller must re-run a full query to reconcile. This class reports both,
 *    but reconciliation is the caller's job.
 */
class EventStreamClient(
    private val scope: CoroutineScope,
    /** Override for tests. In production the client is built from the address and pin. */
    private val http: OkHttpClient? = null
) {

    private val _frames = MutableSharedFlow<StreamFrame>(extraBufferCapacity = 64)
    val frames: SharedFlow<StreamFrame> = _frames.asSharedFlow()

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(StreamState.IDLE)
    val state: kotlinx.coroutines.flow.StateFlow<StreamState> = _state

    private var socket: WebSocket? = null
    private var job: Job? = null
    private var client: OkHttpClient? = null
    private val stopped = AtomicBoolean(true)

    /**
     * Dials the stream, pinning the certificate when one is given.
     *
     * Pinning has to travel with the socket too, not just the request/response
     * calls: this connection carries the same events, and a socket the client
     * would only ever reach over an unpinned connection is the hole the pin was
     * meant to close.
     */
    fun start(baseUrl: String, token: String, certificatePin: String = "") {
        stop()
        client = http ?: defaultStreamHttp(baseUrl, certificatePin)
        stopped.set(false)
        job = scope.launch { loop(baseUrl, token) }
    }

    fun stop() {
        stopped.set(true)
        job?.cancel()
        job = null
        runCatching { socket?.cancel() }
        socket = null
        client = null
        _state.value = StreamState.IDLE
    }

    private suspend fun loop(baseUrl: String, token: String) {
        var attempt = 0
        while (!stopped.get()) {
            _state.value =
                if (attempt == 0) StreamState.CONNECTING else StreamState.RECONNECTING
            val opened = runCatching { connectOnce(baseUrl, token) }.getOrDefault(false)
            if (stopped.get()) return
            if (opened) {
                attempt = 0
            } else {
                attempt++
                // 1s, 2s, 4s ... capped at 30s
                val backoffMs = (1000L shl (attempt - 1).coerceAtMost(4))
                    .coerceAtMost(30_000L)
                delay(backoffMs)
            }
        }
    }

    /** Suspends until the socket closes. Returns true if it ever opened. */
    private suspend fun connectOnce(baseUrl: String, token: String): Boolean {
        val active = client ?: return false
        val url = baseUrl + Paths.STREAM + "?token=" + token
        val request = Request.Builder().url(url).build()
        val opened = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.value = StreamState.CONNECTED
                    if (cont.isActive) cont.resumeWith(Result.success(true))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching {
                        json.decodeFromString<StreamFrame>(text)
                    }.getOrNull() ?: return
                    _frames.tryEmit(frame)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _state.value = StreamState.DISCONNECTED
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _state.value = StreamState.DISCONNECTED
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            }
            socket = active.newWebSocket(request, listener)
            cont.invokeOnCancellation { runCatching { socket?.cancel() } }
        }
        // Keep the coroutine alive for the lifetime of the socket.
        if (opened) {
            kotlinx.coroutines.awaitCancellation()
        }
        return opened
    }

    private companion object {
        val json: Json = wireJson

        fun defaultStreamHttp(baseUrl: String, certificatePin: String): OkHttpClient =
            TlsPinning.apply(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS)   // long-lived, no read timeout
                    .pingInterval(20, TimeUnit.SECONDS),
                baseUrl,
                certificatePin
            ).build()
    }
}
