package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

data class VoiceLanguage(val tag: String, val name: String)

/** The finale languages. Lakshmi speaks through whichever offline voice is installed for each. */
val voiceLanguages = listOf(
    VoiceLanguage("en-IN", "English (India)"),
    VoiceLanguage("hi-IN", "हिंदी · Hindi"),
    VoiceLanguage("ml-IN", "മലയാളം · Malayalam"),
)

enum class SpeechInputStatus(val label: String) {
    ON_DEVICE("Offline recognition installed"),
    DOWNLOADING("Offline pack downloading"),
    DOWNLOADABLE("Offline pack available to download"),
    ONLINE_ONLY("Online recognition only"),
    UNAVAILABLE("No speech recognition"),
    UNKNOWN("Android cannot report support on this version"),
}

enum class SpeechOutputStatus(val label: String) {
    OFFLINE("Offline voice installed"),
    NEEDS_DOWNLOAD("Voice available to download"),
    ONLINE_ONLY("Voice needs a network connection"),
    UNAVAILABLE("No voice for this language"),
    UNKNOWN("Speech output engine not ready"),
}

/** What one installed text-to-speech voice offers. */
data class TtsVoice(val locale: Locale, val needsNetwork: Boolean, val notInstalled: Boolean)

data class LanguageSupport(val language: VoiceLanguage, val input: SpeechInputStatus, val output: SpeechOutputStatus) {
    /** Typed input always works; voice is offered only when both directions run without a network. */
    val voiceReady get() = input == SpeechInputStatus.ON_DEVICE && output == SpeechOutputStatus.OFFLINE
}

private fun List<String>.hasTag(tag: String) = any { it.replace('_', '-').equals(tag, ignoreCase = true) }

/** Maps Android's RecognitionSupport lists for one language tag to a status. */
fun speechInputStatus(tag: String, installed: List<String>, pending: List<String>, supported: List<String>, online: List<String>) = when {
    installed.hasTag(tag) -> SpeechInputStatus.ON_DEVICE
    pending.hasTag(tag) -> SpeechInputStatus.DOWNLOADING
    supported.hasTag(tag) -> SpeechInputStatus.DOWNLOADABLE
    online.hasTag(tag) -> SpeechInputStatus.ONLINE_ONLY
    else -> SpeechInputStatus.UNAVAILABLE
}

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

private fun recognizerIntent(tag: String? = null) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    tag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
}

/** Reports speech-input status per language. Must be called on the main thread. */
fun probeSpeechInput(context: Context, onResult: (Map<String, SpeechInputStatus>) -> Unit) {
    val all = { status: SpeechInputStatus -> onResult(voiceLanguages.associate { it.tag to status }) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
        return all(if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechInputStatus.UNKNOWN else SpeechInputStatus.UNAVAILABLE)
    }
    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    recognizer.checkRecognitionSupport(recognizerIntent(), context.mainExecutor, object : RecognitionSupportCallback {
        override fun onSupportResult(support: RecognitionSupport) {
            recognizer.destroy()
            onResult(voiceLanguages.associate {
                it.tag to speechInputStatus(
                    it.tag,
                    support.installedOnDeviceLanguages,
                    support.pendingOnDeviceLanguages,
                    support.supportedOnDeviceLanguages,
                    support.onlineLanguages,
                )
            })
        }

        override fun onError(error: Int) {
            recognizer.destroy()
            all(SpeechInputStatus.UNKNOWN)
        }
    })
}

/** Asks Android to download the offline recognition pack for [tag]. Returns false when unsupported. */
fun downloadSpeechInput(context: Context, tag: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return false
    val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    return runCatching { recognizer.triggerModelDownload(recognizerIntent(tag)) }.isSuccess.also { recognizer.destroy() }
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
