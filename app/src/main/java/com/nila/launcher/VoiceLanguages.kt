package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import java.util.Locale

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

/** Includes only languages actually advertised by the active engine, with their installed/download state. */
fun availableSpeechOutput(voices: List<TtsVoice>): Map<String, SpeechOutputStatus> =
    voices.map { it.locale.toLanguageTag() }.distinct().associateWith { tag -> speechOutputStatus(tag, voices) }

/** Reports speech-output status per language from the default TTS engine's voices. */
fun probeSpeechOutput(context: Context, onResult: (Map<String, SpeechOutputStatus>) -> Unit) {
    var tts: TextToSpeech? = null
    tts = TextToSpeech(context.applicationContext) { status ->
        val engine = tts
        val voices = engine?.takeIf { status == TextToSpeech.SUCCESS }?.voices?.map {
            TtsVoice(it.locale, it.isNetworkConnectionRequired, TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features)
        }
        engine?.shutdown()
        onResult(voices?.let(::availableSpeechOutput).orEmpty())
    }
}

/** Opens the TTS engine's voice-data installer. */
fun installSpeechOutput(context: Context) = runCatching {
    context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess
