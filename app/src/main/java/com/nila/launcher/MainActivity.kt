package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NilaApp() }
    }
}

data class Turn(val user: String, val response: NilaResponse)
private data class PendingZeptoOrder(val item: String, val client: ZeptoMcpClient)

/** Builds the tap-to-add-to-cart turn for a Zepto search result. Prepare-tier only: addToCart never places or pays for an order. */
private fun zeptoProductTurn(
    item: String,
    products: List<ZeptoProduct>,
    client: ZeptoMcpClient,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    appendTurn: (Turn) -> Unit,
): Turn {
    val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        ?: "nila-device"
    val cheapest = products.sortedBy { it.priceInPaisa }.take(6)
    val productChoices = cheapest.map { product ->
        AsyncChoice(
            title = product.name,
            detail = product.packSize,
            price = "₹%.2f".format(product.priceInPaisa / 100.0),
            eta = "Zepto quick delivery",
            imageUrl = product.imageUrl,
        ) {
            coroutineScope.launch(Dispatchers.IO) {
                val message = runCatching {
                    client.addToCart(deviceId, product)
                    "Added ${product.name} to your Zepto cart. Demo only: nothing was ordered."
                }.getOrElse { "Could not add to cart: ${it.message}" }
                withContext(Dispatchers.Main) { appendTurn(Turn("Zepto", NilaResponse.Text(message))) }
            }
        }
    }
    val zeptoSummary = cheapest.minOfOrNull { it.priceInPaisa }?.let { "from ₹%.2f".format(it / 100.0) } ?: "no results"
    val zeptoGroup = ProviderGroup("Zepto", zeptoSummary, productChoices, if (productChoices.isEmpty()) "No Zepto results for \"$item\"." else null)
    val groups = listOf(zeptoGroup, swiggyPendingGroup(ShoppingCategory.GROCERY))
    return Turn("Zepto", NilaResponse.GroupedChoices("Options for \"$item\":", groups))
}

