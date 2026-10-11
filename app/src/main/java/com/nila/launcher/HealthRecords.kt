package com.nila.launcher

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** What a health entry is. */
enum class HealthKind(val label: String) {
    Visit("Hospital visit"),
    Report("Report"),
    Medicine("Medicine"),
    Reading("Reading"),
    Note("Note"),
}

/**
 * One entry in My NILA's health tracker: a visit, a report, a medicine, a reading such as blood pressure,
 * or a note. [files] are the names of its encrypted attachments (photos or PDFs). [about] is who it is for.
 */
data class HealthRecord(
    val id: String,
    val kind: HealthKind,
    val title: String,
    val date: LocalDate,
    val place: String = "",
    val notes: String = "",
    val files: List<String> = emptyList(),
    val about: String = ME,
)

/** Newest first, so last week's visit is at the top. */
internal fun newestFirst(records: List<HealthRecord>) = records.sortedWith(compareByDescending<HealthRecord> { it.date }.thenByDescending { it.id })

private val HealthCue = Regex(
    """\b(went to|visited|been to|go to)\s+(the\s+|a\s+)?(hospital|doctor|clinic|dentist|lab)\b|""" +
        """\b(blood|sugar|thyroid|lab)\s+(test|report)\b|\b(medical|health|lab|scan|x-?ray|mri|ct)\s+(report|record|scan|result)s?\b|""" +
        """\b(health tracker|my health)\b""",
)

/** "last week I went to the hospital and this is the report": a health entry for My NILA. */
internal fun isHealthMention(text: String): Boolean = HealthCue.containsMatchIn(text.lowercase()) && !OtherRequest.containsMatchIn(text.lowercase())

// A health word inside a request for something else: "remind me to go to the doctor tomorrow" is a reminder.
// Open, show and add stay health requests: "open my health tracker", "add my blood test report".
private val OtherRequest = Regex("""\b(remind|set|call|message|text|send|book|order|buy|schedule|cancel|navigate|take me|turn|switch|start|stop|play|pay|search)\b""")

/** "Hospital visit" for a visit, "Report" when the user talks about a report or a test. */
internal fun healthKindFor(text: String): HealthKind {
    val lower = text.lowercase()
    return when {
        Regex("""\b(report|result|scan|x-?ray|mri|test)\b""").containsMatchIn(lower) &&
            !Regex("""\b(went to|visited|been to)\b""").containsMatchIn(lower) -> HealthKind.Report
        else -> HealthKind.Visit
    }
}

private val weekdayNames = DayOfWeek.entries.associateBy { it.name.lowercase() }

/** The day the user meant: "today", "yesterday", "last week" (7 days ago), "N days ago", or the last such weekday. */
internal fun healthDate(text: String, today: LocalDate): LocalDate {
    val lower = text.lowercase()
    Regex("""\b(\d{1,2})\s+days?\s+ago\b""").find(lower)?.let { return today.minusDays(it.groupValues[1].toLong()) }
    return when {
        Regex("""\byesterday\b""").containsMatchIn(lower) -> today.minusDays(1)
        Regex("""\blast week\b""").containsMatchIn(lower) -> today.minusWeeks(1)
        Regex("""\blast month\b""").containsMatchIn(lower) -> today.minusMonths(1)
        else -> weekdayNames.entries.firstOrNull { (name) -> Regex("""\b(last\s+|on\s+)?$name\b""").containsMatchIn(lower) }
            ?.let { (_, day) -> today.with(TemporalAdjusters.previous(day)) } ?: today
    }
}

/**
 * Health records in encrypted preferences, and their attachments as encrypted files in the app's own
 * storage: nothing leaves the phone, and another app cannot read them.
 */
internal class HealthStore(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey by lazy { MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
    private val dir get() = File(appContext.filesDir, DIR).apply { mkdirs() }

    fun records(): List<HealthRecord> = newestFirst(prefs.getString(RECORDS, null)?.let(::decode).orEmpty())

    fun save(record: HealthRecord): List<HealthRecord> =
        (records().filterNot { it.id == record.id } + record).also(::write).let(::newestFirst)

    /** Removes the record, then its attachments: files go only once the record no longer points to them. */
    fun delete(id: String): List<HealthRecord> {
        val all = records()
        val left = all.filterNot { it.id == id }
        if (write(left)) all.firstOrNull { it.id == id }?.files?.forEach { File(dir, it).delete() }
        return left
    }

    /**
     * Run once at start, before any draft exists: removes camera captures and temporary PDFs left in cache
     * (plain copies, if the app was killed mid-way), and encrypted files no saved record points to.
     */
    fun sweep() {
        captures(appContext).listFiles()?.forEach { it.delete() }
        appContext.cacheDir.listFiles { file -> file.name.endsWith(TEMP_PDF) }?.forEach { it.delete() }
        val kept = records().flatMap { it.files }.toSet()
        dir.listFiles()?.filter { it.name !in kept }?.forEach { it.delete() }
    }

    /** Copies a picked photo or PDF into encrypted storage; returns its stored name, or null when it can't be read. */
    fun attach(uri: Uri): String? = runCatching {
        val type = appContext.contentResolver.getType(uri).orEmpty()
        val name = UUID.randomUUID().toString() + if (type == PDF) ".pdf" else ".img"
        val bytes = appContext.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        write(name, bytes)
        name
    }.getOrNull()

    /** Deletes an attachment that was never saved with a record (the form was cancelled). */
    fun discard(name: String) {
        File(dir, name).delete()
    }

    /** The attachment's bytes, decrypted in memory only. */
    fun read(name: String): ByteArray? = runCatching {
        encrypted(File(dir, name)).openFileInput().use { it.readBytes() }
    }.getOrNull()

    private fun write(name: String, bytes: ByteArray) {
        val file = File(dir, name).also { it.delete() }
        encrypted(file).openFileOutput().use { it.write(bytes) }
    }

    private fun encrypted(file: File) =
        EncryptedFile.Builder(appContext, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()

    private fun write(records: List<HealthRecord>): Boolean =
        prefs.edit().putString(RECORDS, JSONArray().apply {
            records.forEach { r ->
                put(
                    JSONObject().put("id", r.id).put("kind", r.kind.name).put("title", r.title).put("date", r.date.toString())
                        .put("place", r.place).put("notes", r.notes).put("files", JSONArray(r.files)).put("about", r.about),
                )
            }
        }.toString()).commit()

    /** A malformed entry is skipped, never fatal. */
    private fun decode(raw: String): List<HealthRecord> {
        val list = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                HealthRecord(
                    id = o.getString("id"),
                    kind = HealthKind.entries.firstOrNull { it.name == o.optString("kind") } ?: HealthKind.Note,
                    title = o.optString("title"),
                    date = LocalDate.parse(o.getString("date")),
                    place = o.optString("place"),
                    notes = o.optString("notes"),
                    files = o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    about = aboutKey(o.optString("about")),
                )
            }.getOrNull()
        }
    }

    companion object {
        private const val PREFS = "nila_health"
        private const val RECORDS = "records"
        private const val DIR = "health"
        const val PDF = "application/pdf"
        /** The end of every temporary PDF name in cache, so the start-up sweep finds them. */
        const val TEMP_PDF = ".nila.pdf"

        /** Where camera photos land before they are encrypted. */
        fun captures(context: Context) = File(context.cacheDir, "captures")

        fun isPdf(name: String) = name.endsWith(".pdf")
    }
}
