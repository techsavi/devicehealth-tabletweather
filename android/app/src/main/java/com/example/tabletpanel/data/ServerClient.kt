package com.example.tabletpanel.data

import android.os.Build
import android.util.Log
import com.example.tabletpanel.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val TAG = "ServerClient"

sealed interface ServerStatus {
    data object Discovering : ServerStatus
    data class Connecting(val url: String) : ServerStatus
    data class Connected(val url: String) : ServerStatus
    data class Offline(val reason: String) : ServerStatus
}

/**
 * Keeps a WebSocket open to the control server, reconnecting with backoff. The server pushes the
 * full panel state on connect and on every change, so the tablet never has to poll.
 */
class ServerClient(
    private val settings: Settings,
    private val discovery: Discovery,
    private val scope: CoroutineScope,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // With no read timeout and pings only after onOpen, a lost 101 response would hang forever.
        // For WebSockets OkHttp applies the call timeout to the handshake only.
        .callTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(
        settings.cachedState?.let { runCatching { PanelJson.decodeFromString<PanelState>(it) }.getOrNull() } ?: PanelState()
    )
    val state: StateFlow<PanelState> = _state

    private val _status = MutableStateFlow<ServerStatus>(ServerStatus.Offline("Starting"))
    val status: StateFlow<ServerStatus> = _status

    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val commands: SharedFlow<String> = _commands

    @Volatile private var socket: WebSocket? = null
    private var job: Job? = null

    /** (Re)starts the connection loop, e.g. after the server address changes in settings. */
    fun start() {
        job?.cancel()
        job = scope.launch { connectLoop() }
    }

    fun send(message: JsonObject): Boolean = socket?.send(message.toString()) ?: false

    private suspend fun connectLoop() {
        var backoff = 2_000L
        var trustCachedDiscovery = true
        while (coroutineContext.isActive) {
            val url = serverUrl(trustCachedDiscovery)
            if (url == null) {
                _status.value = ServerStatus.Offline("No server found on this network")
                delay(30_000)
                continue
            }
            _status.value = ServerStatus.Connecting(url)
            val result = connectOnce(url)
            if (result.wasOpen) {
                backoff = 2_000L
                trustCachedDiscovery = true
            } else {
                backoff = (backoff * 2).coerceAtMost(30_000L)
                // A remembered address that no longer answers: look the server up again next time.
                trustCachedDiscovery = false
            }
            _status.value = ServerStatus.Offline(result.reason)
            delay(backoff)
        }
    }

    private suspend fun serverUrl(trustCachedDiscovery: Boolean): String? {
        settings.serverUrl.takeIf { it.isNotBlank() }?.let {
            return if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
        }
        if (trustCachedDiscovery) settings.lastDiscoveredUrl?.let { return it }
        _status.value = ServerStatus.Discovering
        return discovery.find()?.also { settings.lastDiscoveredUrl = it }
    }

    private data class Result(val wasOpen: Boolean, val reason: String)

    private suspend fun connectOnce(baseUrl: String): Result {
        val closed = CompletableDeferred<Result>()
        var opened = false
        val key = settings.apiKey.takeIf { it.isNotBlank() }?.let { "?key=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val wsUrl = baseUrl.replaceFirst(Regex("^http"), "ws") + "/ws" + key
        val request = Request.Builder().url(wsUrl).build()

        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                socket = webSocket
                _status.value = ServerStatus.Connected(baseUrl)
                webSocket.send(hello().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { PanelJson.decodeFromString<ServerMessage>(text) }
                    .onFailure { Log.w(TAG, "bad message", it) }
                    .getOrNull() ?: return
                when (msg.type) {
                    "state" -> msg.state?.let {
                        _state.value = it
                        settings.cachedState = PanelJson.encodeToString(PanelState.serializer(), it)
                    }
                    "command" -> msg.command?.let { _commands.tryEmit(it) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(Result(opened, "Disconnected from server"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = when (response?.code) {
                    401 -> "Server rejected the API key"
                    null -> "Can't reach server (${t.message ?: t.javaClass.simpleName})"
                    else -> "Server error ${response.code}"
                }
                closed.complete(Result(opened, why))
            }
        })

        return try {
            closed.await()
        } finally {
            socket = null
            ws.cancel()
        }
    }

    private fun hello() = buildJsonObject {
        put("type", "hello")
        put("deviceId", settings.deviceId)
        put("name", settings.deviceName)
        put("appVersion", BuildConfig.VERSION_NAME)
        put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
    }
}
