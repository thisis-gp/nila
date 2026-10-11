package com.nila.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
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

internal enum class MyNilaTab(val label: String) { People("People"), Health("Health"), Fitness("Fitness") }

/**
 * My NILA: its own space for everything NILA knows about the user and their people, and their health
 * tracker. Facts come from chat. A report is added by its photo alone: NILA reads it on the phone and saves
 * what it says. Everything stays encrypted on this phone.
 */
@Composable
internal fun MyNilaScreen(
    facts: List<MemoryFact>,
    onForgetFact: (String) -> Unit,
    onEditFact: (String, String) -> Unit,
    onAddFact: (text: String, about: String) -> Unit,
    onForgetAll: () -> Unit,
    health: HealthStore,
    readReport: suspend (ByteArray) -> ReportRead,
    onSetReminder: (HealthRecord) -> Unit,
    onDeleted: (HealthRecord) -> Unit,
    draft: HealthPrompt?,
    onDraftUsed: () -> Unit,
    onBack: () -> Unit,
    onSideDrag: (Float) -> Unit,
    onSideDragEnd: (Float) -> Unit,
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
    // Set while the user adds another page to this record instead of a new report.
    var pageFor by remember { mutableStateOf<HealthRecord?>(null) }
    BackHandler(enabled = open != null) { open = null }

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
                val report = (result as? ReportRead.Read)?.reading
                val target = pageFor
                val record = if (target != null) withPage(target, report, stored) else recordFrom(
                    report, stored, dateHint,
                    unavailable = result == ReportRead.Unavailable, pages = page?.pages ?: 1,
                )
                val updated = withContext(Dispatchers.IO) { health.save(record) } ?: run {
                    problem = "Couldn't save that report. Check phone storage and try again."
                    return@launch
                }
                records = updated
                saved = true
                if (target != null && report == null) problem = if (result == ReportRead.Unavailable) {
                    "NILA's on-device reader isn't available right now, so the page was added without reading it."
                } else "NILA couldn't read that page. It is added to the record."
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
                pageFor = null
                reading = false
            }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { readAndSave(it) } }
    val pickPage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) readAndSave(uri) else pageFor = null
    }
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

    Column(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = ScreenGutter, vertical = 12.dp)
            .sideSwipe(onDrag = onSideDrag, onEnd = onSideDragEnd),
    ) {
        Box(Modifier.padding(top = 8.dp).offset(x = (-4).dp)) {
            RoundButton(LucideR.drawable.lucide_ic_chevron_left, "Back", { if (open != null) open = null else onBack() })
        }
        SerifTitle("My NILA", size = 38.sp, modifier = Modifier.padding(top = 12.dp))
        Text("Stored only on this phone, encrypted", color = Muted, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        SegmentedTabs(tab, Modifier.padding(top = 20.dp, bottom = 16.dp)) { tab = it; open = null }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                tab == MyNilaTab.People -> PeopleTab(facts, onForgetFact, onEditFact, onAddFact, onForgetAll)
                tab == MyNilaTab.Fitness -> FitnessTab()
                reading -> ReadingCard()
                open != null -> HealthDetail(
                    health = health,
                    record = open!!,
                    notice = problem,
                    onSetReminder = onSetReminder,
                    onAddPage = { pageFor = it; pickPage.launch(arrayOf("image/*", HealthStore.PDF)) },
                    onDelete = { record, done ->
                        scope.launch {
                            val updated = withContext(Dispatchers.IO) { health.delete(record.id) }
                            if (updated != null) {
                                records = updated
                                onDeleted(record)
                            }
                            done(updated != null)
                        }
                    },
                    onDeleted = { open = null },
                )
                else -> HealthTab(
                    records,
                    reading = reading,
                    problem = problem,
                    onPhoto = { pick.launch(arrayOf("image/*")) },
                    onPdf = { pick.launch(arrayOf(HealthStore.PDF)) },
                    onCamera = ::takePhoto,
                    onOpen = { problem = null; open = it },
                )
            }
        }
    }
}

// ---- People ------------------------------------------------------------------

private val SelectedSegment = Lime.copy(alpha = 0.16f)
private val AvatarSize = 44.dp
private val AvatarGap = 14.dp
private val RowMinHeight = 52.dp
private val EmptyMascotSize = 108.dp

/** The People | Health records | Fitness switch: one rounded track, the chosen segment lifted and ticked. */
@Composable
private fun SegmentedTabs(selected: MyNilaTab, modifier: Modifier = Modifier, onSelect: (MyNilaTab) -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Panel).padding(4.dp)) {
        MyNilaTab.entries.forEach { t ->
            val on = t == selected
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) SelectedSegment else Color.Transparent)
                    .clickable { onSelect(t) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) Icon(painterResource(LucideR.drawable.lucide_ic_check), null, tint = Lime, modifier = Modifier.size(14.dp))
                Text(t.label, color = if (on) Lime else Muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = if (on) 5.dp else 0.dp))
            }
        }
    }
}

