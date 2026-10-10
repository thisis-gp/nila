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
import androidx.compose.runtime.DisposableEffect
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
private data class ActiveZeptoSession(val client: ZeptoMcpClient, val addressId: String, val addressLabel: String)

/** Builds the quantity-stepper turn for a Zepto search result. Prepare-tier only: setCartQuantity never places or pays for an order. */
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
    val pickable = cheapest.map { product ->
        PickableProduct(
            title = product.name,
            detail = product.packSize,
            price = "₹%.2f".format(product.priceInPaisa / 100.0),
            imageUrl = product.imageUrl,
        ) { quantity ->
            coroutineScope.launch(Dispatchers.IO) {
                runCatching { client.setCartQuantity(deviceId, product.productVariantId, product.storeProductId, product.name, quantity) }
                    .onFailure { withContext(Dispatchers.Main) { appendTurn(Turn("Zepto", NilaResponse.Text("Could not update cart: ${it.message}"))) } }
            }
        }
    }
    val zeptoSummary = cheapest.minOfOrNull { it.priceInPaisa }?.let { "from ₹%.2f".format(it / 100.0) } ?: "no results"
    val zeptoGroup = ProductProviderGroup("Zepto", zeptoSummary, pickable, if (pickable.isEmpty()) "No Zepto results for \"$item\"." else null)
    val groups = listOf(zeptoGroup, swiggyPendingProductGroup(ShoppingCategory.GROCERY))
    return Turn("Zepto", NilaResponse.ProductGroupChoices("Options for \"$item\" — tap + to add:", groups))
}

/**
 * Real Cash-on-Delivery placement: shows Zepto's own preview first, places the order only after an
 * explicit tap on that preview. COD only — online payment, wallet and UPI reserve-pay all move money
 * through a gateway and stay unwired (see ZeptoMcpClient). Never opens the Zepto app: the order is
 * placed for real right here, so there is nothing left to hand off for.
 */
