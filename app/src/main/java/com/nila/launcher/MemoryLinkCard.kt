package com.nila.launcher

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AvatarSize = 28.dp
private val ReplySize = 19.sp

private val SheWords = setOf("wife", "sister", "mom", "mother", "amma", "daughter", "grandma", "aunt", "girlfriend", "akka", "didi", "paati")
private val HeWords = setOf("husband", "brother", "dad", "father", "appa", "son", "grandpa", "uncle", "boyfriend", "anna", "bhaiya", "thatha")
private val Verbs = setOf("loves", "likes", "enjoys", "prefers", "eats", "hates", "drinks", "avoids", "wants")

/** "you", "she", "he", or "they" for a person NILA has no pronoun for. */
internal fun pronounFor(about: String): String = when {
    about == ME -> "you"
    about.lowercase() in SheWords -> "she"
    about.lowercase() in HeWords -> "he"
    else -> "they"
}

/**
 * NILA's one-line reply when it saves a fact: "I'll remember she loves pani puri.", "I'll remember you're vegetarian."
 * The request above already names the person, so the reply doesn't repeat it.
 */
internal fun rememberLine(about: String, fact: String): String {
    val subject = pronounFor(about)
    val line = factLine(fact)
    val first = line.substringBefore(' ').lowercase()
    val rest = line.substringAfter(' ', "")
    val plural = subject == "you" || subject == "they"
    val said = when {
        first in Verbs -> "$subject ${if (plural) first.removeSuffix("s") else first} $rest"
        else -> "$subject${if (plural) "'re" else "'s"} ${line.replaceFirstChar(Char::lowercase)}"
    }
    return "I'll remember ${said.trim()}."
}

/** The letter shown when a contact has no photo. */
internal fun contactInitial(name: String): String = name.trim().firstOrNull()?.uppercase() ?: "?"

/** Picks a contact from the system picker or a suggestion, and reports the one chosen. */
private class ContactLinker(val link: (ContactChoice) -> Unit, val pick: () -> Unit)

@Composable
private fun rememberContactLinker(context: Context, onLink: (ContactChoice) -> Unit): ContactLinker {
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { withContext(Dispatchers.IO) { resolvePickedContact(context, uri) }?.let(onLink) }
    }
    return ContactLinker(link = onLink, pick = { picker.launch(null) })
}

/** A round avatar: the contact's photo when there is one, otherwise their initial. */
@Composable
private fun ContactAvatar(choice: ContactChoice, context: Context) {
    val photo by produceState<String?>(null, choice.lookupKey) {
        value = withContext(Dispatchers.IO) { contactPhotoUri(context, choice.lookupKey) }
    }
    Box(Modifier.size(AvatarSize).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
        val uri = photo
        if (uri == null) {
            Text(contactInitial(choice.name), color = Paper, fontSize = 15.sp)
        } else {
            coil.compose.AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(AvatarSize))
        }
    }
}

@Composable
private fun LinkedRow(choice: ContactChoice, context: Context) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ContactAvatar(choice, context)
        Text("Linked to ${choice.name}", color = Paper, fontSize = 16.sp)
    }
}

/** A small rounded chip: an optional avatar and a label. */
@Composable
private fun Chip(label: String, onClick: () -> Unit, leading: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).clickable(onClick = onClick)
            .border(1.dp, Stroke, androidx.compose.foundation.shape.RoundedCornerShape(50))
            .padding(start = if (leading != null) 4.dp else 14.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Text(label, color = Paper, fontSize = 14.sp)
    }
}

/**
 * Saving a fact is a short reply, not a form: one line from NILA, then the people it might be as chips,
 * "Pick contact", and Undo. A linked contact replaces the chips with their face and name.
 */
@Composable
internal fun SavedMomentCard(moment: SavedMoment, context: Context) {
    val memory = remember { NilaMemory(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val about = moment.saved.about
    var linked by remember { mutableStateOf<ContactChoice?>(null) }
    var undone by remember { mutableStateOf(false) }
    val linker = rememberContactLinker(context) { choice ->
        linked = choice
        scope.launch { memory.linkContact(about, choice) }
    }

    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (undone) {
            Text("Okay, forgotten.", color = Muted, fontSize = ReplySize)
            return@Column
        }
        Text(rememberLine(about, moment.saved.fact), color = Paper, fontSize = ReplySize, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val choice = linked
            when {
                choice != null -> Chip(choice.name, {}) { ContactAvatar(choice, context) }
                moment.suggestions != null -> {
                    moment.suggestions.forEach { s -> Chip(s.name, { linker.link(s) }) { ContactAvatar(s, context) } }
                    Chip("Pick contact", linker.pick)
                }
            }
            TextAction("Undo", { undone = true; scope.launch { memory.forget(moment.saved.fact, about) } }, modifier = Modifier.padding(start = 6.dp), color = Muted)
        }
    }
}

/** When someone came up in a request and nothing was saved: just the people it might be, as chips. */
@Composable
internal fun LinkContactCard(response: LinkContact, context: Context) {
    val memory = remember { NilaMemory(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf<ContactChoice?>(null) }
    val linker = rememberContactLinker(context) { choice ->
        linked = choice
        scope.launch { memory.linkContact(response.about, choice) }
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val choice = linked
        if (choice != null) {
            Chip(choice.name, {}) { ContactAvatar(choice, context) }
            return@Column
        }
        Text("Who's ${aboutPhrase(response.about)} in your contacts?", color = Paper, fontSize = ReplySize, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            response.suggestions.forEach { s -> Chip(s.name, { linker.link(s) }) { ContactAvatar(s, context) } }
            Chip("Pick contact", linker.pick)
        }
    }
}