/** What the bottom sheet is open on: an existing fact, a new one for [about], or the user's name. */
private data class SheetTarget(val fact: MemoryFact?, val about: String, val isName: Boolean = false)

@Composable
private fun PeopleTab(facts: List<MemoryFact>, onForgetFact: (String) -> Unit, onEditFact: (String, String) -> Unit, onAddFact: (String, String) -> Unit, onForgetAll: () -> Unit) {
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    val name = userName(facts)
    val nameFact = facts.firstOrNull(::isNameFact)
    val shown = facts.filterNot(::isNameFact)
    val people = factsByPerson(shown).toMutableList().also { if (it.none { p -> p.first == ME }) it.add(0, ME to emptyList()) }
    if (shown.isEmpty() && name == null) {
        EmptyState(
            MascotMood.Listening,
            "Nothing yet. Tell me in chat, like \"my wife loves butter chicken\".",
            "Add something NILA should know",
            onAction = { sheet = SheetTarget(null, ME) },
        )
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextAction("+ Add", onClick = { sheet = SheetTarget(null, ME) })
        }
        people.forEach { (about, known) ->
            val isMe = about == ME
            val title = if (isMe) name ?: "You" else personTitle(about, known)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp).then(if (isMe) Modifier.clip(RoundedCornerShape(12.dp)).clickable { sheet = SheetTarget(nameFact, ME, isName = true) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(AvatarSize).background(if (isMe) Lime else CardInk, CircleShape), contentAlignment = Alignment.Center) {
                    Text(title.take(1).uppercase(), color = if (isMe) Ink else Paper, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
                Column(Modifier.weight(1f).padding(start = AvatarGap)) {
                    Text(title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (isMe) "You · " else "") + personSubtitle(known), color = Muted, fontSize = 13.sp, maxLines = 1)
                }
            }
            known.forEach { fact -> FactRow(fact) { sheet = SheetTarget(fact, about) } }
        }
        ForgetEverything(factCount = shown.size, peopleCount = people.count { it.second.isNotEmpty() }, onForgetAll = onForgetAll)
    }
    sheet?.let { target ->
        FactSheet(
            target,
            onDismiss = { sheet = null },
            people = people.map { it.first },
            onSave = { text, about ->
                when {
                    target.isName -> if (target.fact != null) onEditFact(target.fact.id, NAME_PREFIX + text) else onAddFact(NAME_PREFIX + text, ME)
                    target.fact != null -> onEditFact(target.fact.id, text)
                    else -> onAddFact(text, about)
                }
                sheet = null
            },
            onForget = { id -> onForgetFact(id); sheet = null },
        )
    }
}

