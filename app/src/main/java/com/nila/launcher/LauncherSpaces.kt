package com.nila.launcher

import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
 * Starting favourites: only apps NILA can't stand in for — immersive media, browsing, social.
 * Utility apps (clock, calendar, contacts, file manager, recorder) are excluded because NILA
 * answers or prepares those; Phone, Messages, Camera and Maps already sit in the home dock,
 * so suggesting them again would be a duplicate entry point.
 */
internal fun suggestedHomeApps(apps: List<InstalledApp>): List<String> {
    val suggested = listOf(
        apps.firstOrNull { it.label.equals("Chrome", true) || it.label.contains("browser", true) },
        apps.firstOrNull { it.label.equals("YouTube", true) },
        apps.firstOrNull { it.label.contains("photos", true) || it.label.contains("albums", true) || it.label.contains("gallery", true) },
        apps.firstOrNull { it.label.contains("instagram", true) },
        apps.firstOrNull { it.label.contains("whatsapp", true) },
        apps.firstOrNull { it.label.contains("spotify", true) },
    ).filterNotNull().map(InstalledApp::packageName)
    return suggested.distinct().take(8)
}

internal fun reconcileHomePins(
    savedPins: Set<String>?,
    installedPackages: Set<String>,
    suggestedPackages: List<String>,
): List<String> = (savedPins?.toList() ?: suggestedPackages).filter(installedPackages::contains)

private val DrawerSearchFill = Color(0xFF20211E)
private val DrawerLabel = Color(0xFFD8D6CE)
private val DrawerNote = Color(0xFF8E8D84)
private const val DRAWER_COLUMNS = 4

private enum class DrawerSection { Favourites, All }

/** What one cell of the drawer grid shows, so favourites, the A–Z list and their headings share one grid. */
private sealed interface DrawerCell {
    data class Heading(val title: String) : DrawerCell
    data class App(val app: InstalledApp, val section: DrawerSection) : DrawerCell
}

/**
 * All apps: favourites first, then every installed app A–Z, with a search that falls through to NILA when no
 * app matches. Tap opens an app; hold adds or removes it from favourites.
 */
@Composable
internal fun AppsSpace(
    apps: List<InstalledApp>,
    pinnedPackages: List<String>,
    onOpenApp: (InstalledApp) -> Unit,
    onTogglePin: (InstalledApp) -> Unit,
    onAskNila: (String) -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onChangeWallpaper: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = apps.filter { it.label.contains(query, ignoreCase = true) }
    val favourites = if (query.isBlank()) pinnedPackages.mapNotNull { packageName -> apps.firstOrNull { it.packageName == packageName } } else emptyList()
    val cells = buildList {
        if (favourites.isNotEmpty()) {
            add(DrawerCell.Heading("FAVOURITES"))
            favourites.forEach { add(DrawerCell.App(it, DrawerSection.Favourites)) }
            add(DrawerCell.Heading("A–Z"))
        }
        filtered.forEach { add(DrawerCell.App(it, DrawerSection.All)) }
    }
    fun search() {
        val request = query.trim()
        if (request.isEmpty()) return
        filtered.firstOrNull()?.let(onOpenApp) ?: onAskNila(request)
    }

    Column(Modifier.fillMaxSize().closeOnPullDown(onBack).background(Night).padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
        TopAction(label = "ALL APPS", onBack = onBack) {
            RoundButton(LucideR.drawable.lucide_ic_image, "Change wallpaper", onChangeWallpaper)
            RoundButton(LucideR.drawable.lucide_ic_settings, "Settings", onOpenSettings)
        }
        Row(
            Modifier.padding(top = 25.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(17.dp)).background(DrawerSearchFill)
                .border(1.dp, Stroke, RoundedCornerShape(17.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(painterResource(LucideR.drawable.lucide_ic_search), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search apps or ask NILA", color = Muted, fontSize = 13.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Paper),
                    singleLine = true,
                    cursorBrush = SolidColor(Lime),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                )
            }
        }
        if (query.isNotBlank()) {
            Text(
                "Ask NILA “${query.trim()}”",
                color = Lime,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 14.dp).clickable { onAskNila(query.trim()) }.padding(vertical = 4.dp),
            )
        }
        val gridState = rememberLazyGridState()
        val scope = rememberCoroutineScope()
        // First grid index where each letter starts in the A–Z list — same alphabet-jump pattern as
        // every stock Android app drawer, so there's nothing new here to learn.
        val letterIndex = remember(cells) {
            val map = LinkedHashMap<Char, Int>()
            cells.forEachIndexed { index, cell ->
                if (cell is DrawerCell.App && cell.section == DrawerSection.All) {
                    map.getOrPut(cell.app.label.firstOrNull()?.uppercaseChar() ?: '#') { index }
                }
            }
            map
        }

        Row(Modifier.weight(1f).padding(top = 22.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(DRAWER_COLUMNS),
                state = gridState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                cells.forEach { cell ->
                    when (cell) {
                        is DrawerCell.Heading -> item(key = cell.title, span = { GridItemSpan(maxLineSpan) }) {
                            Text(cell.title, color = Muted, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.15.em)
                        }
                        is DrawerCell.App -> item(key = "${cell.section}:${cell.app.packageName}") {
                            DrawerApp(cell.app, isFavourite = cell.app.packageName in pinnedPackages, onOpenApp, onTogglePin)
                        }
                    }
                }
                item(key = "note", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Stroke))
                        Text(
                            if (filtered.isEmpty()) "No app matches. Press search to ask NILA instead."
                            else "Hold an app to add it to favourites or take it off. Swipe up from Home to come back here.",
                            color = DrawerNote,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 17.dp),
                        )
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
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DrawerApp(app: InstalledApp, isFavourite: Boolean, onOpenApp: (InstalledApp) -> Unit, onTogglePin: (InstalledApp) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(
            onClick = { onOpenApp(app) },
            onLongClick = { onTogglePin(app) },
        ).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AndroidView(
            factory = { context -> ImageView(context).apply { setImageDrawable(app.icon) } },
            update = { it.setImageDrawable(app.icon) },
            modifier = Modifier.size(48.dp),
        )
        Text(
            app.label,
            color = DrawerLabel,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        if (isFavourite) Box(Modifier.padding(top = 4.dp).size(5.dp).background(Lime, CircleShape))
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

private const val PULL_TO_CLOSE_PX = 220f
private const val FLING_TO_CLOSE_PX_PER_S = 2_400f

/**
 * Swipe down closes the drawer, as on other launchers: a pull while the app grid is already at its top
 * (scroll the grid can't use) or a drag on the header. Without it the drawer only closed with its back button.
 */
@Composable
private fun Modifier.closeOnPullDown(onClose: () -> Unit): Modifier {
    val close by rememberUpdatedState(onClose)
    val connection = remember {
        object : NestedScrollConnection {
            var pulled = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                pulled = if (available.y > 0f) pulled + available.y else 0f
                if (pulled > PULL_TO_CLOSE_PX) {
                    pulled = 0f
                    close()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (available.y > FLING_TO_CLOSE_PX_PER_S) close()
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
    return nestedScroll(connection).pointerInput(Unit) {
        var dragged = 0f
        detectVerticalDragGestures(
            onDragStart = { dragged = 0f },
            onVerticalDrag = { _, amount ->
                dragged += amount
                if (dragged > PULL_TO_CLOSE_PX) {
                    dragged = 0f
                    close()
                }
            },
        )
    }
}
