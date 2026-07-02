package com.sixthsense.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DEBUG-ONLY receiver. Bridges adb / the SixthSense MCP server to the running
 * app for development and demo control. Ships only in the `debug` build variant
 * (registered in src/debug/AndroidManifest.xml). NEVER part of the assistive
 * runtime.
 *
 * Actions:
 *   com.sixthsense.DEBUG_MOCK     extra  enabled (bool)
 *   com.sixthsense.DEBUG_ASK      extra  q (string)
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Ensure components exist even if the broadcast arrives before the UI.
        AppGraph.init(context)

        when (intent.action) {
            ACTION_MOCK -> {
                val enabled = intent.getBooleanExtra("enabled", true)
                Log.i(TAG, "DEBUG_MOCK enabled=$enabled")
                AppGraph.mockSceneProducer.setEnabled(enabled)
            }

            ACTION_ASK -> {
                val q = intent.getStringExtra("q") ?: "what's ahead of me?"
                Log.i(TAG, "DEBUG_ASK q=\"$q\"")
                // Route through askAsync so the on-device Qwen LLM answers (off-thread);
                // falls back to the rule-based answer if the LLM isn't ready.
                AppGraph.voiceAgent.askAsync(q) { answer ->
                    Log.i(TAG, "DEBUG_ASK answer=\"$answer\"")
                }
            }

            else -> Log.w(TAG, "Unknown action: ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "SixthSenseMCP"
        private const val ACTION_MOCK = "com.sixthsense.DEBUG_MOCK"
        private const val ACTION_ASK = "com.sixthsense.DEBUG_ASK"
    }
}