@Composable
private fun FactRow(fact: MemoryFact, onClick: () -> Unit) {
    val tag = factTag(fact.kind)
    Column(
        Modifier.fillMaxWidth().heightIn(min = RowMinHeight).clickable(onClick = onClick).padding(start = AvatarSize + AvatarGap, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(factLine(fact.text), color = Paper, fontSize = 16.sp, lineHeight = 21.sp)
        if (tag != null) {
            val color = if (fact.kind == MemoryKind.Allergy) Orange else Muted
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = if (fact.kind == MemoryKind.Allergy) LucideR.drawable.lucide_ic_triangle_alert else LucideR.drawable.lucide_ic_leaf
                Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
                Text(tag, color = color, fontSize = 12.sp, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

/** A mascot, one short line and at most one action: what a tab shows before it has anything. */
@Composable
internal fun EmptyState(mood: MascotMood, line: String, action: String? = null, onAction: () -> Unit = {}, note: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        NilaMascot(mood = mood, size = EmptyMascotSize)
        Text(line, color = Paper, fontSize = 16.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
        action?.let { PrimaryAction(it, onAction) }
        note?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, textAlign = TextAlign.Center) }
    }
}

/** A quiet link at the bottom; it asks before anything is cleared. */
@Composable
private fun ForgetEverything(factCount: Int, peopleCount: Int, onForgetAll: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextAction("Forget everything", onClick = { asking = !asking }, color = Muted)
        if (asking) {
            NilaCard(color = CommitInk) {
                Text("Forget $factCount ${if (factCount == 1) "fact" else "facts"} about $peopleCount ${if (peopleCount == 1) "person" else "people"}?", color = Paper, fontWeight = FontWeight.Bold)
                Text("NILA won't remember any of it. This can't be undone.", color = Muted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("Keep it", onClick = { asking = false }, color = Paper)
                    TextAction("Forget everything", onClick = { asking = false; onForgetAll() }, color = Orange)
                }
            }
        }
    }
    var armed by remember { mutableStateOf(false) }
    TextAction(if (armed) "Tap again to forget everything about you and your people" else "Forget everything", onClick = {
        if (armed) onForgetAll() else armed = true
    }, color = Orange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FactSheet(target: SheetTarget, people: List<String>, onDismiss: () -> Unit, onSave: (text: String, about: String) -> Unit, onForget: (String) -> Unit) {
    val start = if (target.isName) target.fact?.let { userName(listOf(it)) }.orEmpty() else target.fact?.text.orEmpty()
    var text by remember(target) { mutableStateOf(start) }
    var person by remember(target) { mutableStateOf(aboutLabel(target.about)) }
    val choosing = target.fact == null && !target.isName
    val title = when {
        target.isName -> "Your name"
        target.fact != null -> "What NILA knows"
        else -> "Add something NILA should know"
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = CardInk, contentColor = Paper) {
        Column(Modifier.fillMaxWidth().padding(horizontal = ScreenGutter).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SerifTitle(title, size = 24.sp)
            target.fact?.takeIf { !target.isName }?.let { Text(it.text, color = Muted, fontSize = 14.sp, lineHeight = 20.sp) }
            if (choosing) {
                OutlinedTextField(
                    value = person,
                    onValueChange = { person = it },
                    label = { Text("Who is it about", color = Muted) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Paper, unfocusedTextColor = Paper, focusedBorderColor = Lime, unfocusedBorderColor = Stroke, cursorColor = Lime),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    people.forEach { who ->
                        Text(
                            aboutLabel(who), color = if (aboutKey(person) == who) Ink else Paper, fontSize = 13.sp,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (aboutKey(person) == who) Lime else Panel).clickable { person = aboutLabel(who) }.padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (target.isName) "Your name" else "For example: Amma likes filter coffee", color = Muted) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Paper, unfocusedTextColor = Paper, focusedBorderColor = Lime, unfocusedBorderColor = Stroke, cursorColor = Lime),
            )
            PrimaryAction("Save", onClick = { onSave(text.trim(), if (choosing) aboutKey(person) else target.about) }, enabled = text.isNotBlank() && (!choosing || person.isNotBlank()))
            target.fact?.takeIf { !target.isName }?.let {
                TextAction("Forget this", onClick = { onForget(it.id) }, color = Orange, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

// ---- Health ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthTab(
    records: List<HealthRecord>,
    reading: Boolean,
    problem: String?,
    onPhoto: () -> Unit,
    onPdf: () -> Unit,
    onCamera: () -> Unit,
    onOpen: (HealthRecord) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    BackHandler(enabled = adding) { adding = false }
    if (adding) {
        ModalBottomSheet(onDismissRequest = { adding = false }, containerColor = CardInk, contentColor = Paper) {
            Column(Modifier.fillMaxWidth().padding(horizontal = ScreenGutter).padding(bottom = 24.dp)) {
                SerifTitle("Add a report", size = 24.sp, modifier = Modifier.padding(bottom = 8.dp))
                SheetChoice(LucideR.drawable.lucide_ic_image, "Photo") { adding = false; onPhoto() }
                SheetChoice(LucideR.drawable.lucide_ic_file_text, "PDF") { adding = false; onPdf() }
                SheetChoice(LucideR.drawable.lucide_ic_camera, "Camera") { adding = false; onCamera() }
            }
        }
    }
    if (records.isEmpty()) {
        EmptyState(MascotMood.Sleeping, "No health records yet. Add a report and NILA files it with its date and values.", "Add a report", onAction = { adding = true }, note = HEALTH_DISCLAIMER)
        problem?.let { Text(it, color = Orange, fontSize = 13.sp) }
        return
    }
    PrimaryAction("Add a report", onClick = { adding = true })
    problem?.let { Text(it, color = Orange, fontSize = 13.sp) }
    records.forEach { record ->
        val flags = flagsSummary(record.findings)
        Surface(color = CardInk, shape = RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth().clickable { onOpen(record) }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (record.about != ME) CardLabel(aboutLabel(record.about))
                    Text(record.title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(dayAndPlace(record), color = Muted, fontSize = 13.sp)
                    if (flags.isNotBlank()) Text(flags, color = if ("high" in flags) Orange else Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    record.followUp?.let { Text("Follow-up ${shortDay(it)}", color = Lime, fontSize = 12.sp) }
                }
                Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
    Text(HEALTH_DISCLAIMER, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
}

@Composable
private fun ReadingCard() = NilaCard {
    CardLabel("Reading your report", color = Lime)
    Text("NILA is reading it on this phone. Nothing is sent online.", color = Paper, fontSize = 14.sp)
}

/** "Sun 4 Oct". */
private fun shortDay(date: LocalDate) = eventWhen(date, null).substringBefore(",")

/** "Sun 4 Oct · City Diagnostics Lab". */
private fun dayAndPlace(record: HealthRecord) = listOf(shortDay(record.date), record.place).filter(String::isNotBlank).joinToString(" · ")

private const val HEALTH_DISCLAIMER = "NILA reads the report and saves what it says. It is not a doctor: check every value with yours."

@Composable
private fun SheetChoice(icon: Int, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = RowMinHeight).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = Muted, modifier = Modifier.size(20.dp))
        Text(label, color = Paper, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp))
    }
}

/** A small sentence-case heading for a card or section. */
@Composable
internal fun CardLabel(text: String, modifier: Modifier = Modifier, color: Color = Muted) =
    Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)

@Composable
private fun HealthDetail(
    health: HealthStore,
    record: HealthRecord,
    notice: String?,
    onSetReminder: (HealthRecord) -> Unit,
    onAddPage: (HealthRecord) -> Unit,
    onDelete: (HealthRecord, (Boolean) -> Unit) -> Unit,
    onDeleted: () -> Unit,
) {
    var armed by remember(record.id) { mutableStateOf(false) }
    var deleting by remember(record.id) { mutableStateOf(false) }
    var problem by remember(record.id) { mutableStateOf<String?>(null) }
    var reminded by remember(record.id) { mutableStateOf(false) }
    SerifTitle(record.title, size = 26.sp)
    Text(dayAndPlace(record), color = Muted, fontSize = 14.sp)
    notice?.let { Text(it, color = Orange, fontSize = 13.sp) }
    record.followUp?.let { day ->
        ResultCard(
            LucideR.drawable.lucide_ic_calendar,
            "Follow-up",
            actions = { if (reminded) Text("Reminder set for 9 am", color = Muted, fontSize = 13.sp) else TextAction("Set a reminder", onClick = { onSetReminder(record); reminded = true }) },
        ) { Text(shortDay(day), color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
    }
    if (record.unverified) NilaCard {
        SectionLabel("Unverified", color = Orange)
        Text("Read by NILA's on-device model. Check each value against the report below.", color = Paper, fontSize = 14.sp)
    }
    if (record.findings.isNotEmpty()) NilaCard {
        record.findings.forEach { FindingRow(it) }
    }
    if (record.notes.isNotBlank()) NilaCard { Text(record.notes, color = Paper, fontSize = 15.sp, lineHeight = 21.sp) }
    record.files.forEach { name -> Attachment(health, name) }
    TextAction("Add a page", onClick = { onAddPage(record) })
    problem?.let { Text(it, color = Orange, fontSize = 13.sp) }
    TextAction(if (deleting) "Deleting…" else if (armed) "Tap again to delete this record and its files" else "Delete", onClick = {
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
    }, color = if (armed || deleting) Orange else Muted)
    Text(HEALTH_DISCLAIMER, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 16.dp))
}
/** One value: its name on the left, value and unit on the right; low and high get a colour and the word. */
@Composable
private fun FindingRow(finding: ReportFinding) {
    val flagged = finding.flag == "high" || finding.flag == "low"
    val color = when (finding.flag) {
        "high" -> Orange
        "low" -> Lime
        else -> Paper
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(finding.name, color = Paper, fontSize = 15.sp)
            if (finding.range.isNotBlank()) Text("Normal ${finding.range}", color = Muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
            Text(listOf(finding.value, finding.unit).filter(String::isNotBlank).joinToString(" "), color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (flagged) Text(finding.flag.replaceFirstChar { it.uppercase() }, color = color, fontSize = 12.sp)
        }
    }
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

/** What the panel shows while My NILA is locked: no facts, no health data, just a way to unlock. */
@Composable
internal fun LockedMyNila(onUnlock: () -> Unit, onBack: () -> Unit, onSideDrag: (Float) -> Unit, onSideDragEnd: (Float) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = ScreenGutter, vertical = 12.dp)
            .sideSwipe(onDrag = onSideDrag, onEnd = onSideDragEnd),
    ) {
        Box(Modifier.padding(top = 8.dp).offset(x = (-4).dp)) { RoundButton(LucideR.drawable.lucide_ic_chevron_left, "Back", onBack) }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            NilaMascot(mood = MascotMood.Sleeping, size = EmptyMascotSize)
            SerifTitle("My NILA is locked", size = 28.sp, modifier = Modifier.padding(top = 20.dp, bottom = 24.dp))
            PrimaryAction("Unlock", onUnlock)
        }
    }
}
