package com.nila.launcher

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateFormat
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import java.time.LocalDateTime
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.delay
import com.composables.icons.lucide.R as LucideR

// The prototype's olive wallpaper, drawn in layers the same way its CSS stacks them.
private val OliveLight = Color(0xFF849477)
private val OliveMid = Color(0xFF39483A)
private val OliveDeep = Color(0xFF11170F)
private val SunGlow = Color(0xFFD9DC97)
private val TopVeil = Color(0x0D040704)
private val BottomVeil = Color(0x80040603)
private val VignetteMid = Color(0x38090C08)
private val VignetteEdge = Color(0x9E060705)
private val ClockInk = Color(0xFFF6F2E7)
private val HomeInk = Color(0xFFF8F5EB)
private val DockFill = Color(0x7A0E100C)
private val DockStroke = Color.White.copy(alpha = 0.2f)
private val TileFill = Color(0xFFECE9DF)
private val TileInk = Color(0xFF151612)
private val GestureBar = Color.White.copy(alpha = 0.6f)

private const val OLIVE_ANGLE_DEGREES = 145.0
private const val OLIVE_MID_STOP = 0.5f
private const val SUN_X = 0.75f
private const val SUN_Y = 0.20f
private const val SUN_CORE_STOP = 0.09f
private const val SUN_FADE_STOP = 0.31f
private const val VIGNETTE_X = 0.65f
private const val VIGNETTE_Y = 0.45f
private const val VIGNETTE_CLEAR_STOP = 0.18f
private const val VIGNETTE_MID_STOP = 0.44f
/** Dims a photo wallpaper enough for the clock and dock to stay readable on it. */
private const val WALLPAPER_SCRIM_ALPHA = 0.45f
private const val SUGGESTION_HIDDEN_KEY = "suggestion_hidden_on"
private const val GREETING_MS = 1_800L
private const val MINUTE_MS = 60_000L
private const val SWIPE_UP_DP = 64
private const val MORNING_HOUR = 5
private const val AFTERNOON_HOUR = 12
private const val EVENING_HOUR = 17
private const val NIGHT_HOUR = 21

internal enum class PartOfDay(val line: String) {
    Morning("A quiet morning."),
    Afternoon("A quiet afternoon."),
    Evening("A quiet evening."),
    Night("A quiet night."),
}

internal fun partOfDay(hour: Int): PartOfDay = when (hour) {
    in MORNING_HOUR until AFTERNOON_HOUR -> PartOfDay.Morning
    in AFTERNOON_HOUR until EVENING_HOUR -> PartOfDay.Afternoon
    in EVENING_HOUR until NIGHT_HOUR -> PartOfDay.Evening
    else -> PartOfDay.Night
}