private fun startCodCheckout(
    client: ZeptoMcpClient,
    addressId: String,
    addressLabel: String,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    appendTurn: (Turn) -> Unit,
) {
    coroutineScope.launch(Dispatchers.IO) {
        val outcome = runCatching { client.previewCodOrder(addressId) }
        withContext(Dispatchers.Main) {
            outcome.fold(
                onSuccess = { preview ->
                    appendTurn(Turn("Zepto", NilaResponse.Text("Delivering to $addressLabel.\n\n$preview")))
                    val place = AsyncChoice("Place order (Cash on Delivery)", "This charges nothing now — you pay the rider on delivery", "", "") {
                        coroutineScope.launch(Dispatchers.IO) {
                            val placed = runCatching { client.placeCodOrder(addressId) }
                            withContext(Dispatchers.Main) {
                                placed.fold(
                                    onSuccess = { result -> appendTurn(Turn("Zepto", NilaResponse.Text(result))) },
                                    onFailure = { appendTurn(Turn("Zepto", NilaResponse.Text("Order failed: ${it.message}"))) },
                                )
                            }
                        }
                    }
                    appendTurn(Turn("Zepto", NilaResponse.AsyncChoices("Confirm to place the order", listOf(place))))
                },
                onFailure = { appendTurn(Turn("Zepto", NilaResponse.Text("Could not preview the order: ${it.message}"))) },
            )
        }
    }
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
    // Lakshmi speaks replies to spoken requests; typed requests stay silent.
    val lakshmi = remember { Lakshmi(context) }
    DisposableEffect(lakshmi) { onDispose { lakshmi.close() } }
    var speakReply by remember { mutableStateOf(false) }
    LaunchedEffect(brain) { brain.load() }
    // Free both native models when this screen goes away, so recreating it never leaves old engines open.
    DisposableEffect(brain) { onDispose { brain.close() } }
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
    // Zepto requires a selected store before search works at all, so the first "buy" of a session
    // silently picks a saved address for that (no address UI shown — only "go to cart" -> proceed
    // asks). The resolved client+store is then reused for every later "buy" in this session.
    var activeZeptoSession by remember { mutableStateOf<ActiveZeptoSession?>(null) }
    val tavilyAuth = remember { TavilyAuth(context) }

    fun submit(prompt: String = input) {
        if (prompt.isBlank() || isThinking) return
        if (prompt.trim().startsWith("tvly-")) {
            // Never render the raw key: it would sit in the chat scrollback (and any screenshot) for
            // the rest of the session. Only the redacted turn and a generic status reach the UI.
            tavilyAuth.apiKey = prompt.trim()
            turns += Turn("Web search key", NilaResponse.Text("Web search connected. Try \"search for <something>\"."))
            input = ""
            return
        }
        if (prompt.lowercase().contains("connect web search")) {
            turns += Turn(
                prompt,
                NilaResponse.Text(
                    "Get a free key at tavily.com (sign up, no card needed), copy the key starting with \"tvly-\", and paste it here as your next message.",
                ),
            )
            input = ""
            return
        }
        extractWebSearchQuery(prompt)?.let { query ->
            input = ""
            if (!tavilyAuth.isConnected) {
                turns += Turn(prompt, NilaResponse.Text("Web search isn't connected yet. Say \"connect web search\" to set it up."))
                return
            }
            turns += Turn(prompt, NilaResponse.Text("Searching the web for \"$query\"..."))
            scope.launch(Dispatchers.IO) {
                val outcome = runCatching { TavilySearch.search(tavilyAuth.apiKey!!, query) }
                withContext(Dispatchers.Main) {
                    outcome.fold(
                        onSuccess = { answer ->
                            answer.summary?.let { turns += Turn("Web search", NilaResponse.Text(it)) }
                            if (answer.items.isEmpty()) {
                                if (answer.summary == null) turns += Turn("Web search", NilaResponse.Text("No results for \"$query\"."))
                            } else {
                                val choices = answer.items.map { result ->
                                    AsyncChoice(result.title, result.snippet.take(140), "", "") {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url))) }
                                    }
                                }
                                turns += Turn("Web search", NilaResponse.AsyncChoices("Sources:", choices))
                            }
                        },
                        onFailure = { turns += Turn("Web search", NilaResponse.Text("Web search error: ${it.message}")) },
                    )
                }
            }
            return
        }
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
        if (prompt.lowercase().contains("zepto cart") || prompt.lowercase().contains("go to cart") ||
            prompt.lowercase().contains("buy now") || prompt.lowercase().contains("checkout")
        ) {
            input = ""
            if (!zeptoAuth.isConnected) {
                turns += Turn(prompt, NilaResponse.GroupedChoices("Options:", listOf(zeptoNotConnectedGroup(zeptoAuth, context, scope) { t -> turns += t })))
                return
            }
            val client = activeZeptoSession?.client ?: ZeptoMcpClient(zeptoAuth)
            turns += Turn(prompt, NilaResponse.Text("Checking your Zepto cart..."))
            scope.launch(Dispatchers.IO) {
                val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "nila-device"
                val outcome = runCatching { client.viewCartItems() }
                withContext(Dispatchers.Main) {
                    outcome.fold(
                        onSuccess = { items ->
                            if (items.isEmpty()) {
                                turns += Turn("Zepto", NilaResponse.Text("Your Zepto cart is empty. Say \"buy <item>\" to add something first."))
                                return@fold
                            }
                            val total = items.sumOf { it.priceInPaisa.toLong() * it.quantity }
                            val pickable = items.map { cartItem ->
                                PickableProduct(
                                    title = cartItem.name,
                                    detail = "₹%.2f each".format(cartItem.priceInPaisa / 100.0),
                                    price = "₹%.2f".format(cartItem.priceInPaisa * cartItem.quantity / 100.0),
                                    imageUrl = cartItem.imageUrl,
                                    initialQuantity = cartItem.quantity,
                                ) { quantity ->
                                    scope.launch(Dispatchers.IO) {
                                        runCatching { client.setCartQuantity(deviceId, cartItem.productVariantId, cartItem.storeProductId, cartItem.name, quantity) }
                                    }
                                }
                            }
                            // The cart's items were searched/added under activeZeptoSession's store (a saved
                            // address silently picked on the first "buy"). Zepto is hyperlocal — a different
                            // store's catalog can have different stock/prices — so checkout must CONFIRM that
                            // same address, not offer a fresh pick that would swap the store under the cart.
                            val proceed = AsyncChoice("Proceed to checkout", "Confirm delivery address and total", "", "") {
                                val session = activeZeptoSession
                                if (session != null) {
                                    startCodCheckout(client, session.addressId, session.addressLabel, scope) { t -> turns += t }
                                } else {
                                    // Cart was viewed cold (no "buy" this session) — we don't know the store yet.
                                    // Let the user pick one, then re-check the cart under it: items may differ.
                                    scope.launch(Dispatchers.IO) {
                                        val addresses = runCatching { client.listSavedAddresses() }.getOrElse { emptyList() }
                                        withContext(Dispatchers.Main) {
                                            if (addresses.isEmpty()) {
                                                turns += Turn("Zepto", NilaResponse.Text("No saved delivery address — add one in the Zepto app, then proceed again."))
                                            } else {
                                                val addressChoices = addresses.map { address ->
                                                    AsyncChoice(address.label.ifBlank { "Saved address" }, address.addressLine, "", "") {
                                                        scope.launch(Dispatchers.IO) {
                                                            val outcome2 = runCatching {
                                                                client.selectSavedAddress(address.id)
                                                                client.viewCartItems()
                                                            }
                                                            withContext(Dispatchers.Main) {
                                                                activeZeptoSession = ActiveZeptoSession(client, address.id, address.label.ifBlank { "Saved address" })
                                                                outcome2.fold(
                                                                    onSuccess = { freshItems ->
                                                                        if (freshItems.isEmpty()) {
                                                                            turns += Turn("Zepto", NilaResponse.Text("None of the cart items are available for delivery to ${address.label.ifBlank { "this address" }}. Try adding items again from here."))
                                                                        } else {
                                                                            startCodCheckout(client, address.id, address.label.ifBlank { "this address" }, scope) { t -> turns += t }
                                                                        }
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
                                }
                            }
                            turns += Turn(
                                "Zepto",
                                NilaResponse.ProductGroupChoices(
                                    "Your cart — tap +/− to edit, total ₹%.2f".format(total / 100.0),
                                    listOf(ProductProviderGroup("Zepto cart", "${items.size} item${if (items.size == 1) "" else "s"}", pickable)),
                                ),
                            )
                            turns += Turn("Zepto", NilaResponse.AsyncChoices("Ready to order?", listOf(proceed)))
                        },
                        onFailure = { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${it.message}")) },
                    )
                }
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
                    turns += Turn(prompt, NilaResponse.Text("Searching Zepto for \"$item\"..."))
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
                // First "buy" of this session: silently pick a saved address for store context (Zepto's own
                // API rejects search without one) so nothing is asked here — only "go to cart" -> proceed asks.
                turns += Turn(prompt, NilaResponse.Text("Searching Zepto for \"$item\"..."))
                scope.launch(Dispatchers.IO) {
                    val client = ZeptoMcpClient(zeptoAuth)
                    val outcome = runCatching {
                        val defaultAddress = client.listSavedAddresses().firstOrNull()
                            ?: error("No saved Zepto address yet — add one in the Zepto app, then ask again.")
                        client.selectSavedAddress(defaultAddress.id)
                        val parsed = parseOrderItem(item)
                        Triple(client.searchProducts(parsed.searchQuery), parsed.desiredSize, defaultAddress)
                    }
                    withContext(Dispatchers.Main) {
                        outcome.fold(
                            onSuccess = { (products, desiredSize, defaultAddress) ->
                                activeZeptoSession = ActiveZeptoSession(client, defaultAddress.id, defaultAddress.label.ifBlank { "Saved address" })
                                turns += zeptoProductTurn(item, products, client, context, scope, { t -> turns += t }, desiredSize)
                            },
                            onFailure = { turns += Turn("Zepto", NilaResponse.Text("Zepto error: ${it.message}")) },
                        )
                    }
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
            } else if (Regex("""\b(show|read|list|open|what are)\b.*\bnotes?\b""").containsMatchIn(prompt.lowercase())) {
                NilaResponse.Text(notesSummary(context))
            } else {
                // Harmless, reversible actions (flashlight, camera, notes) happen straight away.
                performNow(context, brain.respond(prompt))
            }
            if (response is NilaResponse.Remember) {
                memory.edit().putString("fact", response.fact).apply()
                rememberedFact = response.fact
            }
            turns[pendingIndex] = Turn(prompt, response)
            if (speakReply) spokenText(response)?.let(lakshmi::speak)
            speakReply = false
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
                                onVoiceRequest = { speakReply = true; submit(it) },
                                onListenStart = lakshmi::stop,
                                context = context,
                                prepare = engine::prepare,
                                confirm = { token ->
                                    // Replace the card with its result so the Confirm button cannot be tapped again.
                                    val index = turns.indexOfFirst { (it.response as? NilaResponse.NeedsConfirmation)?.token == token }
                                    val result = engine.confirm(token).let { if (it is NilaResponse.DismissAlarm) dismissAlarm(context, it) else it }
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
    transcribe: suspend (wav: ByteArray, languageName: String?) -> String?,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onVoiceRequest: (String) -> Unit,
    onListenStart: () -> Unit,
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
                    onFinal = onVoiceRequest,
                    onListenStart = onListenStart,
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
        // Confirmation is this card only: the iQOO 15 loan devices have fingerprint locked for the hackathon.
        is NilaResponse.NeedsConfirmation -> ActionCard(
            title = response.capability.label,
            detail = response.lines.joinToString("\n", postfix = "\nNothing happens until you confirm.") { (name, value) -> "$name: $value" },
            action = "Confirm",
        ) { confirm(response.token) }
        is NilaResponse.Text -> Text(response.value)
        is NilaResponse.Remember -> Text("I'll remember: ${response.fact}")
        // Only reached if a dismissal was not confirmed through the card; it is never sent without confirmation.
        is NilaResponse.DismissAlarm -> Text("This alarm was not turned off.")
        // Torch, Camera and Note are done in performNow before they reach the chat.
        is Torch, is Camera, is Note, is Directions, is MapSearch, is WebSearch, is PlayMusic, is MediaKey, is SoundChange,
        is SettingsPage, is LaunchApp, ShowAlarms, BatteryLevel, ShowNotes -> Text("Done.")
        is Message -> MessageCard(response, context) { addTurn("Action result", NilaResponse.Text(it)) }
        is Timer -> ActionCard(
            title = "Timer %d:%02d".format(response.seconds / 60, response.seconds % 60),
            detail = "Ready to ask the Clock app to start this timer.",
            action = "Start timer",
        ) {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, response.seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            runCatching { context.startActivity(intent) }
                .onSuccess { addTurn("Action result", NilaResponse.Text("Timer request sent to the Clock app.")) }
                .onFailure { addTurn("Action result", NilaResponse.Text("No timer app is available.")) }
        }
        is CalendarEvent -> ActionCard(
            title = response.title,
            detail = eventWhen(response.date, response.time) + "\nOpens your calendar to save it.",
            action = "Add to calendar",
        ) {
            val zone = java.time.ZoneId.systemDefault()
            val start = (response.time?.let { response.date.atTime(it) } ?: response.date.atStartOfDay()).atZone(zone).toInstant().toEpochMilli()
            val intent = Intent(Intent.ACTION_INSERT)
                .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.Events.TITLE, response.title)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, response.time == null)
            runCatching { context.startActivity(intent) }
                .onFailure { addTurn("Action result", NilaResponse.Text("No calendar app is available.")) }
        }
        is Call -> CallCard(response.who, context) { addTurn("Action result", NilaResponse.Text(it)) }
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
        is NilaResponse.ProductGroupChoices -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(response.intro)
            response.groups.forEach { group -> ProductProviderGroupCard(group) }
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
private fun ProductProviderGroupCard(group: ProductProviderGroup) {
    var expanded by remember(group.providerName) { mutableStateOf(group.items.isNotEmpty()) }
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
                    group.items.forEach { item -> QuantityProductCard(item) }
                }
            }
        }
    }
}

@Composable
private fun QuantityProductCard(item: PickableProduct) {
    var quantity by remember(item.title) { mutableStateOf(item.initialQuantity) }
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
                    Text(item.price, style = MaterialTheme.typography.bodySmall, color = Lime)
                }
            }
            if (quantity <= 0) {
                Button(onClick = { quantity = 1; item.onQuantityChange(1) }) { Text("Add") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { val next = quantity - 1; quantity = next; item.onQuantityChange(next) }) { Text("−", fontWeight = FontWeight.Bold, color = Lime) }
                    Text("$quantity", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { quantity += 1; item.onQuantityChange(quantity) }) { Text("+", fontWeight = FontWeight.Bold, color = Lime) }
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
    // A tap can start a real, non-idempotent call (place a COD order, select an address, start OAuth).
    // tapOnce is checked and set inside the click handler itself, so even two taps in the same frame
    // can't both get through before recomposition would otherwise disable the button.
    val consumed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var disabled by remember { mutableStateOf(false) }
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
            Button(onClick = { if (tapOnce(consumed)) { disabled = true; onClick() } }, enabled = !disabled) { Text(action) }
        }
    }
}

/** True on the first call, false on every call after — even two calls racing from the same frame. Guards a tap from firing a non-idempotent action (an order, a payment) twice. */
internal fun tapOnce(consumed: java.util.concurrent.atomic.AtomicBoolean): Boolean = !consumed.getAndSet(true)

/**
 * Asks the Clock app to turn off an alarm after the user confirmed. Android does not let other apps read
 * or delete alarms directly, so this is a request: the Clock app may show its own screen to pick one.
 */
private fun dismissAlarm(context: Context, alarm: NilaResponse.DismissAlarm): NilaResponse.Text {
    val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
        if (alarm.hour == null) {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
        } else {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
            putExtra(AlarmClock.EXTRA_HOUR, alarm.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, alarm.minute)
        }
    }
    val which = alarm.hour?.let { "the %02d:%02d alarm".format(it, alarm.minute) } ?: "your next alarm"
    return runCatching { context.startActivity(intent) }.fold(
        onSuccess = { NilaResponse.Text("Asked the Clock app to turn off $which. Check Clock if it asks you to pick one.") },
        onFailure = { NilaResponse.Text("The Clock app on this phone can't turn off alarms for NILA. Nothing was changed.") },
    )
}

/** Does harmless, reversible actions straight away and reports what happened; everything else is returned as is. */
private fun performNow(context: Context, response: NilaResponse): NilaResponse = when (response) {
    is Torch -> NilaResponse.Text(setTorch(context, response.on))
    is Camera -> runCatching {
        val action = if (response.video) android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA else android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        context.startActivity(Intent(action))
    }.fold({ NilaResponse.Text(if (response.video) "Opening the camera for video." else "Opening the camera.") }, { NilaResponse.Text("No camera app is available.") })
    is Note -> NilaResponse.Text(saveNote(context, response.text))
    is Directions -> openUri(context, "google.navigation:q=" + Uri.encode(response.place), "Opening directions to ${response.place}.", "No maps app is available.")
    is MapSearch -> openUri(context, "geo:0,0?q=" + Uri.encode(response.query), "Showing ${response.query} nearby in Maps.", "No maps app is available.")
    is WebSearch -> start(context, Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, response.query), "Searching the web for ${response.query}.", "No browser is available.")
    is PlayMusic -> if (response.query == null) NilaResponse.Text(pressMediaKey(context, "play")) else start(
        context,
        Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, response.query)
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"),
        "Playing ${response.query}.",
        "No music app can play that.",
    )
    is MediaKey -> NilaResponse.Text(pressMediaKey(context, response.action))
    is SoundChange -> NilaResponse.Text(changeSound(context, response.action))
    is SettingsPage -> start(context, Intent(settingsAction(response.page)), "Opening ${if (response.page == "main") "" else response.page + " "}settings.", "That settings page isn't available.")
    is LaunchApp -> NilaResponse.Text(launchByName(context, response.name))
    ShowAlarms -> start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS), "Opening your alarms.", "No clock app is available.")
    BatteryLevel -> NilaResponse.Text(batteryStatus(context))
    ShowNotes -> NilaResponse.Text(notesSummary(context))
    else -> response
}

