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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.composables.icons.lucide.R as LucideR

internal val Night = Color(0xFF11120F)
internal val LabelInk = Color(0xFFCFCEC5)
internal val Stroke = Color.White.copy(alpha = 0.16f)
internal val RoundFill = Color(0xCC171914)

private const val SPOKEN_LINE_HEIGHT = 1.05f
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

/** The spaced label above the inline listening state: what NILA is doing with the person's voice right now. */
internal fun listeningLabel(state: VoiceState, heard: String?): String = when (state) {
    VoiceState.LISTENING -> "LISTENING"
    VoiceState.UNDERSTANDING -> "UNDERSTANDING"
    VoiceState.IDLE -> if (heard != null) "HEARD YOU" else "READY"
}

/**
 * The agent screen's listening state, shown where the next request goes: it shows what NILA heard in large
 * type and waits for "Use this request" before anything runs, so a misheard request never acts on its own.
 */
@Composable
internal fun ListeningTurn(voice: VoiceCapture, heard: String?, notice: String?, onUseRequest: (String) -> Unit) {
    val mood = when (voice.state) {
        VoiceState.LISTENING -> MascotMood.Listening
        VoiceState.UNDERSTANDING -> MascotMood.Thinking
        VoiceState.IDLE -> if (notice != null) MascotMood.Error else MascotMood.Idle
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StateLabel(listeningLabel(voice.state, heard))
        SpokenText(
            text = heard ?: notice ?: when (voice.state) {
                VoiceState.UNDERSTANDING -> "One moment…"
                else -> "I'm listening."
            },
            quoted = heard != null,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.clip(CircleShape).clickable(onClick = voice::toggle)) {
                NilaMascot(mood = mood, size = 72.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                VoiceWave(active = voice.state == VoiceState.LISTENING)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (heard != null) Pill("Use this request") { onUseRequest(heard) }
                    when (voice.state) {
                        VoiceState.LISTENING -> Pill("Done", onClick = voice::toggle)
                        VoiceState.IDLE -> Pill("Try again", onClick = voice::toggle)
                        VoiceState.UNDERSTANDING -> Unit
                    }
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
        fontSize = HeadlineSize,
        lineHeight = HeadlineSize * SPOKEN_LINE_HEIGHT,
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
        StateLabel(label, Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp)) { trailing() }
    }
}

/** The small spaced-out monospace state label, e.g. "LISTENING". */
@Composable
private fun StateLabel(label: String, modifier: Modifier = Modifier) = Text(
    label,
    color = LabelInk,
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 10.sp,
    letterSpacing = 0.15.em,
    modifier = modifier,
)

@Composable
internal fun RoundButton(icon: Int, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(RoundFill).border(1.dp, Stroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = Paper, modifier = Modifier.size(19.dp))
    }
}
