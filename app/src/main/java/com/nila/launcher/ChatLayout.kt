package com.nila.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The chat's voice: the person's line is small and plain, NILA answers with the mascot and one serif line,
// and a card appears only for structured data, always with the same header.

private val ReplyMascotSize = 40.dp
private val ReplyTextSize = 19.sp
private val UserLineSize = 15.sp
private val CardIconSize = 16.dp
private val ActionPillHeight = 36.dp
private const val USER_LINE_ALPHA = 0.8f
private const val USER_LINE_INDENT = 60
private const val DISABLED_PILL_ALPHA = 0.4f

/** The mood NILA's newest reply shows: Talking while it is fresh, Celebrate after a confirmation. Older replies sit idle. */
internal val LocalFreshReplyMood = compositionLocalOf<MascotMood?> { null }

internal fun isErrorReply(text: String) =
    text.contains("could not", true) || text.contains("unavailable", true) || text.contains("No alarm", true)

/** What the person said: a small plain line on the right, no bubble. */
@Composable
internal fun UserLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Paper.copy(alpha = USER_LINE_ALPHA),
        fontSize = UserLineSize,
        textAlign = TextAlign.End,
        modifier = modifier.fillMaxWidth().padding(start = USER_LINE_INDENT.dp),
    )
}

/** The mascot alone in NILA's reply position, above a card that brings its own title, such as food results. */
@Composable
internal fun NilaMark() {
    NilaMascot(mood = LocalFreshReplyMood.current ?: MascotMood.Idle, size = ReplyMascotSize)
}

/** NILA's reply position: the small mascot, then the content beside it. */
@Composable
internal fun NilaSays(mood: MascotMood, content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NilaMascot(mood = mood, size = ReplyMascotSize, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

/** A plain-words reply. A failure reads quieter and shows the mascot in Error mood. */
@Composable
internal fun NilaLine(text: String) {
    if (text.isBlank()) return
    val failed = isErrorReply(text)
    val mood = if (failed) MascotMood.Error else LocalFreshReplyMood.current ?: MascotMood.Idle
    NilaSays(mood) {
        Text(
            text,
            color = if (failed) Muted else Paper,
            fontFamily = FontFamily.Serif,
            fontSize = ReplyTextSize,
            lineHeight = ReplyTextSize * 1.25f,
        )
    }
}

/** The thinking state in NILA's reply position. */
@Composable
internal fun ThinkingLine(text: String = "Looking that up…") {
    NilaSays(MascotMood.Thinking) {
        Text(text, color = Muted, fontFamily = FontFamily.Serif, fontSize = ReplyTextSize, lineHeight = ReplyTextSize * 1.25f)
    }
}

/**
 * The one card shape for structured data: a small icon and title, then the body, then the actions
 * (one [ActionPill] and quiet [TextAction]s).
 */
@Composable
internal fun ResultCard(
    icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    NilaCard(modifier = modifier, border = BorderStroke(1.dp, Stroke)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = Muted, modifier = Modifier.size(CardIconSize))
            Text(title, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        body()
        if (actions != null) FlowRow(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) { actions() }
    }
}

/** A card's one primary action: a small lime pill. */
@Composable
internal fun ActionPill(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        label,
        color = Ink,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier.heightIn(min = ActionPillHeight).clip(CircleShape)
            .background(if (enabled) Lime else Lime.copy(alpha = DISABLED_PILL_ALPHA))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A suggestion or choice chip: a quiet outlined pill. */
@Composable
internal fun SuggestionChip(label: String, onClick: () -> Unit, accent: Color = Stroke) {
    Text(
        label,
        color = Paper,
        fontSize = 14.sp,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)
            .border(1.dp, accent, CircleShape)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}
