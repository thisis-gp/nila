package com.nila.launcher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import com.composables.icons.lucide.R as LucideR

private val ListeningGlow = Color(0xFF4A5132)
private val ListeningShade = Color(0xFF171A13)
internal val Night = Color(0xFF11120F)
private val SpokenMuted = Color(0xFFAEAD9F)
internal val LabelInk = Color(0xFFCFCEC5)
internal val Stroke = Color.White.copy(alpha = 0.16f)
internal val RoundFill = Color(0xCC171914)

private const val GLOW_CENTER_Y = 0.72f
private const val GLOW_SHADE_STOP = 0.42f
private const val GLOW_NIGHT_STOP = 0.70f
private const val WAVE_PERIOD_MS = 800
private const val WAVE_REST_SCALE = 0.3f
private const val WAVE_MIN_ALPHA = 0.45f
private val WaveBarHeights = listOf(10.dp, 23.dp, 16.dp, 10.dp, 23.dp)
private val WaveBarDelaysMs = listOf(0, 200, 450, 0, 200)

/** A request names its action first; the words after it, up to the first detail, are what it is about. */
private val ActionVerbs = setOf(
    "find", "order", "get", "book", "set", "open", "call", "play", "show", "search", "remind", "navigate", "take", "add", "buy",
)
private val FillerWords = setOf("a", "an", "the", "me", "my", "some", "us")
private val DetailWords = setOf(
    "for", "under", "at", "to", "in", "with", "from", "near", "on", "by", "before", "after", "within", "and", "below", "over", "around",
)
private const val MAX_KEY_WORDS = 4

/**
 * Character range of the phrase the listening layer highlights, e.g. "light dinner" in
 * "Find a light dinner for three under ₹800". Null when the request names no action.
 */
internal fun requestKeyPhrase(request: String): IntRange? {
    val words = Regex("""\S+""").findAll(request).toList()
    fun bare(word: MatchResult) = word.value.lowercase().trim { !it.isLetterOrDigit() }
    val verb = words.indexOfFirst { bare(it) in ActionVerbs }
    if (verb < 0) return null
    val phrase = words.drop(verb + 1)
        .dropWhile { bare(it) in FillerWords }
        .takeWhile { bare(it).isNotEmpty() && bare(it) !in DetailWords && it.value.none(Char::isDigit) }
        .take(MAX_KEY_WORDS)
    if (phrase.isEmpty()) return null
    val end = phrase.last().range.first + phrase.last().value.trimEnd { !it.isLetterOrDigit() }.length
    return phrase.first().range.first until end
}

/**
 * The full-screen layer NILA opens when invoked: it listens straight away, shows what it heard in large type
 * and waits for "Use this request" before anything runs, so a misheard request never acts on its own.
 */
@Composable
internal fun ListeningLayer(
    transcribe: suspend (wav: ByteArray, languageName: String?) -> String?,
    onListenStart: () -> Unit,
    onUseRequest: (String) -> Unit,
    onTypeInstead: () -> Unit,
    onClose: () -> Unit,
) {
    var heard by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceCapture(
        transcribe = transcribe,
        onFinal = { heard = it },
        onMessage = { notice = it },
        onListenStart = {
            heard = null
            notice = null
            onListenStart()
        },
    )
    LaunchedEffect(Unit) { voice.toggle() }

    val label = when (voice.state) {
        VoiceState.LISTENING -> "LISTENING"
        VoiceState.UNDERSTANDING -> "UNDERSTANDING"
        VoiceState.IDLE -> if (heard != null) "HEARD YOU" else "READY"
    }
    val mood = when (voice.state) {
        VoiceState.LISTENING -> MascotMood.Listening
        VoiceState.UNDERSTANDING -> MascotMood.Thinking
        VoiceState.IDLE -> if (notice != null) MascotMood.Error else MascotMood.Idle
    }

    Column(
        Modifier.fillMaxSize().drawBehind {
            val center = Offset(size.width / 2, size.height * GLOW_CENTER_Y)
            // CSS "circle at": the gradient reaches the farthest corner.
            val radius = hypot(maxOf(center.x, size.width - center.x), maxOf(center.y, size.height - center.y))
            drawRect(
                Brush.radialGradient(
                    0f to ListeningGlow,
                    GLOW_SHADE_STOP to ListeningShade,
                    GLOW_NIGHT_STOP to Night,
                    center = center,
                    radius = radius,
                )
            )
        }.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 32.dp),
    ) {
        TopAction(label = label, onBack = onClose)
        Spacer(Modifier.weight(1f))
        Text("Say it naturally", color = SpokenMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 15.dp))
        SpokenText(
            text = heard ?: notice ?: when (voice.state) {
                VoiceState.UNDERSTANDING -> "One moment…"
                else -> "I'm listening."
            },
            quoted = heard != null,
        )
        Spacer(Modifier.height(65.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.clip(CircleShape).clickable(onClick = voice::toggle)) {
                NilaMascot(mood = mood, size = 72.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VoiceWave(active = voice.state == VoiceState.LISTENING)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val request = heard
                    when {
                        request != null -> Pill("Use this request") { onUseRequest(request) }
                        voice.state == VoiceState.LISTENING -> Pill("Done", onClick = voice::toggle)
                        voice.state == VoiceState.IDLE -> Pill("Try again", onClick = voice::toggle)
                    }
                    Pill("Type instead", onClick = onTypeInstead)
                }
            }
        }
    }
}

@Composable
private fun SpokenText(text: String, quoted: Boolean) {
    val key = if (quoted) requestKeyPhrase(text) else null
    val styled = buildAnnotatedString {
        if (quoted) append("“")
        if (key == null) append(text) else {
            append(text.substring(0, key.first))
            withStyle(SpanStyle(color = Lime)) { append(text.substring(key)) }
            append(text.substring(key.last + 1))
        }
        if (quoted) append("”")
    }
    Text(
        styled,
        color = Paper,
        fontFamily = FontFamily.Serif,
        fontSize = 39.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.04).em,
    )
}

/** Five bars that pulse while the mic is open and rest otherwise; they show state, not the actual loudness. */
@Composable
private fun VoiceWave(active: Boolean) {
    val motion = rememberInfiniteTransition(label = "voice-wave")
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        WaveBarHeights.zip(WaveBarDelaysMs).forEach { (barHeight, delayMs) ->
            val scale by motion.animateFloat(
                initialValue = 1f,
                targetValue = WAVE_REST_SCALE,
                animationSpec = infiniteRepeatable(
                    tween(WAVE_PERIOD_MS, delayMillis = delayMs, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "wave-bar",
            )
            WaveBar(barHeight, if (active) scale else WAVE_REST_SCALE)
        }
    }
}

@Composable
private fun WaveBar(barHeight: Dp, scale: Float) {
    Box(
        Modifier.width(3.dp).height(barHeight)
            .graphicsLayer { scaleY = scale; alpha = scale.coerceAtLeast(WAVE_MIN_ALPHA) }
            .background(Lime, RoundedCornerShape(4.dp))
    )
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Paper,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(CircleShape).background(Panel).border(1.dp, Stroke, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

/** The prototype's top row: a round back button and a small spaced-out state label, centred. */
@Composable
internal fun TopAction(label: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        RoundButton(LucideR.drawable.lucide_ic_chevron_left, "Back", onBack, Modifier.align(Alignment.CenterStart))
        Text(
            label,
            color = LabelInk,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.15.em,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) { trailing() }
    }
}

@Composable
internal fun RoundButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(RoundFill).border(1.dp, Stroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = Paper, modifier = Modifier.size(19.dp))
    }
}
