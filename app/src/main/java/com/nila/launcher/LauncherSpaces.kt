package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.app.WallpaperManager
import android.widget.ImageView
import android.widget.TextClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.R as LucideR

internal data class InstalledApp(val label: String, val packageName: String, val icon: Drawable)

internal fun loadInstalledApps(context: Context): List<InstalledApp> {
    val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        .asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .map {
            InstalledApp(
                label = it.loadLabel(context.packageManager).toString(),
                packageName = it.activityInfo.packageName,
                icon = it.loadIcon(context.packageManager),
            )
        }
        .distinctBy(InstalledApp::packageName)
        .sortedBy { it.label.lowercase() }
        .toList()
}

/**
 * Only apps NILA can't stand in for: immersive media, browsing, maps, social.
 * Utility apps (clock, calendar, contacts, file manager, recorder) and Phone/Messages
 * are deliberately excluded — NILA answers or prepares those, and Phone/Messages
 * already have a dock icon, so pinning the real app too would be a duplicate entry point.
 */
internal fun suggestedHomeApps(apps: List<InstalledApp>): List<String> {
    val suggested = listOf(
        apps.firstOrNull { it.label.contains("camera", true) },
        apps.firstOrNull { it.label.equals("Chrome", true) || it.label.contains("browser", true) },
        apps.firstOrNull { it.label.equals("YouTube", true) },
        apps.firstOrNull { it.label.contains("maps", true) },
        apps.firstOrNull { it.label.contains("photos", true) || it.label.contains("albums", true) || it.label.contains("gallery", true) },
        apps.firstOrNull { it.label.contains("instagram", true) },
        apps.firstOrNull { it.label.contains("whatsapp", true) },
        apps.firstOrNull { it.label.contains("spotify", true) },
    ).filterNotNull().map(InstalledApp::packageName)
    return suggested.distinct().take(8)
}

// NILA's presence moved off this dock entirely: a character shrunk to icon size and
// parked in a utility tray read as "a feature bolted on" no matter how it was drawn.
// She now stands full-body on the home screen itself, next to her speech-bubble pill
// (see HomeSpace) — a presence, not a control. This dock is just the three plain
// utility shortcuts again.
@Composable
internal fun NilaDock(
    onOpenPhone: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenApps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(32.dp), shadowElevation = 10.dp) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockIcon(LucideR.drawable.lucide_ic_phone, "Phone", onOpenPhone)
            DockIcon(LucideR.drawable.lucide_ic_messages_square, "Messages", onOpenMessages)
            DockIcon(LucideR.drawable.lucide_ic_grid_2x2, "Apps", onOpenApps)
        }
    }
}

