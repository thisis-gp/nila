package com.nila.launcher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Finding files on the phone by what they are: "find my admit card". The search runs on the phone, over
// Android's index of files (MediaStore); nothing leaves the phone.

/** "find my admit card": what the user called it, and the file-name words that can mean it. */
data class FileSearch(val asked: String, val keywords: List<String>) : NilaResponse

/** Common documents and the names their files usually carry: an admit card is often a "hall ticket". */
private val DocumentNames: List<Pair<Regex, List<String>>> = listOf(
    Regex("""\b(admit\s*card|hall\s*ticket|hallticket)\b""") to listOf("admit", "hall ticket", "hallticket", "hall_ticket", "admission"),
    Regex("""\b(aadhaa?r)\b""") to listOf("aadhaar", "aadhar", "uidai", "e-aadhaar"),
    Regex("""\bpan\s*card\b""") to listOf("pan", "pancard", "pan card"),
    Regex("""\b(resume|cv|biodata)\b""") to listOf("resume", "cv", "biodata", "curriculum"),
    Regex("""\b(mark\s*sheet|marksheet|marks\s*card|grade\s*card|transcript|result)\b""") to listOf("marksheet", "mark sheet", "marks", "grade", "transcript", "result"),
    Regex("""\b(train|flight|bus|movie)?\s*tickets?\b|\bboarding\s*pass\b""") to listOf("ticket", "boarding", "irctc", "e-ticket", "eticket", "pnr"),
    Regex("""\b(invoice|bill|receipt)s?\b""") to listOf("invoice", "bill", "receipt"),
    Regex("""\bcertificates?\b""") to listOf("certificate", "cert"),
    Regex("""\bpassport\b""") to listOf("passport"),
    Regex("""\b(driving\s*)?(licen[cs]e|dl)\b""") to listOf("licence", "license", "dl"),
    Regex("""\b(offer\s*letter|appointment\s*letter|joining\s*letter)\b""") to listOf("offer", "appointment", "joining"),
    Regex("""\b(insurance|policy)\b""") to listOf("insurance", "policy"),
)

private val FindVerb = Regex("""^\s*(please\s+)?(can you\s+)?(find|search|look for|where('?s| is| are))\b""")
private val FileWords = Regex("""\b(files?|documents?|docs?|pdfs?)\b""")
private val MyCue = Regex("""\bmy\b""")
private val Filler = setOf(
    "find", "search", "look", "for", "where", "is", "are", "where's", "my", "me", "the", "a", "an",
    "file", "files", "document", "documents", "doc", "docs", "pdf", "pdfs", "in", "on", "phone", "please", "can", "you", "of", "called", "named",
)

/**
 * "find my admit card", "where is my resume", "show my aadhaar", "find the file called budget": what to look
 * for, with the names its file may carry. Null when it isn't a request for a file (web searches, photos, food).
 */
fun fileSearch(text: String): FileSearch? {
    val lower = text.lowercase().trim().trimEnd('?', '.', '!')
    if (!FindVerb.containsMatchIn(lower) || Regex("""\b(the web|online|google)\b|\bphotos?\b|\bpictures?\b""").containsMatchIn(lower)) return null
    if (!MyCue.containsMatchIn(lower) && !FileWords.containsMatchIn(lower)) return null
    DocumentNames.firstOrNull { (words) -> words.containsMatchIn(lower) }?.let { (words, names) ->
        return FileSearch(words.find(lower)!!.value.trim(), names)
    }
    if (!FileWords.containsMatchIn(lower)) return null
    val words = lower.split(Regex("""[^\p{L}\p{N}_-]+""")).filter { it.length >= 2 && it !in Filler }
    if (words.isEmpty()) return null
    return FileSearch(words.joinToString(" "), listOf(words.joinToString(" ")) + words.filter { it.length >= 3 })
}

/** One file found on the phone. */
data class PhoneFile(val uri: Uri, val name: String, val folder: String, val modified: Long, val size: Long, val mime: String)

private const val MAX_FILES = 25

internal fun fileNameMatches(name: String, keywords: List<String>): Boolean = keywords
    .filter { it.length >= 3 }
    .any { Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])").containsMatchIn(name) }

internal fun fileLikePattern(keyword: String): String = "%${keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"

/** Files whose name holds any of the [FileSearch.keywords], newest first. */
internal fun findFiles(context: Context, search: FileSearch): List<PhoneFile> {
    val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.VOLUME_EXTERNAL else "external"
    val collection = MediaStore.Files.getContentUri(volume)
    val name = MediaStore.MediaColumns.DISPLAY_NAME
    val keywords = search.keywords.distinct()
    val searchable = keywords.filter { it.length >= 3 }
    if (searchable.isEmpty()) return emptyList()
    val selection = searchable.joinToString(" OR ") { "$name LIKE ? ESCAPE '\\'" }
    val args = searchable.map(::fileLikePattern).toTypedArray()
    val folderColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
    val projection = arrayOf(
        MediaStore.MediaColumns._ID, name, folderColumn,
        MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE,
    )
    val found = mutableListOf<PhoneFile>()
    context.contentResolver.query(collection, projection, "($selection) AND ${MediaStore.MediaColumns.SIZE} > 0", args, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
        while (c.moveToNext() && found.size < MAX_FILES) {
            val fileName = c.getString(1).orEmpty()
            if (!fileNameMatches(fileName, searchable)) continue
            found += PhoneFile(
                uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                name = fileName,
                folder = c.getString(2).orEmpty().trimEnd('/'),
                modified = c.getLong(3) * 1000,
                size = c.getLong(4),
                mime = c.getString(5).orEmpty(),
            )
        }
    }
    return found
}

/** "2.4 MB", "830 KB". */
internal fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "${bytes / 1_000} KB"
    else -> "$bytes B"
}

/** The files found, newest first: tap one to open it in the app that reads it. */
@Composable
internal fun FileSearchCard(search: FileSearch) {
    val context = LocalContext.current
    // Re-checked each time the card is shown again, after the user comes back from Android's settings.
    var tries by remember { mutableIntStateOf(0) }
    val files by produceState<List<PhoneFile>?>(null, search, tries) {
        value = withContext(Dispatchers.IO) { runCatching { findFiles(context, search) }.getOrDefault(emptyList()) }
    }
    NilaCard {
        SectionLabel("Files · ${search.asked}", color = Lime)
        Text("Searching files indexed by Android on this phone.", color = Muted, fontSize = 12.sp)
        when {
            files == null -> Text("Looking…", color = Muted, fontSize = 14.sp)
            files!!.isEmpty() -> Text(
                "No indexed file named like \"${search.asked}\" on this phone. Try the name printed on it.",
                color = Paper,
                fontSize = 14.sp,
            )
            else -> {
                Text("${files!!.size} found. Tap one to open it.", color = Muted, fontSize = 13.sp)
                files!!.forEachIndexed { i, f ->
                    if (i > 0) HorizontalDivider(color = Panel)
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(f.uri, f.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show() }
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).background(Panel, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Text(
                                when {
                                    f.mime == HealthStore.PDF -> "PDF"
                                    f.mime.startsWith("image/") -> "IMG"
                                    else -> f.name.substringAfterLast('.', "FILE").take(4).uppercase()
                                },
                                color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(f.name, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(
                                    f.folder.ifBlank { "Phone" },
                                    eventWhen(Instant.ofEpochMilli(f.modified).atZone(ZoneId.systemDefault()).toLocalDate(), null).substringBefore(","),
                                    sizeLabel(f.size),
                                ).joinToString(" · "),
                                color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
