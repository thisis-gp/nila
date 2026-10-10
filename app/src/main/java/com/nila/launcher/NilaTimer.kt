package com.nila.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** "9:05" for under an hour, "1:02:30" above it. */
fun timerText(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * NILA's own countdown, so a timer can be paused, resumed and stopped from the chat, which the Clock app's
 * timer does not allow other apps to do. It rings with the phone's alarm sound until stopped.
 * ponytail: lives in NILA's process; if Android kills the launcher the timer is lost. Move to a foreground
 * service with a notification if that happens in practice.
 */
object NilaTimer {
    var remainingMs by mutableLongStateOf(0L)
        private set
    var running by mutableStateOf(false)
        private set
    var ringing by mutableStateOf(false)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var endsAt = 0L
    private var ringtone: Ringtone? = null
    private var appContext: Context? = null

    private val tick = object : Runnable {
        override fun run() {
            remainingMs = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            if (remainingMs == 0L) {
                running = false
                ring()
            } else {
                handler.postDelayed(this, 250)
            }
        }
    }

    val active get() = running || ringing || remainingMs > 0

    fun start(context: Context, seconds: Int) {
        appContext = context.applicationContext
        stop()
        remainingMs = seconds * 1000L
        resume()
    }

    fun pause(): Boolean {
        if (!running) return false
        remainingMs = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        running = false
        handler.removeCallbacks(tick)
        return true
    }

    fun resume(): Boolean {
        if (running || remainingMs <= 0) return false
        endsAt = SystemClock.elapsedRealtime() + remainingMs
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        return true
    }

    fun stop() {
        running = false
        remainingMs = 0
        handler.removeCallbacks(tick)
        ringtone?.stop()
        ringtone = null
        ringing = false
    }

    private fun ring() {
        val context = appContext ?: return
        ringing = true
        ringtone = RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            play()
        }
    }
}