private fun start(context: Context, intent: Intent, done: String, failed: String): NilaResponse.Text =
    NilaResponse.Text(runCatching { context.startActivity(intent) }.fold({ done }, { failed }))

private fun openUri(context: Context, uri: String, done: String, failed: String) = start(context, Intent(Intent.ACTION_VIEW, Uri.parse(uri)), done, failed)

private fun pressMediaKey(context: Context, action: String): String {
    val code = when (action) {
        "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
        "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
    }
    val audio = context.getSystemService(android.media.AudioManager::class.java)
    audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
    audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    return when (action) {
        "pause" -> "Paused."
        "next" -> "Skipped to the next song."
        "previous" -> "Back to the previous song."
        else -> "Playing."
    }
}

private fun changeSound(context: Context, action: String): String = runCatching {
    val audio = context.getSystemService(android.media.AudioManager::class.java)
    val stream = android.media.AudioManager.STREAM_MUSIC
    when (action) {
        "up" -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI).let { "Volume up." }
        "down" -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI).let { "Volume down." }
        "mute" -> audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI).let { "Media muted." }
        "vibrate" -> { audio.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE; "Phone is on vibrate." }
        "silent" -> { audio.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT; "Phone is on silent." }
        else -> {
            audio.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
            audio.adjustStreamVolume(stream, android.media.AudioManager.ADJUST_UNMUTE, 0)
            "Sound is back on."
        }
    }
}.getOrElse {
    // Android blocks silent mode for apps without Do Not Disturb access; send the user to grant it.
    runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    "Android needs you to allow NILA Do Not Disturb access for that. I opened the setting."
}

