package com.sixthsense.glasses

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Hands-free trigger from the Ray-Ban Meta touchpad: a wearer tap arrives at
 * the phone as a Bluetooth AVRCP media-button event (the same channel that
 * controls music playback). Holding an active [MediaSession] in the PLAYING
 * state routes those events here, so a tap on the glasses can start a "talk
 * to Qwen" listening window without touching the phone.
 *
 * Best-effort by design: Android routes media keys to the most recently
 * active media app, and the Meta AI assistant claims long-presses — single
 * taps are the reliable gesture.
 */
class GlassesTapTrigger(context: Context) {

    /** Debounced wearer-tap sink. */
    var onTap: (() -> Unit)? = null

    private var lastTapMs = 0L

    private val session = MediaSession(context, "SonarSight").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = fire()
            override fun onPause() = fire()
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val ev = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (ev?.action == KeyEvent.ACTION_DOWN) fire()
                return true
            }
        })
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE
                )
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .build()
        )
        isActive = true
    }

    private fun fire() {
        val now = System.currentTimeMillis()
        if (now - lastTapMs < DEBOUNCE_MS) return
        lastTapMs = now
        onTap?.invoke()
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    companion object {
        private const val DEBOUNCE_MS = 1200L
    }
}
