package com.nila.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.util.Log
import kotlinx.coroutines.CancellationException
import com.composables.icons.lucide.R as LucideR

/** A health entry offered from the chat ("last week I went to the hospital"): its kind and the day the user said. */
data class HealthPrompt(val kind: HealthKind, val title: String, val date: LocalDate) : NilaResponse

/** A health entry the chat asked to add; MainActivity opens My NILA on it. Compose state, like NilaTimer. */
internal object MyNilaRequests {
    var pending by mutableStateOf<HealthPrompt?>(null)
}

internal enum class MyNilaTab(val label: String) { People("People"), Health("Health"), Reminders("Reminders") }

/**
 * My NILA: its own space for everything NILA knows about the user and their people, and their health
 * tracker. Facts come from chat. A report is added by its photo alone: NILA reads it on the phone and saves
 * what it says. Everything stays encrypted on this phone.
 */
@Composable
internal fun MyNilaScreen(
    facts: List<MemoryFact>,
    onForgetFact: (String) -> Unit,
    onForgetAll: () -> Unit,
    health: HealthStore,
    readReport: suspend (ByteArray) -> ReportRead,
    onSaved: (HealthRecord) -> Unit,
    onDeleted: (HealthRecord) -> Unit,
    reminders: NilaReminders,
    draft: HealthPrompt?,
    onDraftUsed: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(if (draft != null) MyNilaTab.Health else MyNilaTab.People) }
    // The first read builds the Keystore key and decrypts: never on the main thread.
    var records by remember { mutableStateOf<List<HealthRecord>>(emptyList()) }
    LaunchedEffect(Unit) { records = withContext(Dispatchers.IO) { health.records() } }
    var open by remember { mutableStateOf<HealthRecord?>(null) }
    var reading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The day the chat mentioned ("last week"), used when the report prints no date of its own.
    var dateHint by remember { mutableStateOf(draft?.date ?: LocalDate.now()) }
    var photo by remember { mutableStateOf<File?>(null) }

    /**
     * Encrypts the report, reads it and saves the record, all off the main thread. Leaving the screen
     * mid-way cancels it: the encrypted copy of an unsaved report and any plain camera capture are deleted.
     */
    fun readAndSave(uri: Uri, capture: File? = null) {
        problem = null
        reading = true
        scope.launch {
            var name: String? = null
            var saved = false
            try {
                name = withContext(Dispatchers.IO) { health.attach(uri) }
                // Encrypted (or failed): the plain capture is no longer needed either way.
                capture?.let { withContext(Dispatchers.IO) { it.delete() } }
                val stored = name ?: run { problem = "Couldn't read that file."; return@launch }
                val page = withContext(Dispatchers.IO) {
                    health.read(stored)?.let { bytes ->
                        if (HealthStore.isPdf(stored)) pdfFirstPage(bytes, context.cacheDir, READ_SIDE)
                        else scaled(bytes, READ_SIDE)?.let { ReportPage(it, 1) }
                    }
                }
                val result = page?.let { readReport(jpegOf(it.bitmap)) } ?: ReportRead.Unreadable
                val record = recordFrom(
                    (result as? ReportRead.Read)?.reading, stored, dateHint,
                    unavailable = result == ReportRead.Unavailable, pages = page?.pages ?: 1,
                )
                val updated = withContext(Dispatchers.IO) { health.save(record) } ?: run {
                    problem = "Couldn't save that report. Check phone storage and try again."
                    return@launch
                }
                records = updated
                saved = true
                onSaved(record)
                open = record
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The class name only: a message could carry what the report says.
                Log.w("MyNila", "report not saved: ${e::class.java.simpleName}")
                problem = "Couldn't save that report. Try again."
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!saved) name?.let(health::discard)
                    capture?.delete()
                }
                reading = false
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { readAndSave(it) } }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val shot = photo ?: return@rememberLauncherForActivityResult
        // The plain capture is deleted right after it is encrypted, never on a timer.
        if (taken) readAndSave(Uri.fromFile(shot), shot) else shot.delete()
    }
    fun takePhoto() {
        val captures = HealthStore.captures(context)
        if (!captures.isDirectory && !captures.mkdirs()) {
            problem = "Couldn't open the camera: no space to keep the photo."
            return
        }
        val shot = File(captures, "${UUID.randomUUID()}.jpg")
        photo = shot
        shoot.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", shot))
    }
    LaunchedEffect(draft) {
        if (draft != null) {
            dateHint = draft.date
            onDraftUsed()
            pick.launch(arrayOf("image/*", HealthStore.PDF))
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(LucideR.drawable.lucide_ic_chevron_left, "Back", { if (open != null) open = null else onBack() })
            Column(Modifier.padding(start = 12.dp)) {
                SerifTitle("My NILA", size = 30.sp)
                Text("About you and your people · encrypted on this phone", color = Muted, fontSize = 12.sp)
            }
        }
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MyNilaTab.entries.forEach { t ->
                val on = t == tab
                Text(
                    t.label,
                    color = if (on) Ink else Paper,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Lime else Panel)
                        .clickable { tab = t; open = null }.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                tab == MyNilaTab.People -> PeopleTab(facts, onForgetFact, onForgetAll)
                tab == MyNilaTab.Reminders -> RemindersTab(reminders)
                open != null -> HealthDetail(
                    health = health,
                    record = open!!,
                    onDelete = { record, done ->
                        scope.launch {
                            val updated = withContext(Dispatchers.IO) { health.delete(record.id) }
                            if (updated != null) records = updated
                            done(updated != null)
                        }
                    },
                    onDeleted = { open = null },
                )
                else -> HealthTab(
                    records,
                    reading = reading,
                    problem = problem,
                    onUpload = { pick.launch(arrayOf("image/*", HealthStore.PDF)) },
                    onPhoto = ::takePhoto,
                    onOpen = { open = it },
                )
            }
        }
    }
}