private fun settingsAction(page: String) = when (page) {
    "wifi" -> Settings.ACTION_WIFI_SETTINGS
    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
    "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
    "display" -> Settings.ACTION_DISPLAY_SETTINGS
    "sound" -> Settings.ACTION_SOUND_SETTINGS
    "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
    "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
    // No public constant for the main notifications page; this action is what Settings itself uses.
    "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
    "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
    else -> Settings.ACTION_SETTINGS
}

/** Opens the installed app whose name best matches: exact label first, then a label containing the words. */
private fun launchByName(context: Context, name: String): String {
    val wanted = name.lowercase().trim()
    val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
        .filter { it.second != context.packageName }
    val match = apps.firstOrNull { it.first.lowercase() == wanted } ?: apps.firstOrNull { it.first.lowercase().contains(wanted) }
        ?: return "I couldn't find an app called $name on this phone."
    val launch = context.packageManager.getLaunchIntentForPackage(match.second) ?: return "${match.first} can't be opened."
    return runCatching { context.startActivity(launch) }.fold({ "Opening ${match.first}." }, { "${match.first} can't be opened." })
}

private fun batteryStatus(context: Context): String {
    val battery = context.getSystemService(android.os.BatteryManager::class.java)
    val level = battery.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    return "Battery is at $level%" + if (battery.isCharging) " and charging." else "."
}

