package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

data class VoiceLanguage(val tag: String, val name: String)

/**
 * The finale languages. Gemma 4 E2B listens to all of them on the phone; Lakshmi speaks through whichever
 * offline text-to-speech voice is installed for each.
 */
val voiceLanguages = listOf(
    VoiceLanguage("en-IN", "English (India)"),
    VoiceLanguage("hi-IN", "हिंदी · Hindi"),
    VoiceLanguage("ml-IN", "മലയാളം · Malayalam"),
)

enum class SpeechOutputStatus(val label: String) {
    OFFLINE("Offline voice installed"),
    NEEDS_DOWNLOAD("Voice available to download"),
    ONLINE_ONLY("Voice needs a network connection"),
    UNAVAILABLE("No voice for this language"),
    UNKNOWN("Speech output engine not ready"),
}

/** What one installed text-to-speech voice offers. */
data class TtsVoice(val locale: Locale, val needsNetwork: Boolean, val notInstalled: Boolean)

/** Picks the best voice for a language tag: offline beats downloadable beats online-only. */
fun speechOutputStatus(tag: String, voices: List<TtsVoice>): SpeechOutputStatus {
    val wanted = Locale.forLanguageTag(tag)
    val matching = voices.filter { it.locale.language == wanted.language && it.locale.country == wanted.country }
    return when {
        matching.any { !it.needsNetwork && !it.notInstalled } -> SpeechOutputStatus.OFFLINE
        matching.any { it.notInstalled } -> SpeechOutputStatus.NEEDS_DOWNLOAD
        matching.isNotEmpty() -> SpeechOutputStatus.ONLINE_ONLY
        else -> SpeechOutputStatus.UNAVAILABLE
    }
}

/** Reports speech-output status per language from the default TTS engine's voices. */
fun probeSpeechOutput(context: Context, onResult: (Map<String, SpeechOutputStatus>) -> Unit) {
    var tts: TextToSpeech? = null
    tts = TextToSpeech(context.applicationContext) { status ->
        val engine = tts
        val voices = engine?.takeIf { status == TextToSpeech.SUCCESS }?.voices?.map {
            TtsVoice(it.locale, it.isNetworkConnectionRequired, TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features)
        }
        engine?.shutdown()
        onResult(voiceLanguages.associate { it.tag to (voices?.let { v -> speechOutputStatus(it.tag, v) } ?: SpeechOutputStatus.UNKNOWN) })
    }
}

/** Opens the TTS engine's voice-data installer. */
fun installSpeechOutput(context: Context) = runCatching {
    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess
