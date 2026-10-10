package com.nila.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Picks the voice language from the reply's own script: Malayalam, Devanagari (Hindi), or English. */
fun replyLanguage(text: String): String = when {
    text.any { it in 'ഀ'..'ൿ' } -> "ml-IN"
    text.any { it in 'ऀ'..'ॿ' } -> "hi-IN"
    else -> "en-IN"
}

/** What Lakshmi says for a reply. Cards get a short line that points at the button; nothing is spoken as done. */
fun spokenText(response: NilaResponse): String? = when (response) {
    is NilaResponse.Text -> response.value.takeIf { it.isNotBlank() }
    is NilaResponse.Remember -> "I'll remember that."
    is NilaResponse.Alarm -> "Alarm for %02d:%02d is ready. Tap Set alarm to save it.".format(response.hour, response.minute)
    is NilaResponse.OpenApp -> "Tap Open to go to ${response.name}."
    is NilaResponse.Choices -> response.intro
    is NilaResponse.NeedsConfirmation -> "Please check the details and confirm. Nothing happens until you do."
    is Timer -> "Your timer is ready. Tap Start timer."
    is CalendarEvent -> "${response.title} is ready. Tap Add to calendar to save it."
    is Call -> "Tap Call to open the dialer for ${response.who}."
    else -> null
}

/**
 * Lakshmi's voice: Android text-to-speech with offline voices only. When no offline voice is installed for a
 * language, NILA stays silent instead of sending the reply to an online voice.
 */
class Lakshmi(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }

    fun speak(text: String) {
        if (!ready) return
        val wanted = Locale.forLanguageTag(replyLanguage(text))
        val voice = tts.voices.orEmpty().firstOrNull {
            it.locale.language == wanted.language && it.locale.country == wanted.country &&
                !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features
        } ?: run {
            Log.i(TAG, "no offline voice for ${wanted.toLanguageTag()}; staying silent")
            return
        }
        tts.voice = voice
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nila-reply")
    }

    /** Stops speaking, for example when the user taps the mic to talk again. */
    fun stop() {
        tts.stop()
    }

    fun close() {
        tts.shutdown()
    }

    private companion object {
        const val TAG = "Lakshmi"
    }
}
