package com.nila.launcher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Searching the phone itself. First: photos by the day they were taken. The search runs on the phone
// through MediaStore; nothing about the photos leaves it.

/** "photos I took on Dec 18 2024": the days to look in ([to] inclusive) and how to say them. */
data class PhotoSearch(val from: LocalDate, val to: LocalDate, val label: String) : NilaResponse

private val PhotoWords = Regex("""\b(photos?|pictures?|pics?|images?|selfies?|screenshots?|videos?|clips?|gallery)\b""")

/** A request to see photos: "show", "find", "my photos", "photos from …". */
private val PhotoAsk = Regex("""\b(show|find|see|view|display|open|look at|where are|search)\b|\bmy (photos?|pictures?|pics?|images?|selfies?|screenshots?|videos?|clips?|gallery)\b|\b(photos?|pictures?|pics?|images?|selfies?|screenshots?|videos?|clips?|gallery) (from|of|on|taken|i took|i clicked|i recorded|recorded)\b""")

/** Doing something with photos is a command, not a search: "take a selfie today", "print pictures on friday". */
private val PhotoAction = Regex("""\b(remind|set)\b|\b(search\s+(the\s+)?web)\b|\b(take|click|capture|record|print|delete|send|share|edit|upload)\s+(a|my|the|some)?\s*(photo|picture|pic|image|selfie|screenshot|video|clip)\b""")
private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
private val monthWord = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""
private val MonthDayYear = Regex("""\b$monthWord\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""")
private val DayMonthYear = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$monthWord,?\s+(\d{4})\b""")
private val NumericDate = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})\b""")
private val MonthYear = Regex("""\b(?:in\s+)?$monthWord\s+(\d{4})\b""")
private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase() }

private fun monthOf(word: String) = months.indexOf(word.take(3)) + 1

private fun dayLabel(date: LocalDate) = "%s %d %s %d".format(
    date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase),
    date.dayOfMonth,
    date.month.name.take(3).lowercase().replaceFirstChar(Char::uppercase),
    date.year,
)

/**
 * The days a photo request means, read from the user's own words; null when it isn't about photos or names
 * no day. A numeric date is day/month/year, as written in India.
 */
fun photoSearch(text: String, today: LocalDate): PhotoSearch? {
    val lower = text.lowercase()
    // Review on #40: "take a selfie today" and "remind me to print pictures on friday" were gallery searches.
    if (!PhotoWords.containsMatchIn(lower) || !PhotoAsk.containsMatchIn(lower) || PhotoAction.containsMatchIn(lower)) return null
    fun day(date: LocalDate?) = date?.let { PhotoSearch(it, it, dayLabel(it)) }
    fun safe(y: Int, m: Int, d: Int) = runCatching { LocalDate.of(y, m, d) }.getOrNull()
    MonthDayYear.find(lower)?.let { m -> return day(safe(m.groupValues[3].toInt(), monthOf(m.groupValues[1]), m.groupValues[2].toInt())) }
    DayMonthYear.find(lower)?.let { m -> return day(safe(m.groupValues[3].toInt(), monthOf(m.groupValues[2]), m.groupValues[1].toInt())) }
    NumericDate.find(lower)?.let { m -> return day(safe(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())) }
    MonthYear.find(lower)?.let { m ->
        val first = safe(m.groupValues[2].toInt(), monthOf(m.groupValues[1]), 1) ?: return null
        val last = first.withDayOfMonth(first.lengthOfMonth())
        return PhotoSearch(first, last, first.month.name.lowercase().replaceFirstChar(Char::uppercase) + " " + first.year)
    }
    return when {
        Regex("""\btoday\b""").containsMatchIn(lower) -> PhotoSearch(today, today, "today")
        Regex("""\byesterday\b""").containsMatchIn(lower) -> PhotoSearch(today.minusDays(1), today.minusDays(1), "yesterday")
        Regex("""\blast week\b""").containsMatchIn(lower) -> PhotoSearch(today.minusDays(7), today.minusDays(1), "the last 7 days")
        Regex("""\blast month\b""").containsMatchIn(lower) -> today.minusMonths(1).let { m ->
            PhotoSearch(m.withDayOfMonth(1), m.withDayOfMonth(m.lengthOfMonth()), m.month.name.lowercase().replaceFirstChar(Char::uppercase) + " " + m.year)
        }
        else -> weekdays.entries.firstOrNull { (name) -> Regex("""\b$name\b""").containsMatchIn(lower) }?.let { (_, d) ->
            today.with(TemporalAdjusters.previous(d)).let { PhotoSearch(it, it, dayLabel(it)) }
        }
    }
}

