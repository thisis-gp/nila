package com.nila.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicBoolean

// Shared pieces of NILA's card language (the Swiggy booking sheet in FoodCards.kt set the pattern):
// small spaced uppercase labels, serif titles, rounded CardInk surfaces, one lime primary action.

/** Surface for anything that asks before a Commit action runs. */
internal val CommitInk = Color(0xFF321F19)
internal val CardRadius = 18.dp
internal val CardPadding = 16.dp
internal val PrimaryActionHeight = 52.dp
internal val ActionRadius = 16.dp
internal val TitleSize = 22.sp
internal val HeadlineSize = 28.sp
private val LabelSize = 10.sp
private val LabelSpacing = 1.sp
private val LiveDotSize = 6.dp
private const val STEPPER_BORDER_ALPHA = 0.6f

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Muted) = Text(
    text.uppercase(),
    color = color,
    fontSize = LabelSize,
    fontWeight = FontWeight.Bold,
    letterSpacing = LabelSpacing,
    modifier = modifier,
)

/** A section label led by a small dot, like "SWIGGY · LIVE": marks where a card's data came from. */
@Composable
internal fun SourceLabel(text: String, color: Color = Lime) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(LiveDotSize).background(color, CircleShape))
        SectionLabel(text, color = color)
    }
}

@Composable
internal fun SerifTitle(text: String, size: TextUnit = TitleSize, color: Color = Paper, modifier: Modifier = Modifier) = Text(
    text,
    color = color,
    fontSize = size,
    lineHeight = size * 1.15f,
    fontFamily = FontFamily.Serif,
    modifier = modifier,
)

@Composable
internal fun NilaCard(
    modifier: Modifier = Modifier,
    color: Color = CardInk,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = color, shape = RoundedCornerShape(CardRadius), border = border, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun PrimaryAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(PrimaryActionHeight),
        shape = RoundedCornerShape(ActionRadius),
        colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
    ) { Text(label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** Secondary actions read as links, so a card never has two buttons competing. */
@Composable
internal fun TextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Lime) = Text(
    label,
    color = color,
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
)

/** A label/value line, as on the prototype's confirm sheet ("Deliver to … Home"). */
@Composable
internal fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(value, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Lime pill for the one recommended option, like "BEST FIT". */
@Composable
internal fun LimePill(text: String, modifier: Modifier = Modifier) = Text(
    text,
    color = Ink,
    fontSize = 9.sp,
    fontWeight = FontWeight.Black,
    letterSpacing = LabelSpacing,
    modifier = modifier.background(Lime, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
)

/** The cart's − n + control. Callers own the count; this only reports taps. */
@Composable
internal fun QuantityStepper(quantity: Int, onLess: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.border(1.dp, Lime.copy(alpha = STEPPER_BORDER_ALPHA), RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("−", color = Lime, fontSize = 18.sp, modifier = Modifier.clickable(onClickLabel = "One less", onClick = onLess).padding(horizontal = 10.dp, vertical = 2.dp))
        Text("$quantity", color = Paper, fontWeight = FontWeight.Bold)
        Text("+", color = Lime, fontSize = 18.sp, modifier = Modifier.clickable(onClickLabel = "One more", onClick = onMore).padding(horizontal = 10.dp, vertical = 2.dp))
    }
}

/**
 * A tap that runs [action] once at most. A tap can start a real, non-idempotent call (place a COD order,
 * select an address, start OAuth); [tapOnce] is checked inside the handler itself, so two taps in the same
 * frame can't both get through before recomposition disables the control.
 */
@Stable
internal class SingleTap(private val action: State<() -> Unit>) {
    private val consumed = AtomicBoolean(false)
    var used by mutableStateOf(false)
        private set

    fun fire() {
        if (!tapOnce(consumed)) return
        used = true
        action.value()
    }
}

@Composable
internal fun rememberSingleTap(action: () -> Unit): SingleTap {
    val latest = rememberUpdatedState(action)
    return remember { SingleTap(latest) }
}

/** True on the first call, false on every call after — even two calls racing from the same frame. Guards a tap from firing a non-idempotent action (an order, a payment) twice. */
internal fun tapOnce(consumed: AtomicBoolean): Boolean = !consumed.getAndSet(true)
