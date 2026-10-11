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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as LucideR

/** A health entry offered from the chat ("last week I went to the hospital"), opened as a prefilled form. */
data class HealthPrompt(val kind: HealthKind, val title: String, val date: LocalDate) : NilaResponse

/** A health entry the chat asked to add; MainActivity opens My NILA on it. Compose state, like NilaTimer. */
internal object MyNilaRequests {
    var pending by mutableStateOf<HealthPrompt?>(null)
}

internal enum class MyNilaTab(val label: String) { People("People"), Health("Health") }

/**
 * My NILA: its own space for everything NILA knows about the user and their people, and their health
 * tracker. Facts come from chat; health entries are added here, with the hospital's report as a photo or
 * PDF. Everything stays encrypted on this phone.
 */
@Composable
internal fun MyNilaScreen(
    facts: List<MemoryFact>,
    onForgetFact: (String) -> Unit,
    onForgetAll: () -> Unit,
    health: HealthStore,
    draft: HealthPrompt?,
    onDraftUsed: () -> Unit,
    onBack: () -> Unit,
) {
    var tab by remember { mutableStateOf(if (draft != null) MyNilaTab.Health else MyNilaTab.People) }
    val scope = rememberCoroutineScope()
    // The first read builds the Keystore key and decrypts: never on the main thread.
    var records by remember { mutableStateOf<List<HealthRecord>>(emptyList()) }
    LaunchedEffect(Unit) { records = withContext(Dispatchers.IO) { health.records() } }
    var editing by remember { mutableStateOf(draft) }
    var open by remember { mutableStateOf<HealthRecord?>(null) }
    LaunchedEffect(draft) { if (draft != null) onDraftUsed() }

    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(LucideR.drawable.lucide_ic_chevron_left, "Back", {
                when {
                    editing != null -> editing = null
                    open != null -> open = null
                    else -> onBack()
                }
            })
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
                        .clickable { tab = t; editing = null; open = null }.padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                tab == MyNilaTab.People -> PeopleTab(facts, onForgetFact, onForgetAll)
                editing != null -> HealthForm(
                    health = health,
                    draft = editing!!,
                    onSave = { record, done ->
                        scope.launch {
                            val updated = withContext(Dispatchers.IO) { health.save(record) }
                            if (updated != null) records = updated
                            done(updated != null)
                        }
                    },
                    onDone = { editing = null },
                )
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
                else -> HealthTab(records, onAdd = { editing = HealthPrompt(HealthKind.Visit, "", LocalDate.now()) }, onOpen = { open = it })
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

// ---- Health ------------------------------------------------------------------

@Composable
private fun HealthTab(records: List<HealthRecord>, onAdd: () -> Unit, onOpen: (HealthRecord) -> Unit) {
    PrimaryAction("Add a visit, report or medicine", onClick = onAdd)
    Text("NILA keeps your records in one place. It is not a doctor: ask yours about any result.", color = Muted, fontSize = 12.sp)
    if (records.isEmpty()) {
        NilaCard {
            Text("No health records yet", color = Paper, fontWeight = FontWeight.Bold)
            Text("Add last week's hospital visit with a photo or PDF of the report.", color = Muted, fontSize = 13.sp)
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
                }
                if (record.files.isNotEmpty()) Text("${record.files.size} file${if (record.files.size == 1) "" else "s"}", color = Muted, fontSize = 12.sp)
                Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthForm(health: HealthStore, draft: HealthPrompt, onSave: (HealthRecord, (Boolean) -> Unit) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(draft.kind) }
    var title by remember { mutableStateOf(draft.title) }
    var date by remember { mutableStateOf(draft.date) }
    var place by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val files = remember { mutableStateListOf<String>() }
    var problem by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<File?>(null) }
    var keepFiles by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Back, a tab change or a rotation ends the draft: its encrypted files go with it unless it was saved.
    DisposableEffect(Unit) { onDispose { if (!keepFiles) files.toList().forEach(health::discard) } }

    // Reading and encrypting a whole PDF or photo runs off the main thread.
    fun attach(uri: Uri, failed: String, after: () -> Unit = {}) = scope.launch {
        val name = withContext(Dispatchers.IO) { health.attach(uri).also { after() } }
        if (name != null) files.add(name) else problem = failed
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) attach(uri, "Couldn't read that file.")
    }
    val shoot = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val shot = photo ?: return@rememberLauncherForActivityResult
        // The unencrypted capture never stays on the phone; one that can't be deleted now is swept on the next start.
        val removeShot = { if (!shot.delete() && shot.exists()) Log.w("MyNila", "capture not deleted; swept on next start") }
        if (taken) attach(Uri.fromFile(shot), "Couldn't keep that photo.", removeShot) else removeShot()
    }

    SectionLabel("What is it")
    // Five kinds don't fit one phone row: the row scrolls instead of cutting "Note" off (seen on the iQOO 15).
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        HealthKind.entries.forEach { k -> Chip(k.label.substringBefore(" "), k == kind) { kind = k } }
    }
    OutlinedTextField(
        value = title, onValueChange = { title = it },
        label = { Text(if (kind == HealthKind.Medicine) "Medicine and dose" else "Title, like \"Fever check-up\"") },
        singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
    )
    SectionLabel("When")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val today = LocalDate.now()
        Chip("Today", date == today) { date = today }
        Chip("Yesterday", date == today.minusDays(1)) { date = today.minusDays(1) }
        Chip("Last week", date == today.minusWeeks(1)) { date = today.minusWeeks(1) }
        Chip(eventWhen(date, null).substringBefore(","), false) { picking = true }
    }
    OutlinedTextField(
        value = place, onValueChange = { place = it }, label = { Text("Hospital or doctor") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
    )
    OutlinedTextField(
        value = notes, onValueChange = { notes = it }, label = { Text("Notes: what they said, what to do next") },
        minLines = 3, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
    )
    SectionLabel("Report")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TextAction("Add photo or PDF", onClick = { pick.launch(arrayOf("image/*", HealthStore.PDF)) })
        TextAction("Take a photo", onClick = {
            val captures = HealthStore.captures(context)
            if (!captures.isDirectory && !captures.mkdirs()) {
                problem = "Couldn't open the camera: no space to keep the photo."
            } else {
                val shot = File(captures, "${UUID.randomUUID()}.jpg")
                photo = shot
                shoot.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", shot))
            }
        })
    }
    if (files.isNotEmpty()) Text("${files.size} attached, encrypted on this phone", color = Lime, fontSize = 13.sp)
    problem?.let { Text(it, color = Orange, fontSize = 13.sp) }
    PrimaryAction("Save to My NILA", onClick = {
        if (saving) return@PrimaryAction
        saving = true
        // Keep attachments while the disk write is in flight; start-up sweep removes them if the app dies first.
        keepFiles = true
        val record = HealthRecord(UUID.randomUUID().toString(), kind, title.trim(), date, place.trim(), notes.trim(), files.toList())
        onSave(record) { success ->
            if (success) {
                onDone()
            } else {
                saving = false
                keepFiles = false
                problem = "Couldn't save this record. Check phone storage and try again."
            }
        }
    }, enabled = title.isNotBlank() && !saving)
    TextAction("Cancel", onClick = onDone, color = Muted)

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Ink else Paper,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Lime else Panel).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
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
            if (HealthStore.isPdf(name)) pdfFirstPage(bytes, context.cacheDir) else scaled(bytes)
        }
    }
    Surface(color = Panel, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = "Report", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
        } ?: Text(if (HealthStore.isPdf(name)) "PDF" else "Photo", color = Muted, modifier = Modifier.padding(24.dp))
    }
}

private const val MAX_SIDE = 1600

/** Decodes a photo no bigger than [MAX_SIDE] on its long side, so a 50 MP report photo doesn't run out of memory. */
private fun scaled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** PdfRenderer needs a file: the PDF is written to cache only while its first page is drawn, then deleted. */
private fun pdfFirstPage(bytes: ByteArray, cache: File): Bitmap? {
    val temp = File(cache, "${UUID.randomUUID()}${HealthStore.TEMP_PDF}")
    return try {
        temp.writeBytes(bytes)
        ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                if (pdf.pageCount == 0) return null
                pdf.openPage(0).use { page ->
                    // Twice the page size for sharp text, but never over MAX_SIDE: a poster-size scan would run out of memory.
                    val scale = minOf(2f, MAX_SIDE.toFloat() / maxOf(page.width, page.height, 1))
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(android.graphics.Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    } catch (e: Exception) {
        null
    } finally {
        temp.delete()
    }
}
