package com.example.tabletpanel

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.util.Log

/** Chimes for incoming alerts: the notification sound normally, an insistent tone for critical ones. */
class AlertSound(private val context: Context) {
    private var tone: ToneGenerator? = null

    fun play(severity: String) {
        runCatching {
            if (severity == "critical") {
                val t = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, 90).also { tone = it }
                t.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 4000)
            } else {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(context, uri)?.play()
            }
        }.onFailure { Log.w("AlertSound", "could not play alert sound", it) }
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
