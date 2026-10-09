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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NilaApp() }
    }
}

private data class Turn(val user: String, val response: NilaResponse)

private val Ink = Color(0xFF151513)
private val Panel = Color(0xFF22221F)
private val CardInk = Color(0xFF2D2D29)
private val Paper = Color(0xFFF2F0E9)
private val Muted = Color(0xFFAAA99F)
private val Lime = Color(0xFFD9DF36)
private val Orange = Color(0xFFE84619)

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
            primary = Lime,
            onPrimary = Ink,
            background = Ink,
            surface = Panel,
            onSurface = Paper,
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Ink)
                    .imePadding()
                    .padding(20.dp),
            ) {
                NilaHeader()
                Spacer(Modifier.height(24.dp))

                if (turns.isEmpty()) {
                    Text(
                        "Your phone,\nwithout the hunt.",
                        fontSize = 36.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Ask for an outcome. NILA answers, creates a useful view, or opens the full experience.",
                        modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                        color = Muted,
                        lineHeight = 20.sp,
                    )
                    DemoSummary()
                    Spacer(Modifier.height(20.dp))
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
                                prepare = engine::prepare,
                            ) { label, next -> turns += Turn(label, next) }
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
                    trailingIcon = { Button(onClick = { submit() }) { Text("Go") } },
                )
            }
        }
    }
}

@Composable
private fun NilaHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).background(Lime, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("N", color = Ink, fontWeight = FontWeight.Black) }
            Text(
                "NILA",
                modifier = Modifier.padding(start = 10.dp),
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
        }
        Text("ON DEVICE", color = Lime, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DemoSummary() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("PRIVATE BY DESIGN", color = Lime, style = MaterialTheme.typography.labelSmall)
            Text("You decide before NILA acts.", modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.Bold)
            Text(
                "This demo uses local rules and simulated provider results. Sending, paying and booking always stop for confirmation.",
                modifier = Modifier.padding(top = 5.dp),
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
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
                colors = ButtonDefaults.buttonColors(containerColor = CardInk),
            ) { Text(prompt, color = Color.White) }
        }
    }
}

@Composable
private fun ResponseView(
    response: NilaResponse,
    context: Context,
    prepare: (Choice) -> NilaResponse.Confirmation,
    addTurn: (String, NilaResponse) -> Unit,
) {
    when (response) {
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
                .onSuccess { addTurn("Action result", NilaResponse.Text("Alarm request sent to Android.")) }
                .onFailure { addTurn("Action result", NilaResponse.Text("No alarm application is available.")) }
        }
        is NilaResponse.OpenApp -> ActionCard(
            title = response.name,
            detail = "This experience is better in its full interface.",
            action = "Open",
        ) {
            val launch = context.packageManager.getLaunchIntentForPackage(response.packageName)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse(response.fallbackUrl))
            runCatching { context.startActivity(launch) }
                .onFailure { addTurn("Action result", NilaResponse.Text("${response.name} could not be opened.")) }
        }
        is NilaResponse.Choices -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(response.intro)
            response.items.forEach { item ->
                ActionCard(item.title, "${item.detail} · ${item.eta}", "Review ${item.price}") {
                    addTurn("Prepared action", prepare(item))
                }
            }
        }
        is NilaResponse.Confirmation -> ConfirmationCard(response) {
            addTurn(
                "Action result",
                NilaResponse.Text("Simulation complete. No order was placed and no payment or data was shared."),
            )
        }
    }
}

@Composable
private fun ConfirmationCard(response: NilaResponse.Confirmation, onConfirm: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF321F19)),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CONFIRMATION REQUIRED", color = Orange, style = MaterialTheme.typography.labelSmall)
            Text(response.title, fontWeight = FontWeight.Bold)
            Text(response.detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(response.price, color = Paper, style = MaterialTheme.typography.titleLarge)
            Text(
                "Demo only. This stops before a real provider, order or payment.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("Confirm simulation") }
        }
    }
}

@Composable
private fun ActionCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardInk),
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

