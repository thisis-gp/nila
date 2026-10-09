package com.nila.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.composables.icons.lucide.R as LucideR

/** Short label on the language chip. */
fun voiceChipLabel(tag: String) = when (tag) {
    "hi-IN" -> "हि"
    "ml-IN" -> "മ"
    else -> "EN"
}

/** Next language when the chip is tapped. */
fun nextVoiceLanguage(tag: String): String {
    val index = voiceLanguages.indexOfFirst { it.tag == tag }
    return voiceLanguages[(index + 1) % voiceLanguages.size].tag
}

/**
 * What the user sees when listening fails. Never silent: typing always remains.
 * [inputStatus] says whether this phone can download the language, so we only point to a download that exists.
 */
fun voiceErrorMessage(error: Int, languageName: String, inputStatus: SpeechInputStatus? = null): String = when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap the mic and try again."
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
        if (inputStatus == SpeechInputStatus.DOWNLOADABLE || inputStatus == SpeechInputStatus.DOWNLOADING) {
            "$languageName speech isn't installed on this phone. Download it in Settings → Voice languages, or type instead."
        } else {
            "$languageName speech isn't available on this phone yet. Type instead."
        }
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NILA needs microphone access to listen. You can still type."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
        "Listening needs a network for this language right now. Type instead, or download the offline pack."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The microphone is busy. Try again in a moment."
    else -> "Listening stopped. You can type instead."
}

/** Wraps one SpeechRecognizer. Prefers the on-device recognizer so speech stays on the phone. */
class VoiceListener(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onFailed: (Int) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    /** True when speech is processed on the phone rather than by an online service. */
    val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start(tag: String) {
        stop()
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) return onFailed(SpeechRecognizer.ERROR_CLIENT)
        val created = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle) = firstResult(partialResults)?.let(onPartial) ?: Unit
            override fun onResults(results: Bundle) {
                val text = firstResult(results)
                stop()
                if (text.isNullOrBlank()) onFailed(SpeechRecognizer.ERROR_NO_MATCH) else onFinal(text)
            }
            override fun onError(error: Int) {
                stop()
                onFailed(error)
            }
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        created.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }

    /** Ends listening but keeps what was heard: the recognizer delivers its final text to onFinal. */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Cancels listening and discards anything heard. */
    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun firstResult(bundle: Bundle) = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}

/**
 * Language chip and mic for the chat input. Tap to listen; tap again, or pause, to send.
 * Partial words go to [onPartial]; the final text is sent with [onFinal].
 * Failures go to [onMessage] so the user always knows why listening stopped.
 */
@Composable
fun VoiceMic(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("nila_voice", Context.MODE_PRIVATE) }
    var tag by remember { mutableStateOf(prefs.getString("language", null) ?: voiceLanguages.first().tag) }
    var listening by remember { mutableStateOf(false) }
    val languageName = voiceLanguages.first { it.tag == tag }.name
    // The listener outlives recompositions, so it reads the latest callbacks and language through state.
    val latestPartial by rememberUpdatedState(onPartial)
    val latestFinal by rememberUpdatedState(onFinal)
    val latestMessage by rememberUpdatedState(onMessage)
    val latestName by rememberUpdatedState(languageName)
    val latestTag by rememberUpdatedState(tag)
    val listener = remember {
        VoiceListener(
            context,
            onPartial = { latestPartial(it) },
            onFinal = { listening = false; latestFinal(it) },
            onFailed = { error ->
                listening = false
                val languageError = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                // Check what this phone offers before suggesting a download.
                if (languageError) probeSpeechInput(context) { latestMessage(voiceErrorMessage(error, latestName, it[latestTag])) }
                else latestMessage(voiceErrorMessage(error, latestName))
            },
        )
    }
    DisposableEffect(listener) { onDispose { listener.stop() } }

    fun listen() {
        listening = true
        listener.start(tag)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else onMessage(voiceErrorMessage(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, languageName))
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                tag = nextVoiceLanguage(tag)
                prefs.edit().putString("language", tag).apply()
            },
            enabled = !listening,
            modifier = Modifier.semantics { contentDescription = "Voice language: $languageName. Tap to change." },
        ) {
            // While listening the chip becomes the live status, so it is clear the mic is on.
            if (listening) Text("Listening…", color = Lime, fontSize = 14.sp)
            else Text(voiceChipLabel(tag), color = Muted, fontSize = 14.sp)
        }
        IconButton(onClick = {
            when {
                // A second tap means "I'm done": send what was heard instead of throwing it away.
                listening -> listener.finish()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                else -> askPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_mic),
                contentDescription = if (listening) "Finish speaking" else "Speak",
                tint = if (listening) Lime else Paper,
            )
        }
    }
}
