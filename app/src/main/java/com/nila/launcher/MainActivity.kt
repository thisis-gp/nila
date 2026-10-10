package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR

class MainActivity : ComponentActivity() {
    private var resumeVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge hands the keyboard inset to Compose; safeDrawingPadding in NilaApp applies it once.
        // Dark bar styles keep light status icons on NILA's dark background in any system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { NilaApp(resumeVersion) }
    }

    override fun onResume() {
        super.onResume()
        resumeVersion++
    }
}

data class Turn(val user: String, val response: NilaResponse)
private data class PendingZeptoOrder(val item: String, val client: ZeptoMcpClient)
private data class ActiveZeptoSession(val client: ZeptoMcpClient, val addressLabel: String)

/** Builds the tap-to-add-to-cart turn for a Zepto search result. Prepare-tier only: addToCart never places or pays for an order. */
private fun zeptoProductTurn(
    item: String,
    products: List<ZeptoProduct>,
    client: ZeptoMcpClient,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    appendTurn: (Turn) -> Unit,
    desiredSize: String? = null,
): Turn {
    val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        ?: "nila-device"
    val desiredNormalized = desiredSize?.let { normalizedSize(it) }
    val cheapest = products.sortedWith(
        compareByDescending<ZeptoProduct> { product ->
            desiredNormalized != null && normalizedSize(product.packSize)?.let { (unit, value) ->
                unit == desiredNormalized.first && kotlin.math.abs(value - desiredNormalized.second) < 1.0
            } == true
        }.thenBy { it.priceInPaisa },
    ).take(6)
    val productChoices = cheapest.map { product ->
        AsyncChoice(
            title = product.name,
            detail = product.packSize,
            price = "₹%.2f".format(product.priceInPaisa / 100.0),
            eta = "Zepto quick delivery",
            imageUrl = product.imageUrl,
        ) {
            coroutineScope.launch(Dispatchers.IO) {
                val outcome = runCatching { client.addToCart(deviceId, product) }
                withContext(Dispatchers.Main) {
                    outcome.fold(
                        onSuccess = {
                            appendTurn(Turn("Zepto", NilaResponse.Text("Added ${product.name} to your real Zepto cart. NILA stops here — open Zepto to pay and finish the order:")))
                            appendTurn(Turn("Zepto", NilaResponse.OpenApp("Zepto", "com.zeptoconsumerapp", "https://www.zeptonow.com")))
                        },
                        onFailure = { appendTurn(Turn("Zepto", NilaResponse.Text("Could not add to cart: ${it.message}"))) },
                    )
                }
            }
        }
    }
    val zeptoSummary = cheapest.minOfOrNull { it.priceInPaisa }?.let { "from ₹%.2f".format(it / 100.0) } ?: "no results"
    val zeptoGroup = ProviderGroup("Zepto", zeptoSummary, productChoices, if (productChoices.isEmpty()) "No Zepto results for \"$item\"." else null)
    val groups = listOf(zeptoGroup, swiggyPendingGroup(ShoppingCategory.GROCERY))
    return Turn("Zepto", NilaResponse.GroupedChoices("Options for \"$item\":", groups))
}

internal val Ink = Color(0xFF151513)
internal val Panel = Color(0xFF22221F)
internal val CardInk = Color(0xFF2D2D29)
internal val Paper = Color(0xFFF2F0E9)
internal val Muted = Color(0xFFAAA99F)
internal val Lime = Color(0xFFD9DF36)
internal val Orange = Color(0xFFE84619)

