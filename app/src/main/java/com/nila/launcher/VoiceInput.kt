package com.nila.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as LucideR

/** Gemma 4's audio encoder takes 16 kHz mono speech, at most 30 seconds per clip. */
const val VOICE_SAMPLE_RATE = 16_000
private const val MAX_CLIP_MS = 30_000
private const val CHUNK_MS = 100

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

/** Wraps 16-bit mono PCM in a WAV header: the format LiteRT-LM's Content.AudioBytes reads (as in Google's AI Edge Gallery). */
fun wav(pcm: ByteArray, sampleRate: Int = VOICE_SAMPLE_RATE): ByteArray =
    ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1) // PCM, mono
        putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16) // byte rate, block align, bits
        put("data".toByteArray()); putInt(pcm.size); put(pcm)
    }.array()

/** Loudness of one chunk of 16-bit samples, 0 to 32767. */
fun rms(samples: ShortArray, count: Int = samples.size): Double {
    if (count == 0) return 0.0
    var sum = 0.0
    for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
    return sqrt(sum / count)
}

/**
 * Decides when to stop recording: after [quietMs] of quiet once speech has started, or after [waitMs] if
 * nobody speaks. ponytail: fixed loudness threshold; calibrate on the phone or add adaptive noise floor if
 * noisy rooms cut people off.
 */
class EndOfSpeech(private val threshold: Double = 900.0, private val quietMs: Int = 1_300, private val waitMs: Int = 7_000) {
    var heardSpeech = false
        private set
    private var quiet = 0
    private var total = 0

    /** Feeds one chunk; returns true when recording should stop. */
    fun update(loudness: Double, chunkMs: Int): Boolean {
        total += chunkMs
        if (loudness >= threshold) { heardSpeech = true; quiet = 0 } else quiet += chunkMs
        return if (heardSpeech) quiet >= quietMs else total >= waitMs
    }
}

/**
 * Records one spoken request from the microphone as WAV. Stops on its own after the speaker pauses, at
 * [MAX_CLIP_MS], or when [stopRequested] turns true. Returns null when nobody spoke.
 */
@SuppressLint("MissingPermission") // VoiceMic checks RECORD_AUDIO before calling.
suspend fun recordSpeech(stopRequested: () -> Boolean): ByteArray? = withContext(Dispatchers.IO) {
    val minBuffer = AudioRecord.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val chunk = ShortArray(VOICE_SAMPLE_RATE * CHUNK_MS / 1000)
    val recorder = AudioRecord(
        MediaRecorder.AudioSource.VOICE_RECOGNITION, VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, chunk.size * 2 * 4),
    )
    if (recorder.state != AudioRecord.STATE_INITIALIZED) return@withContext null.also { recorder.release() }
    val pcm = ByteArrayOutputStream()
    val endOfSpeech = EndOfSpeech()
    val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    try {
        recorder.startRecording()
        var elapsed = 0
        while (isActive && !stopRequested() && elapsed < MAX_CLIP_MS) {
            val read = recorder.read(chunk, 0, chunk.size)
            if (read <= 0) break
            bytes.clear(); for (i in 0 until read) bytes.putShort(chunk[i])
            pcm.write(bytes.array(), 0, read * 2)
            elapsed += CHUNK_MS
            if (endOfSpeech.update(rms(chunk, read), CHUNK_MS)) break
        }
    } finally {
        recorder.stop()
        recorder.release()
    }
    if (endOfSpeech.heardSpeech) wav(pcm.toByteArray()) else null
}

enum class VoiceState { IDLE, LISTENING, UNDERSTANDING }

/**
 * Language chip and mic for the chat input. Tap to speak; pause or tap again to send. The clip goes to
 * [transcribe] (Gemma 4 E2B, on the phone) with the chip's language as a hint, and the text is sent like a
 * typed request, so voice gets the same rules, checks and confirmations as typing.
 */
@Composable
fun VoiceMic(
    transcribe: suspend (wav: ByteArray, languageName: String) -> String?,
    onFinal: (String) -> Unit,
    onMessage: (String) -> Unit,
    /** Called as recording starts, so NILA stops talking and the mic does not record Lakshmi. */
    onListenStart: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("nila_voice", Context.MODE_PRIVATE) }
    var tag by remember { mutableStateOf(prefs.getString("language", null) ?: voiceLanguages.first().tag) }
    var state by remember { mutableStateOf(VoiceState.IDLE) }
    var stopRequested by remember { mutableStateOf(false) }
    val languageName = voiceLanguages.first { it.tag == tag }.name
    val latestFinal by rememberUpdatedState(onFinal)
    val latestMessage by rememberUpdatedState(onMessage)

    fun listen() {
        onListenStart()
        stopRequested = false
        state = VoiceState.LISTENING
        scope.launch {
            val clip = recordSpeech { stopRequested }
            if (clip == null) {
                state = VoiceState.IDLE
                return@launch latestMessage("I didn't hear anything. Tap the mic and try again, or type instead.")
            }
            state = VoiceState.UNDERSTANDING
            val text = runCatching { transcribe(clip, languageName) }.getOrNull()?.trim()
            state = VoiceState.IDLE
            if (text.isNullOrBlank()) latestMessage("I couldn't understand that. Try again, or type instead.") else latestFinal(text)
        }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else latestMessage("NILA needs microphone access to listen. You can still type.")
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                tag = nextVoiceLanguage(tag)
                prefs.edit().putString("language", tag).apply()
            },
            enabled = state == VoiceState.IDLE,
            modifier = Modifier.semantics { contentDescription = "Voice language: $languageName. Tap to change." },
        ) {
            // While busy the chip becomes the live status, so it is clear what NILA is doing.
            when (state) {
                VoiceState.LISTENING -> Text("Listening…", color = Lime, fontSize = 14.sp)
                VoiceState.UNDERSTANDING -> Text("Understanding…", color = Lime, fontSize = 14.sp)
                VoiceState.IDLE -> Text(voiceChipLabel(tag), color = Muted, fontSize = 14.sp)
            }
        }
        IconButton(
            onClick = {
                when {
                    // A second tap means "I'm done": stop recording and send what was said.
                    state == VoiceState.LISTENING -> stopRequested = true
                    state == VoiceState.UNDERSTANDING -> Unit
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                    else -> askPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        ) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_mic),
                contentDescription = if (state == VoiceState.LISTENING) "Finish speaking" else "Speak",
                tint = if (state == VoiceState.IDLE) Paper else Lime,
            )
        }
    }
}
