package com.sixthsense.ws

import android.util.Log
import com.google.gson.Gson
import com.sixthsense.core.SceneBus
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

/**
 * Local WebSocket server that broadcasts the latest [SceneState] as JSON to the
 * dashboard on port 8080. Visualization only — the dashboard never feeds data
 * back into the assistive runtime.
 *
 * Lifecycle: construct, call [launch] with a scope to begin serving + streaming,
 * call [shutdown] to stop. Failures are logged, not thrown, so a blocked port on
 * conference Wi-Fi never crashes the app (the dashboard falls back to mock data).
 */
class SceneSocket(
    private val bus: SceneBus,
    port: Int = DEFAULT_PORT,
) : WebSocketServer(InetSocketAddress(port)) {

    private val gson = Gson()
    private var streamJob: Job? = null

    fun launch(scope: CoroutineScope) {
        try {
            isReuseAddr = true
            start() // non-blocking; spins up its own thread
            streamJob = scope.launch {
                bus.state.collect { scene ->
                    val json = gson.toJson(scene)
                    broadcastSafe(json)
                }
            }
            Log.i(TAG, "SceneSocket serving on port $port")
        } catch (e: Exception) {
            Log.w(TAG, "SceneSocket failed to start: ${e.message}")
        }
    }

    fun shutdown() {
        streamJob?.cancel()
        streamJob = null
        try {
            stop(1000)
        } catch (e: Exception) {
            Log.w(TAG, "SceneSocket stop error: ${e.message}")
        }
    }

    private fun broadcastSafe(json: String) {
        try {
            broadcast(json)
        } catch (e: Exception) {
            Log.w(TAG, "broadcast error: ${e.message}")
        }
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket server started.")
    }

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.i(TAG, "Dashboard connected: ${conn?.remoteSocketAddress}")
        // Send the current scene immediately so the UI populates on connect.
        conn?.send(gson.toJson(bus.state.value))
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.i(TAG, "Dashboard disconnected: $reason")
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        // Dashboard is visualization only; inbound messages are ignored by design.
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.w(TAG, "SceneSocket error: ${ex?.message}")
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        const val DEFAULT_PORT = 8080
    }
}
