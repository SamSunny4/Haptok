package com.example.haptok.network

import android.util.Log
import com.example.haptok.models.JobStatus
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * OkHttp WebSocket client that connects to the Haptok backend's
 * real-time job-status channel.
 *
 * Status updates are parsed from JSON and emitted on [statusUpdates].
 * Automatic reconnection is attempted on unexpected disconnects.
 *
 * Usage:
 * ```
 * val ws = WebSocketManager(client, moshi, baseUrl)
 * ws.connect(jobId)
 * ws.statusUpdates.collect { status -> … }
 * ws.disconnect()
 * ```
 *
 * @param client  Shared [OkHttpClient].
 * @param moshi   Shared [Moshi] instance for JSON parsing.
 * @param baseUrl Backend base URL (e.g. `http://10.0.2.2:8000`).
 */
class WebSocketManager(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val baseUrl: String,
) {

    companion object {
        private const val TAG = "WebSocketManager"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val NORMAL_CLOSE_CODE = 1000
    }

    // ── State ───────────────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private var currentJobId: String? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = false

    private val _statusUpdates = MutableSharedFlow<JobStatus>(extraBufferCapacity = 16)

    /** Hot flow of [JobStatus] updates received over the WebSocket. */
    val statusUpdates: SharedFlow<JobStatus> = _statusUpdates.asSharedFlow()

    private val jobStatusAdapter by lazy {
        moshi.adapter(JobStatus::class.java)
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Open a WebSocket connection for the given [jobId].
     * Any existing connection is closed first.
     */
    fun connect(jobId: String) {
        disconnect()
        currentJobId = jobId
        shouldReconnect = true
        reconnectAttempts = 0
        openSocket(jobId)
    }

    /** Close the connection. No automatic reconnection will occur. */
    fun disconnect() {
        shouldReconnect = false
        currentJobId = null
        webSocket?.close(NORMAL_CLOSE_CODE, "Client disconnected")
        webSocket = null
    }

    /** Release all resources including the coroutine scope. */
    fun release() {
        disconnect()
        scope.cancel()
    }

    // ── WebSocket lifecycle ─────────────────────────────────────────────

    private fun openSocket(jobId: String) {
        val wsScheme = if (baseUrl.startsWith("https")) "wss" else "ws"
        val host = baseUrl
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        val url = "$wsScheme://$host/ws/jobs/$jobId"

        Log.d(TAG, "Connecting to $url")

        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $url")
                reconnectAttempts = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val status = jobStatusAdapter.fromJson(text)
                    if (status != null) {
                        _statusUpdates.tryEmit(status)
                        Log.d(TAG, "Status: ${status.status} / ${status.message}")

                        // Stop reconnecting once the job reaches a terminal state.
                        if (status.status == "complete" || status.status == "completed" || status.status == "failed") {
                            shouldReconnect = false
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse WS message: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                attemptReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed ($code): $reason")
                if (code != NORMAL_CLOSE_CODE) attemptReconnect()
            }
        })
    }

    private fun attemptReconnect() {
        val jobId = currentJobId ?: return
        if (!shouldReconnect) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            return
        }
        reconnectAttempts++
        Log.d(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms (attempt $reconnectAttempts)")
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (shouldReconnect && isActive) {
                openSocket(jobId)
            }
        }
    }
}