/**
 * The quiet home: place and date, a large clock, one calm line about what NILA is doing, the next alarm
 * when Android has one, a morning suggestion from memory when there is one, and a five-slot dock with NILA
 * in the middle. Every other app is a swipe up away.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun HomeScreen(
    resumeVersion: Int,
    nilaMood: MascotMood,
    onInvokeNila: () -> Unit,
    onOpenApps: () -> Unit,
    onChangeWallpaper: () -> Unit,
    facts: List<MemoryFact>,
    onOpenMyNila: () -> Unit,
    onSuggestion: (String) -> Unit,
    onSideDrag: (Float) -> Unit,
    onSideDragEnd: (Float) -> Unit,
) {
    val context = LocalContext.current
    // NILA's olive look by default; the phone's wallpaper only after the user picks one by long-pressing Home.
    // (Reading it used to depend on photo access: granting that silently swapped the olive look out.)
    val wallpaper = remember(context, resumeVersion) {
        if (!usesPhoneWallpaper(context)) null else runCatching { WallpaperManager.getInstance(context).drawable }.getOrNull()
    }
    // City only when location is already allowed; home never asks for it and never shows a guess.
    val city by produceState<String?>(null) { value = CurrentPlaces.locate(context)?.city?.takeIf(String::isNotBlank) }
    val now by produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS)
        }
    }
    val glance by produceState(GlanceData(), resumeVersion) {
        while (true) {
            value = loadGlance(context, LocalDateTime.now())
            delay(MINUTE_MS)
        }
    }
    val clockStyle = if (DateFormat.is24HourFormat(context)) ClockStyle.Hours24 else ClockStyle.Hours12
    val glanceLines = glanceLines(glance, LocalDateTime.of(LocalDate.now(), now), clockStyle)
    val suggestion = remember(facts, now) { homeSuggestion(facts, LocalDateTime.of(LocalDate.now(), now)) }
    val homePrefs = remember(context) { context.getSharedPreferences("nila_launcher", Context.MODE_PRIVATE) }
    var hiddenOn by remember { mutableStateOf(homePrefs.getString(SUGGESTION_HIDDEN_KEY, null)) }
    val suggestionKey = suggestion?.let { "${LocalDate.now()}:${it.meal}" }
    val showSuggestion = suggestion != null && hiddenOn != suggestionKey
    var greeting by rememberSaveable { mutableStateOf(true) }
    var resting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(GREETING_MS)
        greeting = false
    }
    val displayMood = when {
        resting -> MascotMood.Sleeping
        greeting && nilaMood == MascotMood.Idle -> MascotMood.Greeting
        else -> nilaMood
    }
    val statusLine = when {
        resting -> "NILA is resting. Tap her to wake."
        nilaMood == MascotMood.Thinking -> "NILA is working on it."
        nilaMood == MascotMood.Talking -> "NILA has an answer for you."
        nilaMood == MascotMood.Listening -> "NILA is waiting on you."
        nilaMood == MascotMood.Error -> "NILA couldn't do that one."
        else -> userName(facts)?.let { "${greetingFor(now.hour)}, $it" } ?: partOfDay(now.hour).line
    }
    val statusOpensMyNila = !resting && nilaMood in setOf(MascotMood.Idle, MascotMood.Greeting)

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onChangeWallpaper() }) }
            .sideSwipe(onDrag = onSideDrag, onEnd = onSideDragEnd)
            .pointerInput(Unit) {
                var dragged = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = { if (dragged < -SWIPE_UP_DP.dp.toPx()) onOpenApps() },
                ) { _, dragAmount -> dragged += dragAmount }
            },
    ) {
        if (wallpaper == null) {
            Box(Modifier.fillMaxSize().drawBehind { drawOliveWallpaper() })
        } else {
            AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(wallpaper) } },
                update = { it.setImageDrawable(wallpaper) },
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = WALLPAPER_SCRIM_ALPHA)))
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 8.dp)) {
            Column {
                city?.let { Text(it, color = HomeInk, fontSize = 11.sp, letterSpacing = 0.05.em) }
                Text(
                    LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                    color = HomeInk,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            Text(
                now.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm")),
                color = ClockInk,
                fontFamily = FontFamily.Serif,
                fontSize = 94.sp,
                lineHeight = 94.sp,
                letterSpacing = (-0.085).em,
                modifier = Modifier.padding(top = 50.dp),
            )
            Row(
                Modifier.padding(top = 4.dp).then(if (statusOpensMyNila) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Open My NILA", onClick = onOpenMyNila) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(statusLine, color = ClockInk, fontSize = 12.sp, letterSpacing = 0.05.em)
                if (statusOpensMyNila) Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), contentDescription = null, tint = ClockInk, modifier = Modifier.padding(start = 2.dp).size(14.dp))
            }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                glanceLines.forEach { line ->
                    GlanceRow(line) {
                        val intent = glanceIntent(line.target)
                        if (intent == null) onOpenMyNila() else startOrTell(context, intent, "No app is available for that.")
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (showSuggestion && suggestion != null) {
                HomeSuggestion(
                    suggestion = suggestion,
                    onYes = { onSuggestion(suggestion.request) },
                    onNotToday = {
                        hiddenOn = suggestionKey
                        homePrefs.edit().putString(SUGGESTION_HIDDEN_KEY, hiddenOn).apply()
                    },
                )
            }
            HomeDock(
                mood = displayMood,
                onPhone = { openDialer(context, "") },
                onMessages = { openMessaging(context) },
                onNila = { if (resting) resting = false else onInvokeNila() },
                onNilaLongPress = { resting = !resting },
                onCamera = { startOrTell(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "No camera app is available.") },
                onMaps = { startOrTell(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), "No maps app is available.") },
            )
            // The prototype's gesture handle: tapping it opens the drawer too, for anyone who doesn't swipe.
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).size(width = 150.dp, height = 24.dp)
                    .clickable(onClick = onOpenApps)
                    .semantics { contentDescription = "All apps" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 105.dp, height = 4.dp).background(GestureBar, RoundedCornerShape(4.dp)))
            }
        }
    }
}

private fun DrawScope.drawOliveWallpaper() {
    val angle = Math.toRadians(OLIVE_ANGLE_DEGREES)
    val direction = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
    // CSS sizes an angled gradient line so its ends touch the far corners.
    val halfLine = (abs(size.width * direction.x) + abs(size.height * direction.y)) / 2
    val middle = Offset(size.width / 2, size.height / 2)
    drawRect(Brush.linearGradient(0f to OliveLight, OLIVE_MID_STOP to OliveMid, 1f to OliveDeep, start = middle - direction * halfLine, end = middle + direction * halfLine))
    val sun = Offset(size.width * SUN_X, size.height * SUN_Y)
    drawRect(Brush.radialGradient(0f to SunGlow, SUN_CORE_STOP to SunGlow, SUN_FADE_STOP to Color.Transparent, center = sun, radius = farthestCorner(sun)))
    drawRect(Brush.verticalGradient(listOf(TopVeil, BottomVeil)))
    val vignette = Offset(size.width * VIGNETTE_X, size.height * VIGNETTE_Y)
    drawRect(
        Brush.radialGradient(
            0f to Color.Transparent,
            VIGNETTE_CLEAR_STOP to Color.Transparent,
            VIGNETTE_MID_STOP to VignetteMid,
            1f to VignetteEdge,
            center = vignette,
            radius = farthestCorner(vignette),
        )
    )
}

internal fun DrawScope.farthestCorner(point: Offset): Float =
    hypot(maxOf(point.x, size.width - point.x), maxOf(point.y, size.height - point.y))

private fun startOrTell(context: Context, intent: Intent, failure: String) {
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, failure, Toast.LENGTH_SHORT).show() }
}

@Composable
private fun GlanceRow(line: GlanceLine, onClick: () -> Unit) {
    val icon = when (line.icon) {
        GlanceIcon.Calendar -> LucideR.drawable.lucide_ic_calendar
        GlanceIcon.Reminder -> LucideR.drawable.lucide_ic_bell
        GlanceIcon.Alarm -> LucideR.drawable.lucide_ic_alarm_clock
        GlanceIcon.Steps -> LucideR.drawable.lucide_ic_footprints
    }
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = ClockInk, modifier = Modifier.size(GLANCE_ICON_DP.dp))
        Text(line.text, color = ClockInk, fontSize = 12.sp, letterSpacing = 0.05.em, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp))
    }
}

private const val GLANCE_ICON_DP = 13

/** The suggestion as a small pill above the dock, right-aligned: tap orders it, long-press hides it until the next meal. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeSuggestion(suggestion: MemorySuggestion, onYes: () -> Unit, onNotToday: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 18.dp), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier.clip(RoundedCornerShape(SuggestionPillRadius))
                .combinedClickable(onClickLabel = "Order", onLongClickLabel = "Not today", onLongClick = onNotToday, onClick = onYes)
                .background(SuggestionPillFill)
                .border(1.dp, SuggestionPillStroke, RoundedCornerShape(SuggestionPillRadius))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).shadow(6.dp, CircleShape, ambientColor = Lime, spotColor = Lime).background(Lime, CircleShape))
            Text(suggestion.label, color = ClockInk, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

private val SuggestionPillRadius = 24.dp
private val SuggestionPillFill = Color(0x5C0F110D)
private val SuggestionPillStroke = Color.White.copy(alpha = 0.25f)

private fun greetingFor(hour: Int) = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HomeDock(
    mood: MascotMood,
    onPhone: () -> Unit,
    onMessages: () -> Unit,
    onNila: () -> Unit,
    onNilaLongPress: () -> Unit,
    onCamera: () -> Unit,
    onMaps: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(27.dp)).background(DockFill).border(1.dp, DockStroke, RoundedCornerShape(27.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slot = Modifier.weight(1f)
        DockTile(LucideR.drawable.lucide_ic_phone, "Phone", onPhone, slot)
        DockTile(LucideR.drawable.lucide_ic_message_square, "Messages", onMessages, slot)
        Box(slot, contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(Lime)
                    .combinedClickable(onClick = onNila, onLongClick = onNilaLongPress)
                    .semantics { contentDescription = "Talk to NILA" },
                contentAlignment = Alignment.Center,
            ) {
                NilaMascot(mood = mood, size = 62.dp, modifier = Modifier.offset(y = 5.dp))
            }
        }
        DockTile(LucideR.drawable.lucide_ic_camera, "Camera", onCamera, slot)
        DockTile(LucideR.drawable.lucide_ic_map_pin, "Maps", onMaps, slot)
    }
}

@Composable
private fun DockTile(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(43.dp).shadow(5.dp, RoundedCornerShape(14.dp)).background(TileFill, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), contentDescription = label, tint = TileInk, modifier = Modifier.size(21.dp))
        }
    }
}