private fun setTorch(context: Context, on: Boolean): String = runCatching {
    val cameras = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
    val withFlash = cameras.cameraIdList.first {
        cameras.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
    cameras.setTorchMode(withFlash, on)
}.fold({ if (on) "Flashlight is on." else "Flashlight is off." }, { "I couldn't switch the flashlight. The camera may be in use." })

// ponytail: notes are a JSON list in app storage; move to ObjectBox with the rest of memory when it lands.
private fun notes(context: Context): org.json.JSONArray =
    runCatching { org.json.JSONArray(context.getSharedPreferences("nila_notes", Context.MODE_PRIVATE).getString("notes", "[]")) }.getOrDefault(org.json.JSONArray())

private fun saveNote(context: Context, text: String): String {
    val all = notes(context).put(text)
    context.getSharedPreferences("nila_notes", Context.MODE_PRIVATE).edit().putString("notes", all.toString()).apply()
    return "Saved to your notes: $text"
}

private fun notesSummary(context: Context): String {
    val all = notes(context)
    if (all.length() == 0) return "You have no notes yet. Say \"take a note that…\" to add one."
    return "Your notes:\n" + (0 until all.length()).joinToString("\n") { "• ${all.getString(it)}" }
}

/** Shows who will be called and opens the dialer with the number; the user presses call there. */
@Composable
private fun CallCard(who: String, context: Context, report: (String) -> Unit) {
    fun dial(number: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) }
            .onFailure { report("No phone app is available.") }
    }
    fun dialContact() {
        val found = findContact(context, who)
        if (found == null) report("I couldn't find \"$who\" in your contacts.") else {
            report("Opening the dialer for ${found.first}. Press call to ring.")
            dial(found.second)
        }
    }
    val askContacts = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) dialContact() else report("NILA needs contacts access to find $who. You can say the number instead.") }
    ActionCard(title = "Call $who", detail = "Opens the dialer with the number. You press call.", action = "Call") {
        val number = phoneNumber(who)
        when {
            number != null -> dial(number)
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> dialContact()
            else -> askContacts.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }
}

