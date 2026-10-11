package com.nila.launcher

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AvatarSize = 28.dp

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

/** The avatar of a person who is not linked to a contact yet: their initial. */
@Composable
private fun InitialAvatar(label: String) {
    Box(Modifier.size(AvatarSize).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
        Text(contactInitial(label), color = Paper, fontSize = 15.sp)
    }
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
 * Saving a fact is a short reply and a card: "Remembered", the person and the fact, then the people it might be
 * as chips, "Pick contact", and Undo. A linked contact replaces the chips with their face and name. For a cousin
 * or friend ([SavedMoment.who] set) the card asks "Which cousin?" every time and files the fact on the one picked.
 */
@Composable
internal fun SavedMomentCard(moment: SavedMoment, context: Context) {
    val memory = remember { NilaMemory(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val about = moment.saved.about
    val asksWho = moment.who != null
    var linked by remember { mutableStateOf<ContactChoice?>(null) }
    var chosen by remember { mutableStateOf<WhoChoice?>(null) }
    var naming by remember { mutableStateOf(false) }
    var undone by remember { mutableStateOf(false) }
    fun choose(who: WhoChoice) {
        chosen = who
        naming = false
        scope.launch { memory.refile(about, moment.saved.fact, who) }
    }
    val linker = rememberContactLinker(context) { choice ->
        if (asksWho) return@rememberContactLinker choose(WhoChoice(choice.name, choice.lookupKey))
        linked = choice
        scope.launch { memory.linkContact(about, choice) }
    }
    fun undo() {
        undone = true
        val key = chosen?.let { pickedPersonKey(it.name, it.lookupKey) } ?: about
        scope.launch { memory.forget(moment.saved.fact, key) }
    }

    if (undone) return NilaLine("Okay, forgotten.")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NilaLine("Noted. I'll keep that.")
        val choice = linked
        val who = chosen
        val open = asksWho && who == null
        ResultCard(
            icon = LucideR.drawable.lucide_ic_user,
            title = "Remembered",
            actions = {
                if (open) {
                    moment.who.orEmpty().forEach { w ->
                        val face = w.lookupKey?.let { ContactChoice(it, w.name) }
                        Chip(whoLabel(w, about), { choose(w) }, leading = if (face != null) { { ContactAvatar(face, context) } } else null)
                    }
                    Chip("Pick contact", linker.pick)
                    if (!naming) Chip("Someone new", { naming = true })
                } else if (choice == null && moment.suggestions != null) {
                    moment.suggestions.forEach { s -> Chip(s.name, { linker.link(s) }) { ContactAvatar(s, context) } }
                    Chip("Pick contact", linker.pick)
                }
                TextAction("Undo", ::undo, modifier = Modifier.padding(start = 6.dp), color = Muted)
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val face = choice ?: who?.lookupKey?.let { ContactChoice(it, who.name) }
                val name = choice?.name ?: who?.name?.trim() ?: aboutLabel(about)
                if (face != null) ContactAvatar(face, context) else InitialAvatar(name)
                Column {
                    Text(name, color = Paper, fontSize = 17.sp, fontFamily = FontFamily.Serif)
                    val line = factLine(moment.saved.fact)
                    Text(if (who != null) "${aboutLabel(about)} · $line" else line, color = Muted, fontSize = 14.sp)
                }
            }
            if (open) Text("Which $about?", color = Paper, fontSize = 15.sp)
            if (open && naming) NewPersonField(onDone = { name -> choose(WhoChoice(name, lookupKey = null)) })
        }
    }
}

/** A name for someone who is not in contacts, typed in the card. */
@Composable
private fun NewPersonField(onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Name", color = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onDone(name.trim()) }),
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Paper, unfocusedTextColor = Paper, focusedBorderColor = Lime, unfocusedBorderColor = Stroke, cursorColor = Lime),
        )
        if (name.isNotBlank()) Chip("Save", { onDone(name.trim()) })
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val choice = linked
        if (choice != null) {
            Chip(choice.name, {}) { ContactAvatar(choice, context) }
            return@Column
        }
        Text("Who's ${aboutPhrase(response.about)} in your contacts?", color = Paper, fontSize = 19.sp, fontFamily = FontFamily.Serif)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            response.suggestions.forEach { s -> Chip(s.name, { linker.link(s) }) { ContactAvatar(s, context) } }
            Chip("Pick contact", linker.pick)
        }
    }
}