@Composable
private fun NilaApp(resumeVersion: Int) {
    val context = LocalContext.current
    val engine = remember { NilaEngine() }
    // Gemma 4 E2B and FunctionGemma load in the background; until then NILA answers with its rules.
    val brain = remember { NilaBrain(context.applicationContext, engine) }
    LaunchedEffect(brain) { brain.load() }
    val turns = remember { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    val installedApps = remember(context, resumeVersion) { loadInstalledApps(context) }
    val launcherPreferences = remember(context) { context.getSharedPreferences("nila_launcher", Context.MODE_PRIVATE) }
    val pinnedPackages = remember(installedApps, launcherPreferences) {
        val installedPackages = installedApps.mapTo(mutableSetOf()) { it.packageName }
        mutableStateListOf<String>().apply {
            addAll(
                reconcileHomePins(
                    savedPins = launcherPreferences.getStringSet("home_pins", null),
                    installedPackages = installedPackages,
                    suggestedPackages = suggestedHomeApps(installedApps),
                )
            )
        }
    }
    LaunchedEffect(installedApps) {
        launcherPreferences.edit().putStringSet("home_pins", pinnedPackages.toSet()).apply()
    }
    val memory = remember { context.getSharedPreferences("nila_memory", Context.MODE_PRIVATE) }
    var rememberedFact by remember { mutableStateOf(memory.getString("fact", null)) }
    val navigation = remember { NilaNavigation(NilaScreen.Home) }
    var screen by remember { mutableStateOf(navigation.current) }
    var overlayScreen by remember { mutableStateOf(screen) }
    if (screen != NilaScreen.Home) overlayScreen = screen

    fun open(destination: NilaScreen) {
        screen = if (destination == NilaScreen.Home) navigation.select(NilaScreen.Home) else navigation.open(destination)
    }

    fun chooseDefaultHome() {
        runCatching { context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
            .onFailure { Toast.makeText(context, "Android home-app settings are unavailable.", Toast.LENGTH_SHORT).show() }
    }

    val scope = rememberCoroutineScope()
    var isThinking by remember { mutableStateOf(false) }
    var justReplied by remember { mutableStateOf(false) }
    val zeptoAuth = remember { ZeptoAuth(context) }
    var pendingZeptoOrder by remember { mutableStateOf<PendingZeptoOrder?>(null) }
    // Zepto requires a selected store before search works at all, so the first "buy" of a session
    // still asks for an address. Once chosen, reuse that same client+store for every later "buy"
    // in this session instead of asking again every time.
    var activeZeptoSession by remember { mutableStateOf<ActiveZeptoSession?>(null) }

    fun submit(prompt: String = input) {
        if (prompt.isBlank() || isThinking) return
        if (prompt.lowercase().contains("connect zepto")) {
            turns += Turn(prompt, NilaResponse.Text("Opening Zepto login in your browser. Sign in and come back to NILA."))
            input = ""
            scope.launch(Dispatchers.IO) {
                zeptoAuth.connect(context)
                    .onSuccess { Log.i("ZeptoAuth", "Zepto authorization completed.") }
                    .onFailure { Log.w("ZeptoAuth", "Zepto authorization failed: ${it.message}") }
            }
            return
        }
        if (prompt.lowercase().contains("zepto cart")) {
            input = ""
            if (!zeptoAuth.isConnected) {
                turns += Turn(prompt, NilaResponse.GroupedChoices("Options:", listOf(zeptoNotConnectedGroup(zeptoAuth, context, scope) { t -> turns += t })))
                return
            }
            turns += Turn(prompt, NilaResponse.Text("Checking your Zepto cart..."))
            scope.launch(Dispatchers.IO) {
                val message = runCatching { ZeptoMcpClient(zeptoAuth).viewCart() }
                    .getOrElse { "Zepto error: ${it.message}" }
                withContext(Dispatchers.Main) { turns += Turn("Zepto", NilaResponse.Text(message)) }
            }
            return
        }
        if (prompt.lowercase().contains("open zepto")) {
            turns += Turn(prompt, NilaResponse.OpenApp("Zepto", "com.zeptoconsumerapp", "https://www.zeptonow.com"))
            input = ""
            return
        }
        if (prompt.lowercase().contains("zepto tools")) {
            turns += Turn(prompt, NilaResponse.Text("Checking what Zepto's MCP actually exposes..."))
            input = ""
            scope.launch(Dispatchers.IO) {
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
            scope.launch(Dispatchers.IO) {
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
            scope.launch(Dispatchers.IO) {
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
            scope.launch(Dispatchers.IO) {
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
                    val parsed = parseOrderItem(pending.item)
                    Triple(pending.client.searchProducts(parsed.searchQuery), parsed.desiredSize, newAddress.label)
                }
                withContext(Dispatchers.Main) {
                    outcome.fold(
                        onSuccess = { (products, desiredSize, addressLabel) ->
                            activeZeptoSession = ActiveZeptoSession(pending.client, addressLabel.ifBlank { "Saved address" })
                            turns += zeptoProductTurn(pending.item, products, pending.client, context, scope, { t -> turns += t }, desiredSize)
                        },
                        onFailure = { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${it.message}")) },
                    )
                }
            }
            return
        }
        if (prompt.lowercase().contains("buy now") || prompt.lowercase().contains("checkout")) {
            input = ""
            if (!zeptoAuth.isConnected) {
                turns += Turn(prompt, NilaResponse.GroupedChoices("Options:", listOf(zeptoNotConnectedGroup(zeptoAuth, context, scope) { t -> turns += t })))
                return
            }
            val client = activeZeptoSession?.client ?: ZeptoMcpClient(zeptoAuth)
            turns += Turn(prompt, NilaResponse.Text("Checking your Zepto cart..."))
            scope.launch(Dispatchers.IO) {
                val outcome = runCatching { client.viewCartItems() }
                withContext(Dispatchers.Main) {
                    outcome.fold(
                        onSuccess = { items ->
                            if (items.isEmpty()) {
                                turns += Turn("Zepto", NilaResponse.Text("Your Zepto cart is empty. Say \"buy <item>\" to add something first."))
                                return@fold
                            }
                            val total = items.sumOf { it.priceInPaisa.toLong() * it.quantity }
                            val lines = items.joinToString("\n") { "${it.name} x${it.quantity} — ₹%.2f".format(it.priceInPaisa * it.quantity / 100.0) }
                            turns += Turn("Zepto", NilaResponse.Text("$lines\n\nTotal: ₹%.2f".format(total / 100.0)))
                            scope.launch(Dispatchers.IO) {
                                val addresses = runCatching { client.listSavedAddresses() }.getOrElse { emptyList() }
                                withContext(Dispatchers.Main) {
                                    if (addresses.isEmpty()) {
                                        turns += Turn("Zepto", NilaResponse.Text("No saved delivery address — add one in the Zepto app, then say \"buy now\" again."))
                                    } else {
                                        val addressChoices = addresses.map { address ->
                                            AsyncChoice(address.label.ifBlank { "Saved address" }, address.addressLine, "", "") {
                                                scope.launch(Dispatchers.IO) {
                                                    val selectOutcome = runCatching { client.selectSavedAddress(address.id) }
                                                    withContext(Dispatchers.Main) {
                                                        activeZeptoSession = ActiveZeptoSession(client, address.label.ifBlank { "Saved address" })
                                                        selectOutcome.fold(
                                                            onSuccess = {
                                                                turns += Turn("Zepto", NilaResponse.Text("Delivering to ${address.label.ifBlank { "this address" }}. Total ₹%.2f. NILA stops here — open Zepto to pay and finish the order:".format(total / 100.0)))
                                                                turns += Turn("Zepto", NilaResponse.OpenApp("Zepto", "com.zeptoconsumerapp", "https://www.zeptonow.com"))
                                                            },
                                                            onFailure = { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${it.message}")) },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        turns += Turn("Zepto", NilaResponse.AsyncChoices("Deliver to which address?", addressChoices))
                                    }
                                }
                            }
                        },
                        onFailure = { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${it.message}")) },
                    )
                }
            }
            return
        }
        val orderItem = extractOrderItem(prompt)
        if (orderItem != null) {
            val category = classifyShoppingIntent(orderItem)
            val isOldFoodDemo = prompt.lowercase().startsWith("order ") && category == ShoppingCategory.FOOD
            if (!isOldFoodDemo) {
                input = ""
                if (category == ShoppingCategory.FOOD) {
                    turns += Turn(
                        prompt,
                        NilaResponse.GroupedChoices(
                            "\"$orderItem\" is a restaurant order — Zepto doesn't do food:",
                            listOf(swiggyPendingGroup(category)),
                        ),
                    )
                    return
                }
                val item = orderItem
                if (!zeptoAuth.isConnected) {
                    turns += Turn(
                        prompt,
                        NilaResponse.GroupedChoices(
                            "Options for \"$item\":",
                            listOf(zeptoNotConnectedGroup(zeptoAuth, context, scope) { t -> turns += t }, swiggyPendingGroup(category)),
                        ),
                    )
                    return
                }
                val active = activeZeptoSession
                if (active != null) {
                    turns += Turn(prompt, NilaResponse.Text("Searching Zepto for \"$item\" — delivering to ${active.addressLabel}..."))
                    scope.launch(Dispatchers.IO) {
                        val parsed = parseOrderItem(item)
                        val outcome = runCatching { active.client.searchProducts(parsed.searchQuery) }
                        withContext(Dispatchers.Main) {
                            outcome.fold(
                                onSuccess = { products -> turns += zeptoProductTurn(item, products, active.client, context, scope, { t -> turns += t }, parsed.desiredSize) },
                                onFailure = { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${it.message}")) },
                            )
                        }
                    }
                    return
                }
                turns += Turn(prompt, NilaResponse.Text("Checking your Zepto delivery address..."))
                scope.launch(Dispatchers.IO) {
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
                                    scope.launch(Dispatchers.IO) {
                                        val parsed = parseOrderItem(item)
                                        val searchOutcome = runCatching {
                                            client.selectSavedAddress(address.id)
                                            client.searchProducts(parsed.searchQuery)
                                        }
                                        withContext(Dispatchers.Main) {
                                            activeZeptoSession = ActiveZeptoSession(client, address.label.ifBlank { "Saved address" })
                                            searchOutcome.fold(
                                                onSuccess = { products -> turns += zeptoProductTurn(item, products, client, context, scope, { t -> turns += t }, parsed.desiredSize) },
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
        }
        input = ""
        val pendingIndex = turns.size
        turns += Turn(prompt, NilaResponse.Text(""))
        isThinking = true
        scope.launch {
            delay(550) // a visible thinking beat, so a reply doesn't feel like a canned instant lookup
            val response = if (prompt.lowercase().contains("what do you remember")) {
                NilaResponse.Text(memory.getString("fact", null)?.let { "You asked me to remember: $it" } ?: "You have not asked me to remember anything yet.")
            } else {
                brain.respond(prompt)
            }
            if (response is NilaResponse.Remember) {
                memory.edit().putString("fact", response.fact).apply()
                rememberedFact = response.fact
            }
            turns[pendingIndex] = Turn(prompt, response)
            isThinking = false
            justReplied = true
            delay(900) // the dock head visibly "speaks" for a beat right after a reply lands
            justReplied = false
        }
    }

    BackHandler(enabled = navigation.canGoBack) { screen = navigation.back() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lime,
            onPrimary = Ink,
            background = Ink,
            onBackground = Paper,
            surface = Panel,
            onSurface = Paper,
            surfaceVariant = CardInk,
            onSurfaceVariant = Muted,
            outline = Color(0xFF45453F),
        )
    ) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val lastResponse = turns.lastOrNull()?.response
                val lastText = (lastResponse as? NilaResponse.Text)?.value.orEmpty()
                val nilaMood = when {
                    isThinking -> MascotMood.Thinking
                    justReplied -> MascotMood.Talking
                    lastResponse is NilaResponse.Choices || lastResponse is NilaResponse.Confirmation -> MascotMood.Listening
                    lastText.contains("could not", true) || lastText.contains("unavailable", true) || lastText.contains("No alarm", true) -> MascotMood.Error
                    else -> MascotMood.Idle
                }
                HomeSpace(
                    apps = installedApps,
                    pinnedPackages = pinnedPackages,
                    resumeVersion = resumeVersion,
                    nilaMood = nilaMood,
                    onOpenNila = { open(NilaScreen.Nila) },
                    onOpenApp = { app -> launchInstalledApp(context, app.packageName) },
                    onChangeWallpaper = { changeWallpaper(context) },
                    onOpenSettings = { open(NilaScreen.Settings) },
                )

                AnimatedVisibility(
                    visible = screen != NilaScreen.Home,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    when (overlayScreen) {
                        NilaScreen.Apps -> OverlayScaffold(onClose = { screen = navigation.back() }) {
                            AppsSpace(
                                apps = installedApps,
                                pinnedPackages = pinnedPackages,
                                onOpenApp = { app -> launchInstalledApp(context, app.packageName) },
                                onTogglePin = { app ->
                                    val alreadyPinned = app.packageName in pinnedPackages
                                    // shortcut: eight Home pins fit this grid; add pages before raising the limit.
                                    if (!alreadyPinned && pinnedPackages.size >= 8) {
                                        Toast.makeText(context, "Remove a Home shortcut before pinning another.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val updated = pinnedPackages.toMutableList().apply {
                                            if (alreadyPinned) remove(app.packageName) else add(app.packageName)
                                        }
                                        pinnedPackages.clear()
                                        pinnedPackages.addAll(updated)
                                        launcherPreferences.edit().putStringSet("home_pins", updated.toSet()).apply()
                                        Toast.makeText(
                                            context,
                                            if (app.packageName in updated) "${app.label} pinned to Home" else "${app.label} removed from Home",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                            )
                        }
                        NilaScreen.Nila -> OverlayScaffold(onClose = { screen = navigation.back() }) {
                            AgentSpace(
                                turns = turns,
                                input = input,
                                isThinking = isThinking,
                                modelStatus = "Gemma 4 E2B ${brain.assistantState.label} · FunctionGemma ${brain.routerState.label}",
                                transcribe = brain::transcribe,
                                onInput = { input = it },
                                onSubmit = ::submit,
                                context = context,
                                prepare = engine::prepare,
                                confirm = { token ->
                                    // Replace the card with its result so the Confirm button cannot be tapped again.
                                    val index = turns.indexOfFirst { (it.response as? NilaResponse.NeedsConfirmation)?.token == token }
                                    val result = engine.confirm(token)
                                    if (index >= 0) turns[index] = turns[index].copy(response = result) else turns += Turn("Action result", result)
                                },
                            ) { label, next -> turns += Turn(label, next) }
                        }
                        NilaScreen.Home -> Unit
                        else -> NilaScreenCatalogue(
                            screen = overlayScreen,
                            onOpen = ::open,
                            onBack = { screen = navigation.back() },
                            rememberedFact = rememberedFact,
                            onChooseDefaultHome = ::chooseDefaultHome,
                            onForgetMemory = {
                                memory.edit().remove("fact").apply()
                                rememberedFact = null
                            },
                        )
                    }
                }

                if (screen == NilaScreen.Home) {
                    NilaDock(
                        // Real Phone/Messages apps, not a custom in-launcher screen —
                        // matches what every existing launcher does, so it needs no explaining.
                        onOpenPhone = { openDialer(context, "") },
                        onOpenMessages = { openMessaging(context) },
                        onOpenApps = { open(NilaScreen.Apps) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayScaffold(onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            IconButton(onClick = onClose) {
                Icon(painterResource(LucideR.drawable.lucide_ic_arrow_left), contentDescription = "Close", tint = Paper)
            }
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun AgentSpace(
    turns: List<Turn>,
    input: String,
    isThinking: Boolean,
    modelStatus: String,
    transcribe: suspend (wav: ByteArray, languageName: String) -> String?,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    context: Context,
    prepare: (Choice) -> NilaResponse.Confirmation,
    confirm: (String) -> Unit,
    addTurn: (String, NilaResponse) -> Unit,
) {
    val lastWasConfirmation = turns.lastOrNull()?.response is NilaResponse.Confirmation
    val lastText = (turns.lastOrNull()?.response as? NilaResponse.Text)?.value.orEmpty()
    val lastWasError = !isThinking && (lastText.contains("could not", true) || lastText.contains("unavailable", true) || lastText.contains("No alarm", true))
    var celebrate by remember { mutableStateOf(false) }
    val mood = when {
        celebrate -> MascotMood.Celebrate
        isThinking -> MascotMood.Thinking
        lastWasError -> MascotMood.Error
        input.isNotBlank() -> MascotMood.Listening
        turns.isEmpty() -> MascotMood.Idle
        else -> MascotMood.Talking
    }
    val listState = rememberLazyListState()
    // Bring the newest turn into view when it is added and again when its reply lands.
    LaunchedEffect(turns.size, isThinking) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
    }
    LaunchedEffect(turns.size) {
        if (lastWasConfirmation) {
            celebrate = true
            delay(900)
            celebrate = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(Ink).padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NILA", color = Lime, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text(modelStatus, color = Muted, fontSize = 11.sp)
            }
            IconButton(onClick = { onSubmit("zepto cart") }) {
                Text("🛒", fontSize = 20.sp)
            }
            NilaMascot(mood = mood, size = 36.dp)
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (turns.isEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NilaMascot(mood = MascotMood.Idle, size = 84.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Text("What would you like to get done?", color = Paper, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    DemoPrompts(onSubmit)
                }
            }
            itemsIndexed(turns) { index, turn ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("YOU", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Text(turn.user, style = MaterialTheme.typography.titleMedium)
                    if (index == turns.lastIndex && isThinking) {
                        Text("NILA is thinking…", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        ResponseView(response = turn.response, context = context, prepare = prepare, confirm = confirm, addTurn = addTurn)
                    }
                }
            }
        }
        Surface(Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(24.dp)) {
            Row(
                Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) Text("Ask NILA anything", color = Muted)
                    BasicTextField(
                        value = input,
                        onValueChange = onInput,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Paper),
                        singleLine = true,
                        keyboardActions = KeyboardActions(onDone = { onSubmit(input) }),
                    )
                }
                VoiceMic(
                    transcribe = transcribe,
                    onFinal = onSubmit,
                    onMessage = { addTurn("Voice", NilaResponse.Text(it)) },
                )
                IconButton(onClick = { onSubmit(input) }) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_send), contentDescription = "Send", tint = Lime)
                }
            }
        }
    }
}

@Composable
private fun DemoPrompts(onSubmit: (String) -> Unit) {
    val prompts = listOf(
        "Find dinner under ₹800",
        "What is 18% of 4250?",
        "Set an alarm for 6:30 am",
        "Open YouTube",
    )
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("TRY ASKING", style = MaterialTheme.typography.labelSmall, color = Muted)
        prompts.chunked(2).forEach { rowPrompts ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPrompts.forEach { prompt ->
                    Button(
                        onClick = { onSubmit(prompt) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Paper),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2, lineHeight = 16.sp)
                    }
                }
                if (rowPrompts.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ResponseView(
    response: NilaResponse,
    context: Context,
    prepare: (Choice) -> NilaResponse.Confirmation,
    confirm: (String) -> Unit,
    addTurn: (String, NilaResponse) -> Unit,
) {
    when (response) {
        // ponytail: plain tap confirms; biometric prompt comes with the confirmation flow.
        is NilaResponse.NeedsConfirmation -> ActionCard(
            title = response.capability.description,
            detail = response.lines.joinToString("\n", postfix = "\nNothing happens until you confirm.") { (name, value) -> "$name: $value" },
            action = "Confirm",
        ) { confirm(response.token) }
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
        is NilaResponse.Confirmation -> ConfirmationCard(response) {
            addTurn(
                "Action result",
                NilaResponse.Text("Simulation complete. No order was placed and no payment or data was shared."),
            )
        }
    }
}

@Composable
private fun ProviderGroupCard(group: ProviderGroup) {
    var expanded by remember(group.providerName) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            colors = CardDefaults.cardColors(containerColor = CardInk),
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${group.providerName} — ${group.summary}", fontWeight = FontWeight.Bold)
                Text(if (expanded) "▾" else "▸", color = Lime)
            }
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (group.items.isEmpty()) {
                    Text(group.note ?: "No results.", style = MaterialTheme.typography.bodySmall, color = Muted)
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
        colors = CardDefaults.cardColors(containerColor = CardInk),
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
                    Text(item.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Button(onClick = item.onClick) { Text(item.price.ifBlank { "Select" }) }
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