@Composable
private fun NilaApp() {
    val context = LocalContext.current
    val engine = remember { NilaEngine() }
    val turns = remember { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    val memory = remember { context.getSharedPreferences("nila_memory", Context.MODE_PRIVATE) }
    val zeptoAuth = remember { ZeptoAuth(context) }
    val coroutineScope = rememberCoroutineScope()
    var pendingZeptoOrder by remember { mutableStateOf<PendingZeptoOrder?>(null) }

    fun submit(prompt: String = input) {
        if (prompt.isBlank()) return
        if (prompt.lowercase().contains("connect zepto")) {
            turns += Turn(prompt, NilaResponse.Text("Opening Zepto login in your browser. Sign in and come back to NILA."))
            input = ""
            coroutineScope.launch(Dispatchers.IO) {
                zeptoAuth.connect(context)
                    .onSuccess { Log.i("ZeptoAuth", "Zepto authorization completed.") }
                    .onFailure { Log.w("ZeptoAuth", "Zepto authorization failed: ${it.message}") }
            }
            return
        }
        if (prompt.lowercase().contains("zepto tools")) {
            turns += Turn(prompt, NilaResponse.Text("Checking what Zepto's MCP actually exposes..."))
            input = ""
            coroutineScope.launch(Dispatchers.IO) {
                val result = runCatching { ZeptoMcpClient(zeptoAuth).listTools() }
                val message = result.fold(
                    onSuccess = { tools -> "Zepto tools: " + tools.joinToString(", ") { it.name } },
                    onFailure = { "Zepto tools/list failed: ${it.message}" },
                )
                Log.i("ZeptoAuth", message)
                withContext(Dispatchers.Main) {
                    turns += Turn("Zepto tools result", NilaResponse.Text(message))
                }
            }
            return
        }
        if (prompt.lowercase().contains("zepto schema")) {
            turns += Turn(prompt, NilaResponse.Text("Dumping Zepto's tool schemas to a file..."))
            input = ""
            coroutineScope.launch(Dispatchers.IO) {
                val message = runCatching {
                    val raw = ZeptoMcpClient(zeptoAuth).listToolsRaw().toString(2)
                    context.openFileOutput("zepto_tools.json", Context.MODE_PRIVATE).use { it.write(raw.toByteArray()) }
                    "Wrote ${raw.length} bytes to files/zepto_tools.json"
                }.getOrElse { "Zepto schema dump failed: ${it.message}" }
                Log.i("ZeptoAuth", message)
            }
            return
        }
        if (prompt.lowercase().startsWith("zepto call ")) {
            val rest = prompt.substring("zepto call ".length).trim()
            val toolName = rest.substringBefore(" ")
            val argsJson = rest.substringAfter(" ", "{}")
            turns += Turn(prompt, NilaResponse.Text("Calling Zepto tool \"$toolName\"..."))
            input = ""
            coroutineScope.launch(Dispatchers.IO) {
                val message = runCatching {
                    ZeptoMcpClient(zeptoAuth).callTool(toolName, org.json.JSONObject(argsJson)).toString(2)
                }.getOrElse { "Zepto call failed: ${it.message}" }
                Log.i("ZeptoAuth", "zepto call $toolName -> $message")
            }
            return
        }
        val pending = pendingZeptoOrder
        if (pending != null) {
            pendingZeptoOrder = null
            val addressText = prompt.trim()
            turns += Turn(prompt, NilaResponse.Text("Saving that address on Zepto..."))
            input = ""
            coroutineScope.launch(Dispatchers.IO) {
                val outcome = runCatching {
                    @Suppress("DEPRECATION")
                    val location = android.location.Geocoder(context).getFromLocationName(addressText, 1)?.firstOrNull()
                        ?: error("Could not find that address. Try including city and pincode.")
                    val user = runCatching { pending.client.getUserDetails() }.getOrNull()
                    val args = org.json.JSONObject()
                        .put("type", "OTHER")
                        .put("name", "NILA delivery")
                        .put("flatDetails", "NA")
                        .put("buildingName", location.featureName?.takeIf { it.isNotBlank() } ?: "NA")
                        .put("latitude", location.latitude)
                        .put("longitude", location.longitude)
                        .put("formattedAddress", addressText)
                        .put("shortAddress", addressText.take(80))
                        .put("contactName", user?.optString("fullName")?.takeIf { it.isNotBlank() } ?: "NILA User")
                        .put("contactNumber", user?.optString("phoneNumber") ?: "")
                    val before = pending.client.listSavedAddresses().map { it.id }.toSet()
                    pending.client.addSavedAddress(args)
                    val newAddress = pending.client.listSavedAddresses().firstOrNull { it.id !in before }
                        ?: error("Address was saved but could not be found again.")
                    pending.client.selectSavedAddress(newAddress.id)
                    pending.client.searchProducts(pending.item)
                }
                withContext(Dispatchers.Main) {
                    outcome.fold(
                        onSuccess = { products -> turns += zeptoProductTurn(pending.item, products, pending.client, context, coroutineScope) { t -> turns += t } },
                        onFailure = { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${it.message}")) },
                    )
                }
            }
            return
        }
        if (prompt.lowercase().startsWith("buy ")) {
            val item = prompt.substring("buy ".length).trim()
            val category = classifyShoppingIntent(item)
            input = ""
            if (category == ShoppingCategory.FOOD) {
                turns += Turn(
                    prompt,
                    NilaResponse.GroupedChoices(
                        "\"$item\" is a restaurant order — Zepto doesn't do food:",
                        listOf(swiggyPendingGroup(category)),
                    ),
                )
                return
            }
            turns += Turn(prompt, NilaResponse.Text("Checking your Zepto delivery address..."))
            coroutineScope.launch(Dispatchers.IO) {
                val client = ZeptoMcpClient(zeptoAuth)
                val outcome = runCatching { client.listSavedAddresses() }
                outcome.fold(
                    onSuccess = { addresses ->
                        if (addresses.isEmpty()) {
                            withContext(Dispatchers.Main) {
                                pendingZeptoOrder = PendingZeptoOrder(item, client)
                                turns += Turn("Zepto", NilaResponse.Text("No saved Zepto address found. What's your delivery address? (include city and pincode)"))
                            }
                        } else {
                            val addressChoices = addresses.map { address ->
                                AsyncChoice(address.label.ifBlank { "Saved address" }, address.addressLine, "", "") {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val searchOutcome = runCatching {
                                            client.selectSavedAddress(address.id)
                                            client.searchProducts(item)
                                        }
                                        withContext(Dispatchers.Main) {
                                            searchOutcome.fold(
                                                onSuccess = { products -> turns += zeptoProductTurn(item, products, client, context, coroutineScope) { t -> turns += t } },
                                                onFailure = { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${it.message}")) },
                                            )
                                        }
                                    }
                                }
                            } + AsyncChoice("Use a new address", "Enter a different delivery address", "", "") {
                                pendingZeptoOrder = PendingZeptoOrder(item, client)
                                turns += Turn("Zepto", NilaResponse.Text("What's the new delivery address? (include city and pincode)"))
                            }
                            withContext(Dispatchers.Main) {
                                turns += Turn("Zepto", NilaResponse.AsyncChoices("Use which address for \"$item\"?", addressChoices))
                            }
                        }
                    },
                    onFailure = { e ->
                        withContext(Dispatchers.Main) { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${e.message}")) }
                    },
                )
            }
            return
        }
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
                            ResponseView(turn.response, context) { message ->
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
private fun ResponseView(response: NilaResponse, context: Context, addStatus: (String) -> Unit) {
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
        is NilaResponse.AsyncChoices -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(response.intro)
            response.items.forEach { item ->
                ActionCard(item.title, "${item.detail}${if (item.eta.isNotBlank()) " · ${item.eta}" else ""}", item.price.ifBlank { "Select" }, item.onClick)
            }
        }
        is NilaResponse.GroupedChoices -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(response.intro)
            response.groups.forEach { group -> ProviderGroupCard(group) }
        }
    }
}

@Composable
private fun ProviderGroupCard(group: ProviderGroup) {
    var expanded by remember(group.providerName) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = Color(0xFF292925)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${group.providerName} — ${group.summary}", fontWeight = FontWeight.Bold)
                Text(if (expanded) "▾" else "▸", color = Color(0xFFD9DF36))
            }
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group.items.isEmpty()) {
                    Text(group.note ?: "No results.", style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                } else {
                    group.items.forEach { item -> ImageActionCard(item) }
                }
            }
        }
    }
}

@Composable
private fun ImageActionCard(item: AsyncChoice) {
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
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (item.imageUrl != null) {
                    coil.compose.AsyncImage(
                        model = item.imageUrl,
                        contentDescription = item.title,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column {
                    Text(item.title, fontWeight = FontWeight.Bold)
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = Color.LightGray)
                }
            }
            Button(onClick = item.onClick) { Text(item.price.ifBlank { "Select" }) }
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

