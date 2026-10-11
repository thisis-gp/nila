package com.nila.launcher

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Which contact is your wife?": suggested contacts as pills, the system picker, or "Not now". Asked once per person. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinkContactCard(response: LinkContact, context: Context) {
    val memory = remember { NilaMemory(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<String?>(null) }

    fun link(choice: ContactChoice) {
        outcome = "Linked your ${response.about} to ${choice.name}."
        scope.launch { memory.linkContact(response.about, choice) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { withContext(Dispatchers.IO) { resolvePickedContact(context, uri) }?.let(::link) }
    }

    NilaCard {
        SectionLabel("My NILA", color = Lime)
        Text("Which contact is your ${response.about}?", color = Paper, fontSize = 16.sp)
        outcome?.let { Text(it, color = Muted, fontSize = 13.sp) } ?: FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            response.suggestions.forEach { choice ->
                OutlinedButton(onClick = { link(choice) }, shape = CircleShape, border = BorderStroke(1.dp, Stroke)) {
                    Text(choice.name, color = Paper, fontSize = 13.sp)
                }
            }
            OutlinedButton(onClick = { picker.launch(null) }, shape = CircleShape, border = BorderStroke(1.dp, Lime)) {
                Text("Pick from contacts", color = Lime, fontSize = 13.sp)
            }
            OutlinedButton(onClick = { outcome = "No problem. I won't ask again." }, shape = CircleShape, border = BorderStroke(1.dp, Stroke)) {
                Text("Not now", color = Muted, fontSize = 13.sp)
            }
        }
    }
}
