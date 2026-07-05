package com.sixthsense.cloud

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sixthsense.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Agentic voice control: one qwen-plus function-calling round decides whether a
 * transcript is a device COMMAND ("make the pings more sensitive", "remember
 * this face as Sarah", "where did I leave my keys") or a scene QUESTION for the
 * VLM. Commands come back as a typed [Action]; anything else falls through to
 * [Action.AskScene] so the existing Q&A loop is the default path — a router
 * failure can never take the assistant offline.
 */
class VoiceCommandRouter {

    sealed class Action {
        data class SetPingSensitivity(val level: String) : Action()
        data class SetCollisionAudio(val enabled: Boolean) : Action()
        data class SetCloudVision(val enabled: Boolean) : Action()
        data class AddHazardWatch(val labels: List<String>) : Action()
        object ReadText : Action()
        data class EnrollFace(val name: String) : Action()
        data class FindObject(val name: String) : Action()
        data class AskScene(val question: String) : Action()
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val gson = Gson()

    fun route(transcript: String, onAction: (Action) -> Unit) {
        if (BuildConfig.QWEN_API_KEY.isBlank()) {
            onAction(Action.AskScene(transcript))
            return
        }
        executor.execute {
            val action = try {
                decide(transcript)
            } catch (e: Throwable) {
                Log.w(TAG, "router failed (${e.message}) — treating as scene question")
                Action.AskScene(transcript)
            }
            onAction(action)
        }
    }

    private fun decide(transcript: String): Action {
        val body = JsonObject().apply {
            addProperty("model", ROUTER_MODEL)
            add("messages", JsonArray().apply {
                add(msg("system", SYSTEM))
                add(msg("user", transcript))
            })
            add("tools", gson.fromJson(TOOLS_JSON, JsonArray::class.java))
        }
        val conn = URL("$BASE_URL/chat/completions").openConnection() as HttpURLConnection
        val message = try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.QWEN_API_KEY}")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            gson.fromJson(
                conn.inputStream.bufferedReader().use { it.readText() }, JsonObject::class.java
            ).getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("message")
        } finally {
            conn.disconnect()
        }

        val calls = message.getAsJsonArray("tool_calls") ?: return Action.AskScene(transcript)
        if (calls.size() == 0) return Action.AskScene(transcript)
        val fn = calls[0].asJsonObject.getAsJsonObject("function")
        val name = fn.get("name").asString
        val args = runCatching {
            gson.fromJson(fn.get("arguments").asString, JsonObject::class.java)
        }.getOrNull() ?: JsonObject()
        Log.i(TAG, "voice command: $name $args")

        return when (name) {
            "set_ping_sensitivity" -> Action.SetPingSensitivity(
                args.get("level")?.asString?.lowercase() ?: "normal")
            "set_collision_audio" -> Action.SetCollisionAudio(
                args.get("enabled")?.asBoolean ?: true)
            "set_cloud_vision" -> Action.SetCloudVision(
                args.get("enabled")?.asBoolean ?: true)
            "add_hazard_watch" -> Action.AddHazardWatch(
                args.getAsJsonArray("labels")?.map { it.asString } ?: emptyList())
            "read_text" -> Action.ReadText
            "enroll_face" -> Action.EnrollFace(args.get("name")?.asString ?: "friend")
            "find_object" -> Action.FindObject(args.get("name")?.asString ?: "")
            else -> Action.AskScene(transcript)
        }
    }

    private fun msg(role: String, content: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    companion object {
        private const val TAG = "SixthSenseScene"
        private const val BASE_URL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        private const val ROUTER_MODEL = "qwen-plus"
        private const val SYSTEM =
            "You route voice input from a blind user's navigation glasses. If the user is " +
                "COMMANDING the device, call the matching tool. If they are asking about " +
                "their surroundings or anything else, do NOT call a tool — reply with the " +
                "word QUESTION. Examples: 'ping faster when things are close' -> " +
                "set_ping_sensitivity high · 'stop the beeping' -> set_collision_audio " +
                "false · 'watch out for bikes and dogs' -> add_hazard_watch · 'read that " +
                "sign' -> read_text · 'remember this face as Sarah' -> enroll_face · " +
                "'where did I leave my backpack' -> find_object."

        // OpenAI-style tool schema, parsed once at call time.
        private val TOOLS_JSON = """
        [
         {"type":"function","function":{"name":"set_ping_sensitivity","description":"How early/often collision pings fire","parameters":{"type":"object","properties":{"level":{"type":"string","enum":["low","normal","high"]}},"required":["level"]}}},
         {"type":"function","function":{"name":"set_collision_audio","description":"Turn 3D collision audio pings on or off","parameters":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}}},
         {"type":"function","function":{"name":"set_cloud_vision","description":"Turn Qwen Cloud vision on or off","parameters":{"type":"object","properties":{"enabled":{"type":"boolean"}},"required":["enabled"]}}},
         {"type":"function","function":{"name":"add_hazard_watch","description":"Add object types the user wants extra warnings about","parameters":{"type":"object","properties":{"labels":{"type":"array","items":{"type":"string"}}},"required":["labels"]}}},
         {"type":"function","function":{"name":"read_text","description":"Read all visible text, signs and labels aloud","parameters":{"type":"object","properties":{}}}},
         {"type":"function","function":{"name":"enroll_face","description":"Remember the person currently in view by name (with their consent)","parameters":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}},
         {"type":"function","function":{"name":"find_object","description":"Recall where an object was last seen","parameters":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}
        ]""".trimIndent()
    }
}
