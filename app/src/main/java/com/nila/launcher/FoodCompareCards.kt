package com.nila.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The price comparison across delivery apps (design direction A with C's expandable bill): one winner
// card with the final total, a quiet row per other app, unpriced apps last with their reason.

private val WinnerInk = Color(0xFF2C2F14)
private val WinnerStroke = Color(0xFF4C5120)
private val WinnerLabel = Color(0xFFE3E79A)
private val WinnerText = Color(0xFFDFE0C8)
private val InitialSize = 28.dp
private val RowPadding = 16.dp
private const val DIM_ALPHA = 0.55f

/** The comparison block on the payment card. Nothing renders until the board belongs to this cart. */
@Composable
internal fun CompareSection(ranked: RankedComparison, actions: FoodActions) {
    if (ranked.winner == null && ranked.unpriced.isEmpty()) return
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    fun toggle(id: String) { open = if (open == id) null else id }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        summaryLine(ranked)?.let { Text(it, color = Paper, fontSize = 17.sp, lineHeight = 23.sp) }
        ranked.winner?.let { winner ->
            CompareWinnerCard(winner, ranked, expanded = open == winner.provider.id) { toggle(winner.provider.id) }
        }
        val rows = ranked.others.size + ranked.unpriced.size
        if (rows > 0) {
            Surface(color = CardInk, shape = RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth()) {
                Column {
                    ranked.others.forEachIndexed { index, app ->
                        if (index > 0) HorizontalDivider(color = Panel)
                        CompareRow(app, ranked.winner, expanded = open == app.provider.id) { toggle(app.provider.id) }
                    }
                    ranked.unpriced.forEachIndexed { index, (provider, quote) ->
                        if (index > 0 || ranked.others.isNotEmpty()) HorizontalDivider(color = Panel)
                        UnpricedRow(provider, quote, actions)
                    }
                }
            }
        }
        CompareNote()
    }
}

@Composable
internal fun AppInitial(provider: FoodProvider, fill: Color = provider.tint, ink: Color = Color.White) {
    Box(
        Modifier.size(InitialSize).background(fill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(provider.initial.toString(), color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** The cheapest app: its final total large, the ETA, and how much it saves against the next app. */
@Composable
internal fun CompareWinnerCard(winner: PricedApp, ranked: RankedComparison, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        color = WinnerInk,
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, WinnerStroke),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppInitial(winner.provider)
                Text(
                    winner.provider.name + if (ranked.others.isNotEmpty() && ranked.savings != 0L) " · cheapest" else "",
                    color = WinnerLabel,
                    fontSize = 14.sp,
                )
            }
            Text(paiseText(winner.total), color = Paper, fontSize = 48.sp, lineHeight = 52.sp, fontFamily = FontFamily.Serif, modifier = Modifier.padding(top = 6.dp))
            val detail = listOfNotNull(winner.quote.etaMinutes?.let { "$it min" }, winner.quote.coupon?.let { "with $it" }).joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, color = WinnerText, fontSize = 14.sp)
            val runnerUp = ranked.runnerUp
            when {
                runnerUp == null -> Unit
                ranked.savings == 0L -> Text("Same total as ${runnerUp.name}", color = WinnerText, fontSize = 14.sp)
                else -> Text("Saves ${paiseText(ranked.savings ?: 0)} vs ${runnerUp.name}", color = Lime, fontSize = 14.sp)
            }
            if (expanded) {
                HorizontalDivider(color = WinnerStroke, modifier = Modifier.padding(vertical = 6.dp))
                BillLines(winner.quote, color = WinnerText)
            } else {
                Text("Bill details", color = WinnerLabel, fontSize = 12.sp)
            }
        }
    }
}

/** Another priced app: its total and how much more it costs. Tapping it opens its bill. */
@Composable
internal fun CompareRow(app: PricedApp, winner: PricedApp?, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = RowPadding, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppInitial(app.provider)
            Column(Modifier.weight(1f)) {
                Text(app.provider.name, color = Paper, fontSize = 16.sp)
                Text(
                    listOfNotNull(app.quote.etaMinutes?.let { "$it min" }, if (expanded) "Hide bill" else "Bill details").joinToString(" · "),
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(paiseText(app.total), color = Paper, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                val more = winner?.let { app.total - it.total } ?: 0L
                Text(if (more > 0) "${paiseText(more)} more" else "Same total", color = Muted, fontSize = 12.sp)
            }
        }
        if (expanded) {
            HorizontalDivider(color = Panel, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            BillLines(app.quote, color = Muted)
        }
    }
}

/** An app without a price, with the reason and the one thing the user can do about it. */
@Composable
internal fun UnpricedRow(provider: FoodProvider, quote: ProviderQuote, actions: FoodActions) {
    val (reason, action) = when (quote) {
        ProviderQuote.Checking -> "Checking price" to null
        ProviderQuote.SignIn -> "Sign in to compare" to ("Sign in" to { actions.signIn(provider) })
        is ProviderQuote.Failed -> "Couldn't reach ${provider.name} · ${quote.reason}" to ("Try again" to { actions.retryQuote(provider.id) })
        is ProviderQuote.NotAvailable -> quote.reason to null
        is ProviderQuote.Priced -> return
    }
    val dim = quote is ProviderQuote.NotAvailable
    Row(
        Modifier.fillMaxWidth().padding(horizontal = RowPadding, vertical = 14.dp).alpha(if (dim) DIM_ALPHA else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (dim) AppInitial(provider, fill = Panel, ink = Muted) else AppInitial(provider)
        Column(Modifier.weight(1f)) {
            Text(provider.name, color = Paper, fontSize = 16.sp)
            Text(reason, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (quote == ProviderQuote.Checking) {
            Box(Modifier.width(56.dp).height(16.dp).background(Panel, RoundedCornerShape(6.dp)))
        }
        action?.let { (label, onClick) ->
            Text(
                label,
                color = Lime,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp),
            )
        }
    }
}

/** Item total, delivery, fees, taxes, coupon and total, every number from the app's own answer. */
@Composable
private fun BillLines(quote: ProviderQuote.Priced, color: Color) {
    val bill = quote.bill
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BillLine("Item total", paiseText(bill.itemTotal), color)
        BillLine("Delivery", if (bill.delivery == 0L) "Free" else paiseText(bill.delivery), color)
        if (bill.platformFee > 0) BillLine("Platform and other fees", paiseText(bill.platformFee), color)
        if (bill.taxes > 0) BillLine("GST and taxes", paiseText(bill.taxes), color)
        if (bill.discount > 0) BillLine(quote.coupon ?: "Discount", "-${paiseText(bill.discount)}", Lime)
        BillLine("Total", paiseText(bill.total), Paper, FontWeight.Bold)
    }
}

@Composable
private fun BillLine(label: String, value: String, color: Color, weight: FontWeight = FontWeight.Normal) {
    Row {
        Text(label, color = color, fontSize = 13.sp, fontWeight = weight, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 13.sp, fontWeight = weight, textAlign = TextAlign.End)
    }
}

@Composable
internal fun CompareNote() {
    Text("Totals at checkout, may change", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
}
