package com.nila.launcher

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
private const val WAVE_REST_SCALE = 0.25f
private const val WAVE_MIN_ALPHA = 0.45f
private val WaveHeight = 44.dp
private val WaveBarHeights = listOf(14.dp, 30.dp, 22.dp, 40.dp, 22.dp, 30.dp, 14.dp)
private val WaveBarDelaysMs = listOf(0, 200, 450, 100, 350, 200, 0)
private const val STOP_BORDER_ALPHA = 0.5f
private val SpokenSize = 38.sp
private val HeroMascotSize = 72.dp
private val HeroBottomGap = 12.dp
private val RoundTouchSize = 48.dp
private val RoundFaceSize = 40.dp

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
 * The agent screen's listening state and the hero of the screen while the mic is in use: what NILA heard sits
 * low in large serif type and waits for "Use this request" before anything runs, so a misheard request never
 * acts on its own. It carries the only mascot on screen while it shows.
 */
@Composable
internal fun ListeningTurn(
    voice: VoiceCapture,
    heard: String?,
    notice: String?,
    modifier: Modifier = Modifier,
    onUseRequest: (String) -> Unit,
) {
    val mood = when (voice.state) {
        VoiceState.LISTENING -> MascotMood.Listening
        VoiceState.UNDERSTANDING -> MascotMood.Thinking
        VoiceState.IDLE -> if (notice != null) MascotMood.Error else MascotMood.Idle
    }
    val failed = heard == null && notice != null && voice.state == VoiceState.IDLE
    Column(modifier.fillMaxSize().padding(bottom = HeroBottomGap), verticalArrangement = Arrangement.Bottom) {
        SourceLabel(listeningLabel(voice.state, heard), color = if (failed) Muted else Lime)
        SpokenText(
            text = heard ?: notice ?: when (voice.state) {
                VoiceState.UNDERSTANDING -> "One moment…"
                else -> "I'm listening."
            },
            quoted = heard != null,
            modifier = Modifier.padding(top = 14.dp),
        )
        Row(
            Modifier.padding(top = 28.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.clip(CircleShape).clickable(
                    onClickLabel = if (voice.state == VoiceState.LISTENING) "Finish speaking" else "Speak",
                    onClick = voice::toggle,
                )
            ) { NilaMascot(mood = mood, size = HeroMascotSize) }
            VoiceWave(active = voice.state == VoiceState.LISTENING)
        }
        when {
            voice.state == VoiceState.LISTENING -> StopAction(onClick = voice::toggle)
            voice.state == VoiceState.UNDERSTANDING -> Spacer(Modifier.height(PrimaryActionHeight))
            heard != null -> {
                PrimaryAction("Use this request", onClick = { onUseRequest(heard) })
                TextAction("Say it again", onClick = voice::toggle, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp))
            }
            else -> PrimaryAction("Try again", onClick = voice::toggle)
        }
    }
}

/** Ends the recording and sends what was said: a full-width outline, easy to find mid-sentence. */
@Composable
private fun StopAction(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(PrimaryActionHeight).semantics { contentDescription = "Stop and use what I said" },
        shape = RoundedCornerShape(ActionRadius),
        border = BorderStroke(1.dp, Paper.copy(alpha = STOP_BORDER_ALPHA)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Paper),
    ) {
        Icon(painterResource(LucideR.drawable.lucide_ic_square), contentDescription = null, modifier = Modifier.size(16.dp))
        Text("Stop", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun SpokenText(text: String, quoted: Boolean, modifier: Modifier = Modifier) {
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
        fontSize = SpokenSize,
        lineHeight = SpokenSize * SPOKEN_LINE_HEIGHT,
        letterSpacing = (-0.04).em,
        modifier = modifier,
    )
}

/** Seven bars that pulse while the mic is open and rest otherwise; they show state, not the actual loudness. */
@Composable
private fun VoiceWave(active: Boolean) {
    val motion = rememberInfiniteTransition(label = "voice-wave")
    Row(Modifier.height(WaveHeight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
        Modifier.width(4.dp).height(barHeight)
            .graphicsLayer { scaleY = scale; alpha = scale.coerceAtLeast(WAVE_MIN_ALPHA) }
            .background(Lime, RoundedCornerShape(4.dp))
    )
}

/** The prototype's top row: a round back button, an optional small spaced-out label centred, and contextual actions. */
@Composable
internal fun TopAction(onBack: () -> Unit, label: String? = null, trailing: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        RoundButton(LucideR.drawable.lucide_ic_chevron_left, "Back", onBack, Modifier.align(Alignment.CenterStart))
        label?.let { StateLabel(it, Modifier.align(Alignment.Center)) }
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
    // A 48dp touch target around the prototype's 40dp circle.
    Box(modifier.size(RoundTouchSize).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(RoundFaceSize).clip(CircleShape).background(RoundFill).border(1.dp, Stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = description, tint = Paper, modifier = Modifier.size(19.dp))
        }
    }
}
