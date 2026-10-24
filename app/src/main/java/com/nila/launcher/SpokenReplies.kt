package com.nila.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Picks a TTS locale only for scripts with a clear language match; unknown scripts use English. */
fun replyLanguage(text: String): String {
    val locales = text.codePoints().toArray().asSequence().mapNotNull { codePoint ->
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.MALAYALAM -> "ml-IN"
            Character.UnicodeScript.DEVANAGARI -> "hi-IN"
            Character.UnicodeScript.TAMIL -> "ta-IN"
            Character.UnicodeScript.TELUGU -> "te-IN"
            Character.UnicodeScript.KANNADA -> "kn-IN"
            Character.UnicodeScript.THAI -> "th-TH"
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "ja-JP"
            Character.UnicodeScript.HANGUL -> "ko-KR"
            Character.UnicodeScript.GREEK -> "el-GR"
            Character.UnicodeScript.HEBREW -> "he-IL"
            else -> null
        }
    }.toList()
    return locales.firstOrNull() ?: "en-IN"
}

/** What Lakshmi says for a reply. Cards get a short line that points at the button; nothing is spoken as done. */
fun spokenText(response: NilaResponse): String? = when (response) {
    is NilaResponse.Text -> response.value.takeIf { it.isNotBlank() }
    is NilaResponse.Remember -> "I'll remember that."
    is NilaResponse.Alarm -> "Alarm for %02d:%02d is ready. Tap Set alarm to save it.".format(response.hour, response.minute)
    is NilaResponse.OpenApp -> "Tap Open to go to ${response.name}."
    is NilaResponse.Choices -> response.intro
    is NilaResponse.NeedsConfirmation -> "Please check the details and confirm. Nothing happens until you do."
    is NilaResponse.McpToolInvocation -> "The connected service returned a result."
    is Timer -> "Timer started."
    is CalendarEvent -> "${response.title} is ready. Tap Add to calendar to save it."
    is MonthlyReminder -> "${response.title}, every month. Tap Remind me every month to turn it on."
    is Call -> "Tap Call to open the dialer for ${response.who}."
    is Message -> "Tap Write to open Messages to ${response.who}."
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
