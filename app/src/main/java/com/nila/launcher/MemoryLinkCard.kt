package com.nila.launcher

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
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

private val CardHeadlineSize = 24.sp
private val AvatarSize = 36.dp

/** "Saved for you", "Saved for your wife", "Saved for Priya". */
internal fun savedHeadline(about: String): String = "Saved for ${aboutPhrase(about)}"

/** "Which contact is your wife?" */
internal fun linkQuestion(about: String): String = "Which contact is ${aboutPhrase(about)}?"

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

/** The suggested contacts as rows, then the picker. */
@Composable
private fun ContactRows(suggestions: List<ContactChoice>, linker: ContactLinker, context: Context) {
    suggestions.forEach { choice ->
        Row(
            Modifier.fillMaxWidth().clickable { linker.link(choice) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ContactAvatar(choice, context)
            Text(choice.name, color = Paper, fontSize = 16.sp)
        }
    }
    TextAction(if (suggestions.isEmpty()) "Choose from contacts" else "Choose another contact", linker.pick)
}

/** A saved fact and, if wanted, "which contact is she?" in one card. Undo forgets the fact. */
@Composable
internal fun SavedMomentCard(moment: SavedMoment, context: Context) {
    val memory = remember { NilaMemory(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val about = moment.saved.about
    var linked by remember { mutableStateOf<ContactChoice?>(null) }
    var declined by remember { mutableStateOf(false) }
    var undone by remember { mutableStateOf(false) }
    val linker = rememberContactLinker(context) { choice ->
        linked = choice
        scope.launch { memory.linkContact(about, choice) }
    }
    val asking = moment.suggestions != null && linked == null && !declined

    NilaCard {
        if (undone) {
            Text("Forgotten. Nothing was kept.", color = Muted, fontSize = 14.sp)
            return@NilaCard
        }
        SerifTitle(savedHeadline(about), size = CardHeadlineSize)
        Text(factLine(moment.saved.fact), color = Paper, fontSize = 16.sp)
        if (asking) {
            HorizontalDivider(color = Stroke, modifier = Modifier.padding(vertical = 4.dp))
            Text(linkQuestion(about), color = Muted, fontSize = 14.sp)
            ContactRows(moment.suggestions.orEmpty(), linker, context)
        }
        linked?.let {
            HorizontalDivider(color = Stroke, modifier = Modifier.padding(vertical = 4.dp))
            LinkedRow(it, context)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (asking) TextAction("Not now", { declined = true }, color = Muted)
            TextAction("Undo", { undone = true; scope.launch { memory.forget(moment.saved.fact, about) } }, color = Muted)
        }
    }
}

/** "Which contact is your wife?" on its own, when someone came up in a request and nothing was saved. */
@Composable
internal fun LinkContactCard(response: LinkContact, context: Context) {
    val memory = remember { NilaMemory(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf<ContactChoice?>(null) }
    var declined by remember { mutableStateOf(false) }
    val linker = rememberContactLinker(context) { choice ->
        linked = choice
        scope.launch { memory.linkContact(response.about, choice) }
    }

    NilaCard {
        val choice = linked
        when {
            choice != null -> LinkedRow(choice, context)
            declined -> Text("No problem. I won't ask again.", color = Muted, fontSize = 14.sp)
            else -> {
                SerifTitle(linkQuestion(response.about), size = CardHeadlineSize)
                ContactRows(response.suggestions, linker, context)
                TextAction("Not now", { declined = true }, color = Muted)
            }
        }
    }
}