/** First contact whose name contains [name], as (display name, number). Needs READ_CONTACTS. */
private fun findContact(context: Context, name: String): Pair<String, String>? {
    val uri =android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI
    val nameColumn = android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
    val numberColumn = android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
    return context.contentResolver.query(uri, arrayOf(nameColumn, numberColumn), "$nameColumn LIKE ?", arrayOf("%$name%"), null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) to cursor.getString(1) else null
    }
}

/** Shows who gets which words and opens Messages with them filled in; the user presses Send there. */
@Composable
private fun MessageCard(message: Message, context: Context, report: (String) -> Unit) {
    fun compose(number: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", message.body)
        runCatching { context.startActivity(intent) }.onFailure { report("No messaging app is available.") }
    }
    fun composeForContact() {
        val found = findContact(context, message.who)
        if (found == null) report("I couldn't find \"${message.who}\" in your contacts.") else {
            report("Opening Messages to ${found.first}. Press Send when it looks right.")
            compose(found.second)
        }
    }
    val askContacts = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) composeForContact() else report("NILA needs contacts access to find ${message.who}. You can say the number instead.") }
    ActionCard(title = "Message ${message.who}", detail = "\"${message.body}\"\nOpens Messages with this filled in. You press Send.", action = "Write") {
        val number = phoneNumber(message.who)
        when {
            number != null -> compose(number)
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> composeForContact()
            else -> askContacts.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }
}

