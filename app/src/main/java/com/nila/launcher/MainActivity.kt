package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.util.Log
import android.provider.Settings
import android.widget.Toast
import androidx.core.view.WindowCompat
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
        // Edge-to-edge: without this the OS resizes the window around the
        // keyboard AND Compose's imePadding() pads again on top of that,
        // double-counting the inset and leaving dead space above the keyboard.
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
    val swiggySession = remember(SignIns.revision) { SwiggyAuth.currentSession(context) }
    val zomatoSession = remember(SignIns.revision) { ZomatoAuth.currentSession(context) }
    val foodFlow = remember { FoodFlow(context.applicationContext) }

    /** Runs one Swiggy step as its own chat turn, with the same thinking state as a typed request. */
    /**
     * Swiggy steps update the booking card in place: one sheet for one intent, like the prototype's
     * temporary layer, instead of a new chat turn and a new card for every tap.
     */
    fun runFood(label: String, step: suspend () -> NilaResponse) {
        if (isThinking) return
        val index = if (turns.lastOrNull()?.response is NilaResponse.Food) turns.lastIndex else {
            turns += Turn(label, NilaResponse.Text(""))
            turns.lastIndex
        }
        isThinking = true
        scope.launch {
            turns[index] = turns[index].copy(response = step())
            isThinking = false
        }
    }

    val foodActions = remember {
        object : FoodActions {
            override fun locate(query: String) =
                runFood("Use my current location") { foodFlow.start(query) }
            override fun refine(address: FoodAddress, search: NilaResponse.FoodSearch) =
                runFood(foodIntentTitle(search)) { foodFlow.refine(address, search) }
            override fun pickAddress(address: FoodAddress, query: String) =
                runFood("Deliver to ${address.label}") { foodFlow.deliverTo(address, query) }
            override fun applyCoupon(payment: FoodCard.Payment, coupon: FoodCoupon) =
                runFood("Apply ${coupon.code}") { foodFlow.applyCoupon(payment, coupon) }
            override fun zomatoTotal(payment: FoodCard.Payment, method: String) =
                runFood("Check Zomato total") { foodFlow.zomatoTotal(payment, method) }
            override fun back(card: FoodCard) = runFood("Back") { NilaResponse.Food(card) }
            override fun lastResults() = foodFlow.lastResults
            override fun lastMenu() = foodFlow.lastMenu
            override fun lastCart() = foodFlow.lastCart
            override fun changeAddress(query: String) =
                runFood("Change address") { foodFlow.chooseAddress(query) }
            override fun addAddress(query: String, form: NewAddressForm) =
                runFood("Add a new address") { foodFlow.addAddress(query, form) }
            override fun openRestaurant(address: FoodAddress, restaurant: FoodRestaurant, query: String) =
                runFood(restaurant.name) { foodFlow.menu(address, restaurant, query) }
            override fun changeItem(address: FoodAddress, restaurant: FoodRestaurant, item: FoodItem, delta: Int) =
                runFood(if (delta > 0) "Add ${item.name}" else "Remove ${item.name}") { foodFlow.changeItem(address, restaurant, item, delta) }
            override fun changeLine(address: FoodAddress, restaurant: FoodRestaurant, itemId: String, delta: Int) =
                runFood(if (delta > 0) "One more" else "One less") { foodFlow.changeLine(address, restaurant, itemId, delta) }
            override fun checkout(address: FoodAddress, restaurant: FoodRestaurant, cart: FoodCartView) =
                runFood("Checkout") { foodFlow.payment(address, restaurant, cart) }
            override fun place(address: FoodAddress, cart: FoodCartView, option: FoodPayOption) =
                runFood("Place order · ${option.label}") {
                    foodFlow.place(address, cart, option).also { response ->
                        // Hand straight to the UPI app with the amount filled in; the card keeps a button to reopen it.
                        ((response as? NilaResponse.Food)?.card as? FoodCard.Order)?.pending?.upiLink?.let(::openUpi)
                    }
                }
            override fun openUpi(link: String) {
                // NPCI routes UPI payments through the payment app; this is the one hop NILA can't avoid.
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    .onFailure { Toast.makeText(context, "No UPI app can open this payment.", Toast.LENGTH_SHORT).show() }
            }
            override fun checkPayment(pending: PendingPayment) =
                runFood("Check payment") { foodFlow.checkPayment(pending) }
            override fun track(orderId: String?) =
                runFood("Track order") { foodFlow.track(orderId) }
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val zeptoAuth = remember { ZeptoAuth(context) }
    // Zepto requires a selected store before search works at all, so the first "buy" of a session
    // silently picks a saved address for that (no address UI shown — only "go to cart" -> proceed
    // asks). The resolved client+store is then reused for every later "buy" in this session.
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
        // The answer card needs the room: Swiggy's address step was hidden behind the keyboard.
        keyboard?.hide()
        val pendingIndex = turns.size
        turns += Turn(prompt, NilaResponse.Text(""))
        isThinking = true
        scope.launch {
            delay(550) // a visible thinking beat, so a reply doesn't feel like a canned instant lookup
            val normalized = prompt.lowercase()
            val response = if (normalized.contains("what do you remember")) {
                NilaResponse.Text(memory.getString("fact", null)?.let { "You asked me to remember: $it" } ?: "You have not asked me to remember anything yet.")
            } else if (Regex("""\b(show|read|list|open|what are)\b.*\bnotes?\b""").containsMatchIn(prompt.lowercase())) {
                NilaResponse.Text(notesSummary(context))
            } else {
                // The router picks the tool; food and directions hand off to live services, and
                // harmless, reversible actions (flashlight, camera, notes) happen straight away.
                when (val routed = brain.respond(prompt)) {
                    is NilaResponse.FoodSearch -> if (foodFlow.connected) {
                        foodFlow.start(routed)
                    } else {
                        engine.mockFood().let { it.copy(intro = "${it.intro}\nConnect Swiggy in Settings to order for real.") }
                    }
                    is NilaResponse.Navigate -> NilaIntegrations.liveRouteCard(context, routed.destination) ?: routed
                    else -> performNow(context, routed)
                }
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

    // How the agent screen was last opened: from the mascot it starts listening, from a typed hand-off it does not.
    var agentEntry by remember { mutableStateOf(AgentEntry.Text) }

    fun openAgent(entry: AgentEntry) {
        agentEntry = entry
        open(NilaScreen.Nila)
    }

    fun closeOverlay() {
        agentEntry = AgentEntry.Text
        screen = navigation.back()
    }

    BackHandler(enabled = navigation.canGoBack) { closeOverlay() }

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
            // Home draws its wallpaper edge to edge and pads its own content; each overlay applies
            // safeDrawingPadding() once below, which is also where the keyboard inset comes in.
            Box(Modifier.fillMaxSize()) {
                val lastResponse = turns.lastOrNull()?.response
                val lastText = (lastResponse as? NilaResponse.Text)?.value.orEmpty()
                val nilaMood = when {
                    isThinking -> MascotMood.Thinking
                    justReplied -> MascotMood.Talking
                    lastResponse is NilaResponse.Choices || lastResponse is NilaResponse.Confirmation -> MascotMood.Listening
                    lastText.contains("could not", true) || lastText.contains("unavailable", true) || lastText.contains("No alarm", true) -> MascotMood.Error
                    else -> MascotMood.Idle
                }
                HomeScreen(
                    resumeVersion = resumeVersion,
                    nilaMood = nilaMood,
                    onInvokeNila = { openAgent(AgentEntry.Voice) },
                    onOpenApps = { open(NilaScreen.Apps) },
                    onChangeWallpaper = { changeWallpaper(context) },
                )

                AnimatedVisibility(
                    visible = screen != NilaScreen.Home,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                ) {
                    // The empty pointerInput keeps touches on bare overlay areas from reaching Home's swipe-up below.
                    val backdrop = if (overlayScreen == NilaScreen.Nila) Modifier.drawBehind { drawAgentAtmosphere() } else Modifier
                    Box(Modifier.fillMaxSize().background(Ink).then(backdrop).pointerInput(Unit) {}.safeDrawingPadding()) {
                        when (overlayScreen) {
                            NilaScreen.Apps -> AppsSpace(
                                apps = installedApps,
                                pinnedPackages = pinnedPackages,
                                onOpenApp = { app -> launchInstalledApp(context, app.packageName) },
                                onTogglePin = { app ->
                                    val alreadyPinned = app.packageName in pinnedPackages
                                    // shortcut: eight favourites fill two drawer rows; add paging before raising the limit.
                                    if (!alreadyPinned && pinnedPackages.size >= 8) {
                                        Toast.makeText(context, "Remove a favourite before adding another.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val updated = pinnedPackages.toMutableList().apply {
                                            if (alreadyPinned) remove(app.packageName) else add(app.packageName)
                                        }
                                        pinnedPackages.clear()
                                        pinnedPackages.addAll(updated)
                                        launcherPreferences.edit().putStringSet("home_pins", updated.toSet()).apply()
                                        Toast.makeText(
                                            context,
                                            if (app.packageName in updated) "${app.label} added to favourites" else "${app.label} removed from favourites",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                },
                                onAskNila = { request ->
                                    openAgent(AgentEntry.Text)
                                    submit(request)
                                },
                                onBack = ::closeOverlay,
                                onOpenSettings = { open(NilaScreen.Settings) },
                                onChangeWallpaper = { changeWallpaper(context) },
                            )
                            NilaScreen.Nila -> AgentSpace(
                                entry = agentEntry,
                                turns = turns,
                                input = input,
                                isThinking = isThinking,
                                modelStatus = "Gemma 4 E2B ${brain.assistantState.label} · FunctionGemma ${brain.routerState.label}" +
                                    (brain.loadError?.let { "\n$it" } ?: ""),
                                transcribe = brain::transcribe,
                                onInput = { input = it },
                                onSubmit = ::submit,
                                onVoiceRequest = { speakReply = true; submit(it) },
                                onListenStart = lakshmi::stop,
                                onClose = ::closeOverlay,
                                // Only a Zepto session that has searched or added something has a cart worth opening.
                                onOpenCart = activeZeptoSession?.let { { submit("zepto cart") } },
                                context = context,
                                prepare = engine::prepare,
                                confirm = { token ->
                                    // Replace the card with its result so the Confirm button cannot be tapped again.
                                    val index = turns.indexOfFirst { (it.response as? NilaResponse.NeedsConfirmation)?.token == token }
                                    val result = engine.confirm(token).let { if (it is NilaResponse.DismissAlarm) dismissAlarm(context, it) else it }
                                    if (index >= 0) turns[index] = turns[index].copy(response = result) else turns += Turn("Action result", result)
                                },
                                food = foodActions,
                            ) { label, next -> turns += Turn(label, next) }
                            NilaScreen.Home -> Unit
                            else -> NilaScreenCatalogue(
                                screen = overlayScreen,
                                onOpen = ::open,
                                onBack = ::closeOverlay,
                                rememberedFact = rememberedFact,
                                onChooseDefaultHome = ::chooseDefaultHome,
                                onForgetMemory = {
                                    memory.edit().remove("fact").apply()
                                    rememberedFact = null
                                },
                                accounts = listOf(
                                    ServiceAccount(
                                        name = "Swiggy",
                                        detail = "Orders food · OAuth with PKCE · sign in again after 5 days",
                                        connected = swiggySession != null,
                                        onConnect = {
                                            SignIns.start(SwiggyAuth, context) {
                                                Toast.makeText(context.applicationContext, "Swiggy sign-in didn't complete.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onDisconnect = { SignIns.signOut(SwiggyAuth, context) },
                                    ),
                                    ServiceAccount(
                                        name = "Zomato",
                                        detail = "Compares your Swiggy cart with the same order on Zomato",
                                        connected = zomatoSession != null,
                                        onConnect = {
                                            SignIns.start(ZomatoAuth, context) {
                                                Toast.makeText(context.applicationContext, "Zomato sign-in didn't complete.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onDisconnect = { SignIns.signOut(ZomatoAuth, context) },
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Turns NILA adds on its own (a provider follow-up, an action's result) carry a label, not something the person said. */
private val FollowUpLabels = setOf("Zepto", "Zepto tools result", "Action result", "Prepared action")

internal fun isFollowUpTurn(label: String): Boolean = label in FollowUpLabels

private val MascotTapSize = 40.dp
private val HeaderMascotSize = 34.dp
private val WorkingMascotSize = 28.dp
private val EmptyMascotSize = 72.dp
private val FoodRequestSize = 14.sp
private val ScreenGutter = 20.dp
private val PromptMinHeight = 48.dp
private val ProductImageSize = 60.dp
private val OptionImageSize = 52.dp
private const val BUSY_CARD_ALPHA = 0.35f
private const val BOUNDARY_ALPHA = 0.35f

/** Where the agent screen was opened from: the mascot starts listening straight away, typed hand-offs do not. */
internal enum class AgentEntry { Voice, Text }

@Composable
private fun AgentSpace(
    entry: AgentEntry,
    turns: List<Turn>,
    input: String,
    isThinking: Boolean,
    modelStatus: String,
    transcribe: suspend (wav: ByteArray, languageName: String?) -> String?,
    onInput: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onVoiceRequest: (String) -> Unit,
    onListenStart: () -> Unit,
    onClose: () -> Unit,
    onOpenCart: (() -> Unit)?,
    context: Context,
    prepare: (Choice) -> NilaResponse.Confirmation,
    confirm: (String) -> Unit,
    food: FoodActions,
    addTurn: (String, NilaResponse) -> Unit,
) {
    val lastWasConfirmation = turns.lastOrNull()?.response is NilaResponse.Confirmation
    val lastText = (turns.lastOrNull()?.response as? NilaResponse.Text)?.value.orEmpty()
    val lastWasError = !isThinking && (lastText.contains("could not", true) || lastText.contains("unavailable", true) || lastText.contains("No alarm", true))
    var celebrate by remember { mutableStateOf(false) }
    // Model load status is debug info: hidden until the mascot is tapped.
    var showStatus by remember { mutableStateOf(false) }
    // What the inline listening state shows: the transcript waiting for "Use this request", or why voice failed.
    var heard by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceCapture(
        transcribe = transcribe,
        onFinal = { heard = it },
        onMessage = { notice = it },
        onListenStart = {
            heard = null
            notice = null
            onListenStart()
        },
    )
    val voiceShown = voice.state != VoiceState.IDLE || heard != null || notice != null
    fun dismissVoice() {
        voice.cancel()
        heard = null
        notice = null
    }
    LaunchedEffect(Unit) {
        if (entry == AgentEntry.Voice) voice.toggle()
    }
    val focusManager = LocalFocusManager.current
    val mood = when {
        celebrate -> MascotMood.Celebrate
        isThinking -> MascotMood.Thinking
        lastWasError -> MascotMood.Error
        voice.state == VoiceState.LISTENING || input.isNotBlank() -> MascotMood.Listening
        turns.isEmpty() -> MascotMood.Idle
        else -> MascotMood.Talking
    }
    val listState = rememberLazyListState()
    // Bring the newest turn into view when it is added, when its reply lands and when listening hands back the screen.
    LaunchedEffect(turns.size, isThinking, voiceShown) {
        if (voiceShown || turns.isEmpty()) return@LaunchedEffect
        listState.animateScrollToItem(turns.lastIndex)
    }
    LaunchedEffect(turns.size) {
        if (lastWasConfirmation) {
            celebrate = true
            delay(900)
            celebrate = false
        }
    }

    // One mascot on screen: the body shows it while listening, working or empty, the header the rest of the time.
    val bodyHasMascot = voiceShown || isThinking || turns.isEmpty()
    Column(
        Modifier.fillMaxSize().padding(horizontal = ScreenGutter, vertical = 12.dp),
    ) {
        TopAction(onBack = onClose) {
            onOpenCart?.let { RoundButton(LucideR.drawable.lucide_ic_shopping_cart, "Zepto cart", it) }
            if (!bodyHasMascot) Box(
                Modifier.size(MascotTapSize).clip(CircleShape)
                    .clickable(onClickLabel = if (showStatus) "Hide model status" else "Show model status") { showStatus = !showStatus },
                contentAlignment = Alignment.Center,
            ) { NilaMascot(mood = mood, size = HeaderMascotSize) }
        }
        AnimatedVisibility(visible = showStatus && !bodyHasMascot) {
            Text(
                modelStatus,
                color = Muted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }
        if (voiceShown) {
            ListeningTurn(voice = voice, heard = heard, notice = notice, modifier = Modifier.weight(1f)) { request ->
                heard = null
                onVoiceRequest(request)
            }
        } else LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (turns.isEmpty()) item { EmptyAgent(onSubmit) }
            itemsIndexed(turns) { index, turn ->
                val busy = index == turns.lastIndex && isThinking
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TurnHeading(turn)
                    when {
                        busy && turn.response is NilaResponse.Food -> {
                            // Keep the booking card on screen while Swiggy answers, faded so taps wait.
                            WorkingOnIt()
                            Box(Modifier.alpha(BUSY_CARD_ALPHA)) {
                                ResponseView(response = turn.response, context = context, prepare = prepare, confirm = confirm, food = food, addTurn = addTurn)
                            }
                        }
                        busy -> WorkingOnIt()
                        else -> ResponseView(response = turn.response, context = context, prepare = prepare, confirm = confirm, food = food, addTurn = addTurn)
                    }
                }
            }
        }
        Surface(
            Modifier.fillMaxWidth(),
            color = Panel,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, Stroke),
        ) {
            Row(
                Modifier.padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) Text("Ask NILA anything", color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(
                        value = input,
                        onValueChange = {
                            dismissVoice()
                            onInput(it)
                        },
                        // Reaching for the keyboard means typing instead, so any listening stops.
                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) dismissVoice() },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Paper),
                        cursorBrush = SolidColor(Lime),
                        singleLine = true,
                        keyboardActions = KeyboardActions(onDone = { onSubmit(input) }),
                    )
                }
                VoiceMic(voice) {
                    // Drop the keyboard so the listening state has room; tapping the field again cancels listening.
                    focusManager.clearFocus()
                    voice.toggle()
                }
                IconButton(onClick = { onSubmit(input) }) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_send), contentDescription = "Send", tint = Lime)
                }
            }
        }
    }
}

/** What the person asked, as the layer's serif headline; NILA's own follow-ups get a small label instead. */
@Composable
private fun TurnHeading(turn: Turn) {
    when {
        isFollowUpTurn(turn.user) -> SectionLabel(turn.user)
        // The booking sheet brings its own serif title, so the request is only a quiet echo above it.
        turn.response is NilaResponse.Food -> Text(turn.user, color = Muted, fontSize = FoodRequestSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else -> RequestHeadline(turn.user)
    }
}

@Composable
private fun RequestHeadline(request: String) {
    val key = requestKeyPhrase(request)
    val styled = buildAnnotatedString {
        if (key == null) append(request) else {
            append(request.substring(0, key.first))
            withStyle(SpanStyle(color = Lime)) { append(request.substring(key)) }
            append(request.substring(key.last + 1))
        }
    }
    Text(styled, color = Paper, fontFamily = FontFamily.Serif, fontSize = HeadlineSize, lineHeight = HeadlineSize * 1.12f)
}

@Composable
private fun WorkingOnIt() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NilaMascot(mood = MascotMood.Thinking, size = WorkingMascotSize)
        SectionLabel("Working on it…", color = Lime)
    }
}

@Composable
private fun EmptyAgent(onSubmit: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 24.dp)) {
        NilaMascot(mood = MascotMood.Idle, size = EmptyMascotSize)
        Text("Say it naturally, or type it", color = Muted, fontSize = 12.sp)
        Text(
            buildAnnotatedString {
                append("What would you like to ")
                withStyle(SpanStyle(color = Lime)) { append("get done") }
                append("?")
            },
            color = Paper,
            fontFamily = FontFamily.Serif,
            fontSize = 36.sp,
            lineHeight = 39.sp,
        )
        DemoPrompts(onSubmit)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        SectionLabel("Try asking")
        prompts.forEach { prompt ->
            Box(
                Modifier.heightIn(min = PromptMinHeight).clip(CircleShape).background(Panel).border(1.dp, Stroke, CircleShape)
                    .clickable { onSubmit(prompt) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("“$prompt”", color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun openGoogleMaps(context: Context, destination: String, addTurn: (String, NilaResponse) -> Unit) {
    val navUri = Uri.parse("google.navigation:q=${Uri.encode(destination)}")
    val mapsIntent = Intent(Intent.ACTION_VIEW, navUri).setPackage("com.google.android.apps.maps")
    val opened = runCatching { context.startActivity(mapsIntent) }.isSuccess
    if (!opened) {
        val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, webUri)) }
            .onFailure { addTurn("Action result", NilaResponse.Text("Maps could not be opened.")) }
    }
}

@Composable
private fun ResponseView(
    response: NilaResponse,
    context: Context,
    prepare: (Choice) -> NilaResponse.Confirmation,
    confirm: (String) -> Unit,
    food: FoodActions,
    addTurn: (String, NilaResponse) -> Unit,
) {
    when (response) {
        is NilaResponse.Food -> FoodCardView(response.card, food)
        // Resolved into the live flow in submit(); shown only if that ever fails to happen.
        is NilaResponse.FoodSearch -> AnswerCard("Looking for ${response.query}…")
        // Confirmation is this card only: the iQOO 15 loan devices have fingerprint locked for the hackathon.
        is NilaResponse.NeedsConfirmation -> CommitCard(response) { confirm(response.token) }
        is NilaResponse.Text -> AnswerCard(response.value)
        is NilaResponse.Remember -> NilaCard {
            SectionLabel("Remembered", color = Lime)
            Text("I'll remember: ${response.fact}", color = Paper, fontSize = 15.sp, lineHeight = 22.sp)
        }
        // Only reached if a dismissal was not confirmed through the card; it is never sent without confirmation.
        is NilaResponse.DismissAlarm -> AnswerCard("This alarm was not turned off.")
        // Torch, Camera and Note are done in performNow before they reach the chat.
        is Torch, is Camera, is Note -> AnswerCard("Done.")
        is Timer -> ActionCard(
            label = "Timer",
            title = "%d:%02d".format(response.seconds / 60, response.seconds % 60),
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
            label = "Calendar",
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
            label = "Alarm",
            title = "%02d:%02d".format(response.hour, response.minute),
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
            label = "Open app",
            title = response.name,
            detail = "This experience is better in its full interface.",
            action = "Open ${response.name}",
        ) {
            val launch = context.packageManager.getLaunchIntentForPackage(response.packageName)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse(response.fallbackUrl))
            runCatching { context.startActivity(launch) }
                .onFailure { addTurn("Action result", NilaResponse.Text("${response.name} could not be opened.")) }
        }
        is NilaResponse.Navigate -> ActionCard(
            label = "Directions",
            title = response.destination,
            detail = "Opens Google Maps for live turn-by-turn directions.",
            action = "Open Maps",
        ) { openGoogleMaps(context, response.destination, addTurn) }
        is NilaResponse.RouteCard -> NilaCard {
            SourceLabel(response.source)
            SerifTitle(response.destination)
            Text("%.1f km · about %.0f min by road".format(response.distanceKm, response.durationMin), color = Paper, fontSize = 14.sp)
            Text("© OpenStreetMap contributors", color = Muted, fontSize = 10.sp)
            PrimaryAction("Open Maps for navigation", { openGoogleMaps(context, response.destination, addTurn) }, Modifier.padding(top = 4.dp))
        }
        is NilaResponse.Choices -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceLabel(response.source, color = if (response.source.startsWith("LIVE")) Lime else Orange)
            Text(response.intro, color = Paper, fontSize = 15.sp, lineHeight = 22.sp)
            NilaCard {
                response.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Panel)
                    val review = rememberSingleTap { addTurn("Prepared action", prepare(item)) }
                    OptionRow(item.title, "${item.detail} · ${item.eta}", imageUrl = null) {
                        Column(horizontalAlignment = Alignment.End) {
                            SerifTitle(item.price, size = 18.sp)
                            TextAction("Review ›", review::fire, color = if (review.used) Muted else Lime)
                        }
                    }
                }
            }
        }
        is NilaResponse.AsyncChoices -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SerifTitle(response.intro)
            val only = response.items.singleOrNull()
            if (only != null) SingleChoiceCard(only) else NilaCard {
                response.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = Panel)
                    val pick = rememberSingleTap(item.onClick)
                    OptionRow(item.title, withEta(item.detail, item.eta), item.imageUrl) {
                        TextAction("${item.price.ifBlank { "Select" }} ›", pick::fire, color = if (pick.used) Muted else Lime)
                    }
                }
            }
        }
        is NilaResponse.GroupedChoices -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SerifTitle(response.intro)
            response.groups.forEach { group -> ProviderGroupCard(group) }
        }
        is NilaResponse.ProductGroupChoices -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SerifTitle(response.intro)
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

private fun withEta(detail: String, eta: String) = if (eta.isNotBlank()) "$detail · $eta" else detail

/** NILA's plain answer: calm text on a card, nothing to tap. */
@Composable
private fun AnswerCard(text: String) {
    if (text.isBlank()) return
    NilaCard { Text(text, color = Paper, fontSize = 15.sp, lineHeight = 22.sp) }
}

/** The line every Commit card ends on, set apart like the prototype's "NILA will stop at this boundary". */
@Composable
private fun BoundaryNote(text: String) {
    Surface(color = Ink.copy(alpha = BOUNDARY_ALPHA), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp, modifier = Modifier.padding(12.dp))
    }
}

/** A validated Commit call: shows exactly what Confirm authorizes; the token is spent by the first tap. */
@Composable
private fun CommitCard(response: NilaResponse.NeedsConfirmation, onConfirm: () -> Unit) {
    val tap = rememberSingleTap(onConfirm)
    NilaCard(color = CommitInk) {
        SectionLabel("Confirmation required", color = Orange)
        SerifTitle(response.capability.label)
        response.lines.forEach { (name, value) -> DetailRow(name, value) }
        BoundaryNote("Nothing happens until you confirm.")
        PrimaryAction("Confirm", tap::fire, enabled = !tap.used)
    }
}

/** One option in a list: image-led when there is an image, with its action on the right. */
@Composable
private fun OptionRow(title: String, detail: String, imageUrl: String?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (imageUrl != null) {
            coil.compose.AsyncImage(
                model = imageUrl,
                contentDescription = title,
                modifier = Modifier.size(OptionImageSize).clip(RoundedCornerShape(14.dp)).background(Panel),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(end = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        trailing()
    }
}

/** A single next step (place this order, connect this account): the one primary action of its card. */
@Composable
private fun SingleChoiceCard(item: AsyncChoice) {
    val tap = rememberSingleTap(item.onClick)
    NilaCard {
        if (item.price.isNotBlank()) Text(item.title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        val detail = withEta(item.detail, item.eta)
        if (detail.isNotBlank()) Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        PrimaryAction(item.price.ifBlank { item.title }, tap::fire, Modifier.padding(top = 4.dp), enabled = !tap.used)
    }
}

/** A provider's name and summary as a section label, like "ZEPTO · FROM ₹42.00"; tapping it folds the group. */
@Composable
private fun ProviderHeader(name: String, summary: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = if (expanded) "Collapse $name" else "Expand $name", onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("$name · $summary", Modifier.weight(1f), color = Paper)
        Icon(
            painterResource(if (expanded) LucideR.drawable.lucide_ic_chevron_down else LucideR.drawable.lucide_ic_chevron_right),
            contentDescription = null,
            tint = Lime,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun GroupNote(note: String?) = Text(note ?: "No results.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)

@Composable
private fun ProviderGroupCard(group: ProviderGroup) {
    var expanded by remember(group.providerName) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProviderHeader(group.providerName, group.summary, expanded = expanded) { expanded = !expanded }
        if (expanded) NilaCard {
            if (group.items.isEmpty()) GroupNote(group.note)
            group.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = Panel)
                OptionRow(item.title, item.detail, item.imageUrl) {
                    TextAction("${item.price.ifBlank { "Select" }} ›", item.onClick)
                }
            }
        }
    }
}

@Composable
private fun ProductProviderGroupCard(group: ProductProviderGroup) {
    var expanded by remember(group.providerName) { mutableStateOf(group.items.isNotEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProviderHeader(group.providerName, group.summary, expanded = expanded) { expanded = !expanded }
        if (expanded) NilaCard {
            if (group.items.isEmpty()) GroupNote(group.note)
            group.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = Panel)
                QuantityProductRow(item)
            }
        }
    }
}

/** Image-led product row with the price up front and the same stepper as the Swiggy cart. */
@Composable
private fun QuantityProductRow(item: PickableProduct) {
    var quantity by remember(item.title) { mutableStateOf(item.initialQuantity) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val imageModifier = Modifier.size(ProductImageSize).clip(RoundedCornerShape(14.dp)).background(Panel)
        if (item.imageUrl != null) {
            coil.compose.AsyncImage(
                model = item.imageUrl,
                contentDescription = item.title,
                modifier = imageModifier,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Box(imageModifier, contentAlignment = Alignment.Center) {
                Icon(painterResource(LucideR.drawable.lucide_ic_shopping_basket), contentDescription = null, tint = Muted, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, color = Paper, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.detail.isNotBlank()) Text(item.detail, color = Muted, fontSize = 12.sp)
            Text(item.price, color = Paper, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        if (quantity <= 0) {
            Text(
                "ADD",
                color = Lime,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                modifier = Modifier.border(1.dp, Lime.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable(onClickLabel = "Add ${item.title}") { quantity = 1; item.onQuantityChange(1) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        } else {
            QuantityStepper(
                quantity = quantity,
                onLess = { val next = quantity - 1; quantity = next; item.onQuantityChange(next) },
                onMore = { quantity += 1; item.onQuantityChange(quantity) },
            )
        }
    }
}

/** The labelled mock food flow's prepared order: the prototype's confirm sheet, still a simulation. */
@Composable
private fun ConfirmationCard(response: NilaResponse.Confirmation, onConfirm: () -> Unit) {
    NilaCard(color = CommitInk) {
        SectionLabel("Prepared · not ordered", color = Orange)
        SerifTitle(response.title)
        Text(response.detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total", color = Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            SerifTitle(response.price, size = 26.sp)
        }
        BoundaryNote("Demo only. This stops before a real provider, order or payment.")
        PrimaryAction("Confirm simulation", onConfirm)
    }
}

/** One action card: what it is, the serif title, a calm line of detail and one lime button that works once. */
@Composable
private fun ActionCard(label: String, title: String, detail: String, action: String, onClick: () -> Unit) {
    val tap = rememberSingleTap(onClick)
    NilaCard {
        SectionLabel(label)
        SerifTitle(title)
        Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        PrimaryAction(action, tap::fire, Modifier.padding(top = 4.dp), enabled = !tap.used)
    }
}

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
    else -> response
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
    ActionCard(label = "Call", title = who, detail = "Opens the dialer with the number. You press call.", action = "Call $who") {
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

