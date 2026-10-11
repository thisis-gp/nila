package com.nila.launcher

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate

/** Steps today against the goal, the last 7 days, and the workouts and weights from chat. */
@Composable
internal fun FitnessTab() {
    val context = LocalContext.current
    val store = remember { FitnessStore(context) }
    var allowed by remember { mutableStateOf(hasStepPermission(context)) }
    var refresh by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(store.entries()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = hasStepPermission(context) }
    var state by remember { mutableStateOf(store.steps()) }
    var noSensor by remember { mutableStateOf(false) }
    LaunchedEffect(allowed, refresh) {
        if (!allowed) return@LaunchedEffect
        noSensor = sampleSteps(context) == null && store.steps() == null
        state = store.steps()
        StepSampleReceiver.schedule(context)
    }
    val today = LocalDate.now()
    val steps = stepsToday(state)?.takeIf { state?.day == today }

    if (!allowed) {
        EmptyState(
            MascotMood.Listening,
            "NILA counts your steps with this phone's own step counter.",
            "Count my steps",
            onAction = { ask.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
        )
    } else {
        NilaCard {
            CardLabel("Steps today", color = Lime)
            Text(if (noSensor) "This phone has no step counter." else "%,d".format(steps ?: 0), color = Paper, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Panel)) {
                Box(Modifier.fillMaxWidth(((steps ?: 0).toFloat() / STEP_GOAL).coerceIn(0f, 1f)).fillMaxHeight().background(Lime))
            }
            Text("Goal %,d · counted from when NILA first read the counter today".format(STEP_GOAL), color = Muted, fontSize = 12.sp)
            TextAction("Refresh", onClick = { refresh++ })
        }
        WeekBars(lastDays(state, today))
    }
    entries.firstOrNull { it.kg != null }?.let { w -> NilaCard { CardLabel("Weight", color = Lime); Text("${entryLine(w).substringAfter("· ")} · ${eventWhen(w.date, null).substringBefore(",")}", color = Paper, fontWeight = FontWeight.Bold) } }
    if (entries.isNotEmpty() || allowed) SerifTitle("Workouts and weight", size = 22.sp, modifier = Modifier.padding(top = 8.dp))
    if (entries.isEmpty() && allowed) {
        EmptyState(MascotMood.Idle, "Nothing logged yet. Say \"I walked 3 km today\" in chat and it shows up here.")
    }
    entries.forEach { e ->
        Surface(color = CardInk, shape = RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entryLine(e), color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(eventWhen(e.date, null).substringBefore(","), color = Muted, fontSize = 12.sp)
                }
                TextAction("Delete", onClick = { entries = store.delete(e.id) }, color = Muted)
            }
        }
    }
}

/** Seven small bars, today last; the text under each says the count, so colour is never the only signal. */
@Composable
private fun WeekBars(days: List<Pair<LocalDate, Int>>) {
    val top = maxOf(STEP_GOAL, days.maxOf { it.second })
    NilaCard {
        CardLabel("Last 7 days", color = Lime)
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            days.forEach { (day, n) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text(if (n >= 1000) "${n / 1000}k" else "$n", color = Muted, fontSize = 10.sp)
                    Box(
                        Modifier.fillMaxWidth().height((BAR_MAX_DP * n / top).coerceAtLeast(2).dp).clip(RoundedCornerShape(4.dp))
                            .background(if (n >= STEP_GOAL) Lime else Paper.copy(alpha = 0.5f)),
                    )
                    Text(day.dayOfWeek.name.take(1), color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

private const val BAR_MAX_DP = 80