// ---- People ------------------------------------------------------------------

@Composable
private fun PeopleTab(facts: List<MemoryFact>, onForgetFact: (String) -> Unit, onForgetAll: () -> Unit) {
    if (facts.isEmpty()) {
        NilaCard {
            Text("Nothing yet", color = Paper, fontWeight = FontWeight.Bold)
            Text("Just chat: \"my wife loves butter chicken\", \"Amma is allergic to peanuts\", \"I'm vegetarian on Tuesdays\".", color = Muted, fontSize = 13.sp)
        }
        return
    }
    factsByPerson(facts).forEach { (about, known) ->
        Surface(color = CardInk, shape = RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
                        Text(aboutLabel(about).take(1), color = Lime, fontWeight = FontWeight.Bold)
                    }
                    Text(aboutLabel(about), color = Paper, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(start = 12.dp))
                }
                known.forEach { fact ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fact.text, color = Paper, fontSize = 15.sp)
                            Text(fact.kind.label, color = Muted, fontSize = 12.sp)
                        }
                        TextAction("Forget", onClick = { onForgetFact(fact.id) }, color = Muted)
                    }
                }
            }
        }
    }
    var armed by remember { mutableStateOf(false) }
    TextAction(if (armed) "Tap again to forget everything about you and your people" else "Forget everything", onClick = {
        if (armed) onForgetAll() else armed = true
    }, color = Orange)
}

// ---- Reminders ---------------------------------------------------------------

@Composable
private fun RemindersTab(store: NilaReminders) {
    var reminders by remember { mutableStateOf(store.all()) }
    Text("My NILA notifies you, even when NILA is closed. Say \"remind me to pay the electricity bill every month on the 5th\", or upload a report that asks for a follow-up.", color = Muted, fontSize = 13.sp)
    if (reminders.isEmpty()) {
        NilaCard { Text("No reminders yet", color = Paper, fontWeight = FontWeight.Bold) }
        return
    }
    reminders.forEach { reminder ->
        Surface(color = CardInk, shape = RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(reminder.title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(reminderWhen(reminder), color = Lime, fontSize = 13.sp)
                }
                TextAction("Turn off", onClick = { reminders = store.remove(reminder.id) }, color = Muted)
            }
        }
    }
}

// ---- Health ------------------------------------------------------------------