/** One photo or video on the phone. */
data class PhonePhoto(val uri: Uri, val video: Boolean = false)

private const val MAX_PHOTOS = 60

/**
 * Photos taken between [PhotoSearch.from] and [PhotoSearch.to], newest first. Uses the time the photo was
 * taken, or the time it was added when the camera didn't record one.
 */
internal fun findPhotos(context: Context, search: PhotoSearch): List<PhonePhoto> {
    val zone = ZoneId.systemDefault()
    val start = search.from.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = search.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    // Photos and videos in one list, newest first: MediaStore's Files table holds both.
    val collection = MediaStore.Files.getContentUri(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL else "external")
    val type = MediaStore.Files.FileColumns.MEDIA_TYPE
    val taken = MediaStore.Images.Media.DATE_TAKEN
    val added = MediaStore.MediaColumns.DATE_ADDED
    val selection = "$type IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND " +
        "(($taken >= ? AND $taken < ?) OR (($taken IS NULL OR $taken = 0) AND $added >= ? AND $added < ?))"
    val args = arrayOf(start.toString(), end.toString(), (start / 1000).toString(), (end / 1000).toString())
    val photos = mutableListOf<PhonePhoto>()
    context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID, type), selection, args, "$taken DESC")?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val kind = c.getColumnIndexOrThrow(type)
        while (c.moveToNext() && photos.size < MAX_PHOTOS) {
            val video = c.getInt(kind) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            photos += PhonePhoto(ContentUris.withAppendedId(base, c.getLong(id)), video)
        }
    }
    return photos
}

/** Photos and videos: one permission each on Android 13+, one for both before. */
internal val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun hasMedia(context: Context) = mediaPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** The photos found, as a grid: tap one to open it in the phone's gallery. Asks for photo access first. */
@Composable
internal fun PhotoSearchCard(search: PhotoSearch) {
    val context = LocalContext.current
    fun granted() = mediaPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    // After two refusals Android stops asking: then the button opens NILA's settings instead.
    var blocked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        allowed = granted()
        val activity = context as? android.app.Activity
        blocked = result.values.any { !it } && activity != null && mediaPermissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED && !activity.shouldShowRequestPermissionRationale(it)
        }
    }
    // Back from Android's settings: check again.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) allowed = granted() }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }
    NilaCard {
        SectionLabel("Your photos · ${search.label}", color = Lime)
        if (!allowed) {
            Text("NILA needs access to photos and videos to search them. They stay on this phone.", color = Paper, fontSize = 14.sp)
            if (blocked) {
                PrimaryAction("Open settings to allow photos and videos", onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
            } else {
                PrimaryAction("Allow photo and video access", onClick = { ask.launch(mediaPermissions) })
            }
            return@NilaCard
        }
        val photos by produceState<List<PhonePhoto>?>(null, search) { value = withContext(Dispatchers.IO) { runCatching { findPhotos(context, search) }.getOrDefault(emptyList()) } }
        when {
            photos == null -> Text("Looking…", color = Muted, fontSize = 14.sp)
            photos!!.isEmpty() -> Text("No photos or videos from ${search.label}.", color = Paper, fontSize = 14.sp)
            else -> {
                val videos = photos!!.count { it.video }
                val count = "${photos!!.size}${if (photos!!.size == MAX_PHOTOS) "+" else ""}"
                Text(if (videos > 0) "$count photos and videos ($videos videos). Tap one to open it." else "$count photos. Tap one to open it.", color = Paper, fontSize = 14.sp)
                photos!!.chunked(GRID_COLUMNS).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { photo -> Thumbnail(photo, Modifier.weight(1f)) }
                        repeat(GRID_COLUMNS - row.size) { Column(Modifier.weight(1f)) {} }
                    }
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 3
private const val THUMB_PX = 256

@Composable
private fun Thumbnail(photo: PhonePhoto, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(photo.uri, Size(THUMB_PX, THUMB_PX), null)
                } else {
                    // Android 8-9: Android's own small thumbnail of the photo.
                    @Suppress("DEPRECATION")
                    if (photo.video) {
                        MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(photo.uri), MediaStore.Video.Thumbnails.MINI_KIND, null)
                    } else {
                        MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, ContentUris.parseId(photo.uri), MediaStore.Images.Thumbnails.MINI_KIND, null)
                    }
                }
            }.getOrNull()
        }
    }
    Column(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Panel).clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, photo.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        },
    ) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = if (photo.video) "Video" else "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f)) }
        if (photo.video) Text("▶ video", color = Paper, fontSize = 11.sp, modifier = Modifier.background(Ink.copy(alpha = 0.6f)))
    }
}
