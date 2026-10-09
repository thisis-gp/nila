package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NilaApp() }
    }
}

private data class Turn(val user: String, val response: NilaResponse)

@Composable
private fun NilaApp() {
    val context = LocalContext.current
    val engine = remember { NilaEngine() }
    val turns = remember { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    val memory = remember { context.getSharedPreferences("nila_memory", Context.MODE_PRIVATE) }

    fun submit(prompt: String = input) {
        if (prompt.isBlank()) return
        val response = if (prompt.lowercase().contains("what do you remember")) {
            NilaResponse.Text(memory.getString("fact", null)?.let { "You asked me to remember: $it" } ?: "You have not asked me to remember anything yet.")
        } else {
            engine.respond(prompt)
        }
        if (response is NilaResponse.Remember) memory.edit().putString("fact", response.fact).apply()
        turns += Turn(prompt, response)
        input = ""
    }

    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFFD9DF36),
            onPrimary = Color(0xFF151513),
            background = Color(0xFF151513),
            surface = Color(0xFF22221F),
            onSurface = Color(0xFFF2F0E9),
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF151513))
                    .imePadding()
                    .padding(20.dp),
            ) {
                Text("NILA", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text("Say what you need.", color = Color(0xFFD9DF36))
                Spacer(Modifier.height(16.dp))

                if (turns.isEmpty()) {
                    DemoPrompts(::submit)
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(turns) { turn ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("YOU", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(turn.user, style = MaterialTheme.typography.titleMedium)
                            ResponseView(
                                response = turn.response,
                                context = context,
                                confirm = { call ->
                                    turns += Turn("Confirmed", engine.execute(NilaIntent.Call(call), confirmed = true))
                                },
                            ) { message ->
                                turns += Turn("Confirmation", NilaResponse.Text(message))
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("What do you want to do?") },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = { Button(onClick = { submit() }) { Text("Ask") } },
                )
            }
        }
    }
}

@Composable
private fun DemoPrompts(onSelect: (String) -> Unit) {
    val prompts = listOf(
        "Order chicken biryani under ₹250",
        "What is 18% of 4250?",
        "Set an alarm for 6:30 am",
        "Open YouTube",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("TRY A REQUEST", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        prompts.forEach { prompt ->
            Button(
                onClick = { onSelect(prompt) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2D29)),
            ) { Text(prompt, color = Color.White) }
        }
    }
}

@Composable
private fun ResponseView(
    response: NilaResponse,
    context: Context,
    confirm: (ToolCall) -> Unit,
    addStatus: (String) -> Unit,
) {
    when (response) {
        // ponytail: plain tap confirms; biometric prompt comes with the confirmation flow.
        is NilaResponse.NeedsConfirmation -> ActionCard(
            title = response.capability.description,
            detail = "Nothing happens until you confirm.",
            action = "Confirm",
        ) { confirm(response.call) }
        is NilaResponse.Text -> Text(response.value)
        is NilaResponse.Remember -> Text("I'll remember: ${response.fact}")
        is NilaResponse.Alarm -> ActionCard(
            title = "Alarm ${"%02d:%02d".format(response.hour, response.minute)}",
            detail = "Ready to ask Android to set this alarm.",
            action = "Set alarm",
        ) {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, response.hour)
                putExtra(AlarmClock.EXTRA_MINUTES, response.minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            }
            runCatching { context.startActivity(intent) }
                .onSuccess { addStatus("Alarm request sent to Android.") }
                .onFailure { addStatus("No alarm application is available.") }
        }
        is NilaResponse.OpenApp -> ActionCard(
            title = response.name,
            detail = "This experience is better in its full interface.",
            action = "Open",
        ) {
            val launch = context.packageManager.getLaunchIntentForPackage(response.packageName)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse(response.fallbackUrl))
            runCatching { context.startActivity(launch) }
                .onFailure { addStatus("${response.name} could not be opened.") }
        }
        is NilaResponse.Choices -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(response.intro)
            response.items.forEach { item ->
                ActionCard(item.title, "${item.detail} · ${item.eta}", "Choose ${item.price}") {
                    addStatus("Prepared ${item.title} for ${item.price}. Demo only: nothing was ordered.")
                }
            }
        }
    }
}

@Composable
private fun ActionCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF292925)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                Column {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            }
            Button(onClick = onClick) { Text(action) }
        }
    }
}