@Composable
private fun HealthTab(
    records: List<HealthRecord>,
    reading: Boolean,
    problem: String?,
    onUpload: () -> Unit,
    onPhoto: () -> Unit,
    onOpen: (HealthRecord) -> Unit,
) {
    if (reading) {
        NilaCard {
            SectionLabel("Reading your report", color = Lime)
            Text("NILA is reading it on this phone. Nothing is sent online.", color = Paper, fontSize = 14.sp)
        }
    } else {
        PrimaryAction("Upload a report photo or PDF", onClick = onUpload)
        TextAction("Take a photo of the report", onClick = onPhoto)
    }
    problem?.let { Text(it, color = Orange, fontSize = 13.sp) }
    Text("NILA reads the report and saves what it says. It is not a doctor: check every value with yours.", color = Muted, fontSize = 12.sp)
    if (records.isEmpty() && !reading) {
        NilaCard {
            Text("No health records yet", color = Paper, fontWeight = FontWeight.Bold)
            Text("Upload last week's report and NILA will file it with its date, hospital and values.", color = Muted, fontSize = 13.sp)
        }
        return
    }
    records.forEach { record ->
        Surface(color = CardInk, shape = RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth().clickable { onOpen(record) }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    SectionLabel(record.kind.label + if (record.about != ME) " · ${aboutLabel(record.about)}" else "", color = Lime)
                    Text(record.title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(eventWhen(record.date, null).substringBefore(","), record.place).filter(String::isNotBlank).joinToString(" · "),
                        color = Muted,
                        fontSize = 13.sp,
                    )
                    record.followUp?.let { Text("Follow-up ${eventWhen(it, null).substringBefore(",")}", color = Lime, fontSize = 12.sp) }
                }
                Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun HealthDetail(
    health: HealthStore,
    record: HealthRecord,
    onDelete: (HealthRecord, (Boolean) -> Unit) -> Unit,
    onDeleted: () -> Unit,
) {
    var armed by remember(record.id) { mutableStateOf(false) }
    var deleting by remember(record.id) { mutableStateOf(false) }
    var problem by remember(record.id) { mutableStateOf<String?>(null) }
    SectionLabel(record.kind.label, color = Lime)
    SerifTitle(record.title, size = 26.sp)
    Text(listOf(eventWhen(record.date, null).substringBefore(","), record.place).filter(String::isNotBlank).joinToString(" · "), color = Muted, fontSize = 14.sp)
    record.followUp?.let { NilaCard { SectionLabel("Follow-up", color = Lime); Text(eventWhen(it, null).substringBefore(","), color = Paper, fontWeight = FontWeight.Bold) } }
    if (record.unverified) NilaCard {
        SectionLabel("Unverified", color = Orange)
        Text("Read by NILA's on-device model. Check each value against the report below.", color = Paper, fontSize = 14.sp)
    }
    if (record.notes.isNotBlank()) NilaCard { Text(record.notes, color = Paper, fontSize = 15.sp, lineHeight = 21.sp) }
    record.files.forEach { name -> Attachment(health, name) }
    problem?.let { Text(it, color = Orange, fontSize = 13.sp) }
    TextAction(if (deleting) "Deleting…" else if (armed) "Tap again to delete this record and its files" else "Delete record", onClick = {
        if (deleting) return@TextAction
        if (!armed) {
            armed = true
            return@TextAction
        }
        deleting = true
        onDelete(record) { success ->
            if (success) onDeleted() else {
                deleting = false
                problem = "Couldn't delete this record. Check phone storage and try again."
            }
        }
    }, color = Orange)
}

/** A stored photo, or a PDF's first page, decrypted in memory only. */
@Composable
private fun Attachment(health: HealthStore, name: String) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, name) {
        value = withContext(Dispatchers.IO) {
            val bytes = health.read(name) ?: return@withContext null
            if (HealthStore.isPdf(name)) pdfFirstPage(bytes, context.cacheDir, MAX_SIDE)?.bitmap else scaled(bytes)
        }
    }
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Report", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        } ?: Text(if (HealthStore.isPdf(name)) "PDF" else "Photo", color = Muted, modifier = Modifier.padding(24.dp))
    }
}

private const val MAX_SIDE = 1600
/** Gemma reads a report at this size: enough for printed values, small enough to read in seconds. */
private const val READ_SIDE = 1024
private const val JPEG_QUALITY = 90

/** A PDF's first page, and how many pages the PDF has, so NILA can say it read only the first. */
private class ReportPage(val bitmap: Bitmap, val pages: Int)

private fun jpegOf(bitmap: Bitmap): ByteArray =
    java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

/** Decodes a photo no bigger than [maxSide] on its long side, so a 50 MP report photo doesn't run out of memory. */
private fun scaled(bytes: ByteArray, maxSide: Int = MAX_SIDE): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** PdfRenderer needs a file: the PDF is written to cache only while its first page is drawn, then deleted. */
private fun pdfFirstPage(bytes: ByteArray, cache: File, maxSide: Int): ReportPage? {
    val temp = File(cache, "${UUID.randomUUID()}${HealthStore.TEMP_PDF}")
    return try {
        temp.writeBytes(bytes)
        ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                if (pdf.pageCount == 0) return null
                pdf.openPage(0).use { page ->
                    // Twice the page size for sharp text, but never over maxSide: a poster-size scan would run out of memory.
                    val scale = minOf(2f, maxSide.toFloat() / maxOf(page.width, page.height, 1))
                    val bitmap = Bitmap.createBitmap(
                        (page.width * scale).toInt().coerceAtLeast(1),
                        (page.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888,
                    )
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ReportPage(bitmap, pdf.pageCount)
                }
            }
        }
    } catch (e: Exception) {
        null
    } finally {
        temp.delete()
    }
}
