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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
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
@SuppressLint("MissingPermission") // VoiceCapture.toggle checks RECORD_AUDIO before calling.
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

/** Where one spoken request is: the agent screen's mic, chip and inline listening state all read it. */
interface VoiceCapture {
    val state: VoiceState

    /** Starts listening, or, while listening, stops and sends what was said. */
    fun toggle()

    /** Stops listening and drops whatever was being recorded or transcribed, e.g. when the person starts typing. */
    fun cancel()
}

/**
 * Records one spoken request at a time. The clip goes to [transcribe] (Gemma 4 E2B, on the phone), which
 * detects English, Hindi or Malayalam itself, and the text goes to [onFinal], so voice gets the same rules as typing.
 */
@Composable
fun rememberVoiceCapture(
    /** Gemma detects the language itself. */
    transcribe: suspend (wav: ByteArray) -> String?,
    onFinal: (String) -> Unit,
    onMessage: (String) -> Unit,
    /** Called as recording starts, so NILA stops talking and the mic does not record Lakshmi. */
    onListenStart: () -> Unit = {},
): VoiceCapture {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentState by remember { mutableStateOf(VoiceState.IDLE) }
    var stopRequested by remember { mutableStateOf(false) }
    // Each recording gets a number; cancel() moves past it so a late transcript from it is ignored.
    val session = remember { AtomicInteger() }
    val latestFinal by rememberUpdatedState(onFinal)
    val latestMessage by rememberUpdatedState(onMessage)

    fun listen() {
        onListenStart()
        stopRequested = false
        currentState = VoiceState.LISTENING
        val mine = session.incrementAndGet()
        scope.launch {
            val clip = recordSpeech { stopRequested || session.get() != mine }
            if (session.get() != mine) return@launch
            if (clip == null) {
                currentState = VoiceState.IDLE
                return@launch latestMessage("I didn't hear anything. Tap the mic and try again, or type instead.")
            }
            currentState = VoiceState.UNDERSTANDING
            val text = runCatching { transcribe(clip) }.getOrNull()?.trim()
            if (session.get() != mine) return@launch
            currentState = VoiceState.IDLE
            if (text.isNullOrBlank()) latestMessage("I couldn't understand that. Try again, or type instead.") else latestFinal(text)
        }
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else latestMessage("NILA needs microphone access to listen. You can still type.")
    }

    return object : VoiceCapture {
        override val state get() = currentState

        override fun toggle() {
            when {
                // A second tap means "I'm done": stop recording and send what was said.
                currentState == VoiceState.LISTENING -> stopRequested = true
                currentState == VoiceState.UNDERSTANDING -> Unit
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                else -> askPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        override fun cancel() {
            if (currentState == VoiceState.IDLE) return
            session.incrementAndGet()
            currentState = VoiceState.IDLE
        }
    }
}

/** The mic for the agent's input bar: Gemma detects the language, so there is no language chip. [onMic] starts listening, or stops it while listening. */

@Composable
fun VoiceMic(voice: VoiceCapture, onMic: () -> Unit) {
    IconButton(onClick = onMic) {
        Icon(
            painterResource(LucideR.drawable.lucide_ic_mic),
            contentDescription = if (voice.state == VoiceState.LISTENING) "Finish speaking" else "Speak",
            tint = if (voice.state == VoiceState.IDLE) Paper else Lime,
        )
    }
}