@Composable
private fun DockIcon(icon: Int, label: String, onClick: () -> Unit, tint: Color = Paper) {
    IconButton(onClick = onClick, modifier = Modifier.size(42.dp)) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(23.dp))
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun HomeSpace(
    apps: List<InstalledApp>,
    pinnedPackages: List<String>,
    nilaMood: MascotMood,
    onOpenNila: () -> Unit,
    onOpenApp: (InstalledApp) -> Unit,
    onChangeWallpaper: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val wallpaper = remember { runCatching { WallpaperManager.getInstance(context).drawable }.getOrNull() }
    var greeting by rememberSaveable { mutableStateOf(true) }
    var resting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1800)
        greeting = false
    }
    val displayMood = when {
        resting -> MascotMood.Sleeping
        greeting && nilaMood == MascotMood.Idle -> MascotMood.Greeting
        else -> nilaMood
    }
    Box(Modifier.fillMaxSize()) {
        wallpaper?.let { drawable ->
            AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(drawable) } },
                update = { it.setImageDrawable(drawable) },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (wallpaper == null) 1f else 0.58f)))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp).padding(bottom = 86.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("NILA", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_settings), contentDescription = "Settings", tint = Paper)
                }
                IconButton(onClick = onChangeWallpaper) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_image), contentDescription = "Change wallpaper", tint = Paper)
                }
            }

            AndroidView(
                factory = {
                    TextClock(it).apply {
                        format12Hour = "h:mm"
                        format24Hour = "HH:mm"
                        textSize = 56f
                        setTextColor(android.graphics.Color.WHITE)
                        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                    }
                },
                modifier = Modifier.height(70.dp).padding(top = 6.dp),
            )
            Text(
                java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE · d MMMM")),
                color = Muted,
                style = MaterialTheme.typography.titleMedium,
            )

            // She stands on the home screen, full-body, directly on the dark background
            // (no tinted circle — that was the round-3 mistake again: a container around
            // her reads as an icon, not a character). The pill beside her is her speech
            // bubble, its text mirroring whatever she's doing right now.
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp).combinedClickable(
                    onClick = { if (resting) resting = false else onOpenNila() },
                    onLongClick = { resting = !resting },
                ),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NilaMascot(mood = displayMood, size = 104.dp)
                Surface(
                    Modifier.weight(1f).padding(bottom = 18.dp),
                    color = Panel,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val pillText = when {
                            resting -> "NILA is resting · tap to wake"
                            displayMood == MascotMood.Greeting -> "Hey, I'm NILA"
                            nilaMood == MascotMood.Thinking -> "Thinking…"
                            nilaMood == MascotMood.Listening -> "Waiting on you…"
                            nilaMood == MascotMood.Error -> "Couldn't do that"
                            nilaMood == MascotMood.Talking -> "Here's what I found"
                            else -> "Ask NILA anything"
                        }
                        Text(pillText, color = Muted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Icon(painterResource(LucideR.drawable.lucide_ic_search), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Bottom) {
                val pinned = pinnedPackages.mapNotNull { packageName -> apps.firstOrNull { it.packageName == packageName } }
                if (pinned.isNotEmpty()) {
                    pinned.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { app ->
                                Column(
                                    Modifier.weight(1f).clickable { onOpenApp(app) }.padding(vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Box(
                                        Modifier.size(52.dp).background(Color.White.copy(alpha = 0.08f), CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        AndroidView(
                                            factory = { ImageView(it).apply { setImageDrawable(app.icon) } },
                                            update = { it.setImageDrawable(app.icon) },
                                            modifier = Modifier.size(34.dp),
                                        )
                                    }
                                    Text(app.label, color = Paper, fontSize = 10.sp, maxLines = 1)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else {
                    Text("Hold an app in Apps to pin it here.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AppsSpace(
    apps: List<InstalledApp>,
    pinnedPackages: List<String>,
    onOpenApp: (InstalledApp) -> Unit,
    onTogglePin: (InstalledApp) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = apps.filter { it.label.contains(query, ignoreCase = true) }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Apps", color = Paper, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text("Tap to open · hold to pin or remove from Home", color = Muted, fontSize = 12.sp)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search apps", color = Muted) },
            leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_search), contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
        )
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        // First grid index where each letter starts — same alphabet-jump pattern as
        // every stock Android app drawer, so there's nothing new here to learn.
        val letterIndex = remember(filtered) {
            val map = LinkedHashMap<Char, Int>()
            filtered.forEachIndexed { index, app ->
                val letter = app.label.firstOrNull()?.uppercaseChar() ?: '#'
                map.getOrPut(letter) { index }
            }
            map
        }

        Row(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(filtered, key = InstalledApp::packageName) { app ->
                    Column(
                        Modifier.fillMaxWidth().combinedClickable(
                            onClick = { onOpenApp(app) },
                            onLongClick = { onTogglePin(app) },
                        ).padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        AndroidView(
                            factory = { context -> ImageView(context).apply { setImageDrawable(app.icon) } },
                            update = { it.setImageDrawable(app.icon) },
                            modifier = Modifier.size(48.dp),
                        )
                        Text(app.label, color = Paper, fontSize = 10.sp, maxLines = 2, lineHeight = 12.sp)
                        if (app.packageName in pinnedPackages) {
                            Box(Modifier.size(5.dp).background(Lime, CircleShape))
                        }
                    }
                }
            }
            if (letterIndex.size > 1) {
                Column(
                    Modifier.fillMaxHeight().padding(start = 2.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    letterIndex.keys.forEach { letter ->
                        Text(
                            letter.toString(),
                            color = Muted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                scope.launch { gridState.scrollToItem(letterIndex.getValue(letter)) }
                            },
                        )
                    }
                }
            }
        }
        if (filtered.isEmpty()) Text("No matching apps", color = Muted)
    }
}

internal fun openDialer(context: Context, number: String) {
    val intent = Intent(Intent.ACTION_DIAL).apply {
        if (number.isNotBlank()) data = Uri.fromParts("tel", number, null)
    }
    runCatching { context.startActivity(intent) }
}

internal fun launchInstalledApp(context: Context, packageName: String) {
    context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
        runCatching { context.startActivity(launch) }
    }
}

internal fun changeWallpaper(context: Context) {
    runCatching { context.startActivity(Intent(Intent.ACTION_SET_WALLPAPER)) }
        .onFailure { android.widget.Toast.makeText(context, "No wallpaper picker is available.", android.widget.Toast.LENGTH_SHORT).show() }
}

internal fun openMessaging(context: Context, compose: Boolean = false) {
    val intent = if (compose) Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")) else {
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            if (!compose) runCatching { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))) }
        }
}
