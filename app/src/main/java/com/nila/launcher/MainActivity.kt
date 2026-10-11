package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // What the user asked NILA to remember; read by chat, food, groceries and the morning suggestion.
    val memory = remember { NilaMemory(context.applicationContext) }
    val facts by memory.factsFlow.collectAsState()
    val health = remember { HealthStore(context.applicationContext) }
    LaunchedEffect(health) { withContext(Dispatchers.IO) { health.sweep() } }
    val reminders = remember { NilaReminders(context.applicationContext) }
    val brain = remember { NilaBrain(context.applicationContext, engine, { memory.facts() }, { health.records() }) }
    // Lakshmi speaks replies to spoken requests; typed requests stay silent.
    val lakshmi = remember { Lakshmi(context) }
    DisposableEffect(lakshmi) { onDispose { lakshmi.close() } }
    var speakReply by remember { mutableStateOf(false) }
    LaunchedEffect(brain) { brain.load() }
    // Free both native models when this screen goes away, so recreating it never leaves old engines open.
    DisposableEffect(brain) { onDispose { brain.close() } }
    val turns = remember { mutableStateListOf<Turn>() }
    // Any reminder, not only the monthly card's, asks for notifications the first time: a silent reminder is no reminder.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) turns += Turn("My NILA", NilaResponse.Text("Notifications are off for NILA, so reminders can't show. Turn them on in Android settings."))
    }
    fun addReminder(reminder: NilaReminder) {
        reminders.add(reminder)
        if (needsNotificationPermission(context)) askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
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
    var showWelcome by remember { mutableStateOf(!launcherPreferences.getBoolean(ONBOARDED_KEY, false)) }
    val navigation = remember { NilaNavigation(NilaScreen.Home) }
    var screen by remember { mutableStateOf(navigation.current) }
    var overlayScreen by remember { mutableStateOf(screen) }
    if (screen != NilaScreen.Home && screen != NilaScreen.MyNila) overlayScreen = screen
    // 0 = home, 1 = My NILA fully open; dragged by the finger, then settled with a spring.
    val sidePanel = remember { Animatable(0f) }
    var panelDragging by remember { mutableStateOf(false) }
    val panelWidth = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

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
    val instamart = remember { InstamartShop(context.applicationContext) }

    /** Runs one Swiggy step as its own chat turn, with the same thinking state as a typed request. */
    /**
     * Swiggy steps update the booking card in place: one sheet for one intent, like the prototype's
     * temporary layer, instead of a new chat turn and a new card for every tap.
     */
    /** Runs one step in place and says whether it ran: false while another step is still working. */
    fun tryRunFood(label: String, step: suspend () -> NilaResponse): Boolean {
        if (isThinking) return false
        val index = if (turns.lastOrNull()?.response.let { it is NilaResponse.Food || it is NilaResponse.Grocery }) turns.lastIndex else {
            turns += Turn(label, NilaResponse.Text(""))
            turns.lastIndex
        }
        isThinking = true
        scope.launch {
            turns[index] = turns[index].copy(response = step())
            isThinking = false
        }
        return true
    }

    fun runFood(label: String, step: suspend () -> NilaResponse) {
        tryRunFood(label, step)
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
            override suspend fun zomatoShop(address: FoodAddress, restaurant: FoodRestaurant) = foodFlow.zomatoShop(address, restaurant)
            override suspend fun zomatoNearby(address: FoodAddress) = foodFlow.zomatoNearby(address)
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
            override fun rememberDish(dish: String) {
                scope.launch { memory.save("I like $dish", MemoryKind.Preference) }
            }
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val zeptoAuth = remember { ZeptoAuth(context) }
    // Zepto runs as the same step-by-step sheet as Swiggy food (ZeptoCards.kt).
    val groceryFlow = remember { GroceryFlow(context.applicationContext, zeptoAuth, instamart, scope) { turns += Turn("Zepto", NilaResponse.Text(it)) } }

    val groceryActions = remember {
        object : GroceryActions {
            override fun pickAddress(address: SavedAddress, item: String?) =
                runFood("Deliver to ${address.label.ifBlank { "saved address" }}") { groceryFlow.deliverTo(address, item) }
            override fun changeAddress(item: String?) = runFood("Change address") { groceryFlow.chooseAddress(item) }
            override fun openCart() = runFood("Your carts") { groceryFlow.cart() }
            override fun changeLine(item: CartItem, quantity: Int) =
                runFood(if (quantity > item.quantity) "One more" else "One less") { groceryFlow.changeLine(item, quantity) }
            override fun review() = runFood("Review order") { groceryFlow.review() }
            override fun place(review: GroceryCard.Review) = tryRunFood("Place order") { groceryFlow.place(review) }
            override fun changeInstamartLine(item: InstamartItem, quantity: Int) =
                runFood(if (quantity > 0) "Update Instamart cart" else "Remove ${item.name}") { groceryFlow.changeInstamartLine(item, quantity) }
            override fun reviewInstamart() = runFood("Review Instamart order") { groceryFlow.reviewInstamart() }
            override fun placeInstamart(review: GroceryCard.InstamartReview) = tryRunFood("Place Instamart order") { groceryFlow.placeInstamart(review) }
            override fun back(card: GroceryCard) = runFood("Back") { groceryFlow.reload(card) }
            override fun lastProducts() = groceryFlow.lastProducts
            override fun lastCart() = groceryFlow.lastCart
        }
    }
    val tavilyAuth = remember { TavilyAuth(context) }
    // Bumped when Settings connects or disconnects an account, so its cards re-read who is signed in.
    var accountsRevision by remember { mutableIntStateOf(0) }

    /**
     * Groceries: Zepto and Swiggy Instamart side by side. Reached when the router picks search_groceries;
     * the old "buy / get me / i need" keyword triggers are gone.
     */
    fun shopGroceries(item: String) {
        if (!zeptoAuth.isConnected) {
            turns += Turn("Zepto", NilaResponse.GroupedChoices("Options for \"$item\":", listOf(zeptoNotConnectedGroup(zeptoAuth, context, scope) { t -> turns += t })))
            scope.launch {
                val parsed = parseOrderItem(item)
                val found = instamart.search(parsed.searchQuery, parsed.desiredSize)
                turns += Turn("Swiggy Instamart", NilaResponse.ProductGroupChoices("Swiggy Instamart for \"$item\":", listOf(instamart.group(item, found, scope) { turns += Turn("Swiggy Instamart", NilaResponse.Text(it)) })))
            }
            return
        }
        runFood(item) { groceryFlow.start(item) }
    }

    /**
     * Web search the router picked (web_search): Tavily when the user added a key, else DuckDuckGo's free
     * instant answer. Always ends with "open in browser" so a thin answer is never a dead end.
     */
    fun searchWeb(query: String) {
        scope.launch(Dispatchers.IO) {
            val found = runCatching { tavilyAuth.apiKey?.takeIf(String::isNotBlank)?.let { TavilySearch.search(it, query) } ?: InstantAnswer.search(query) }.getOrNull()
            val sources = found?.items.orEmpty()
            // The service's own answer when it has one, else Gemma reads the top results: always an answer, not a page list.
            val answer = found?.summary ?: brain.answerFromWeb(query, sources) ?: "I couldn't find a clear answer to that."
            withContext(Dispatchers.Main) { turns += Turn("Web search", WebAnswer(query, answer, sources)) }
        }
    }

    fun submit(prompt: String = input) {
        if (prompt.isBlank() || isThinking) return
        if (prompt.trim().startsWith("tvly-")) {
            // A key pasted into the chat is never shown or kept: keys belong in Settings, behind a hidden field.
            turns += Turn("Web search key", NilaResponse.Text("Keys are not kept in the chat. Add it in Settings → Accounts → Web search."))
            input = ""
            return
        }
        if (prompt.lowercase().contains("connect web search")) {
            input = ""
            open(NilaScreen.Settings)
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
            runFood(prompt) { groceryFlow.cart() }
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
        input = ""
        // The answer card needs the room: Swiggy's address step was hidden behind the keyboard.
        keyboard?.hide()
        val pendingIndex = turns.size
        turns += Turn(prompt, NilaResponse.Text(""))
        isThinking = true
        scope.launch {
            delay(550) // a visible thinking beat, so a reply doesn't feel like a canned instant lookup
            val normalized = prompt.lowercase()
            val response = if (asksForSteps(prompt)) {
                val today = java.time.LocalDate.now()
                val store = FitnessStore(context)
                val baselineExists = store.steps()?.day == today
                if (hasStepPermission(context)) sampleSteps(context)
                val state = store.steps()
                StepsAnswer(
                    today = if (!baselineExists || !hasStepPermission(context)) null else stepsToday(state)?.takeIf { state?.day == today },
                    week = lastDays(state, today),
                    trackingStarted = hasStepPermission(context) && !baselineExists,
                )
            } else if (normalized.contains("what do you remember")) {
                NilaResponse.Text(memorySummary(facts))
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
                    is WebSearch -> {
                        searchWeb(routed.query)
                        NilaResponse.Text("Searching the web for \"${routed.query}\"…")
                    }
                    // Connected: the Zepto sheet is this turn's answer, like Swiggy food above.
                    is NilaResponse.GrocerySearch -> if (zeptoAuth.isConnected) groceryFlow.start(routed.query) else {
                        shopGroceries(routed.query)
                        NilaResponse.Text("Comparing Zepto and Swiggy Instamart for \"${routed.query}\"…")
                    }
                    else -> performNow(context, routed)
                }
            }
            if (response is NilaResponse.Remember) memory.save(response.fact, response.kind, response.about)
            turns[pendingIndex] = Turn(prompt, response)
            if (speakReply) spokenText(response)?.let(lakshmi::speak)
            speakReply = false
            isThinking = false
            justReplied = true
            delay(900) // the dock head visibly "speaks" for a beat right after a reply lands
            justReplied = false
            // After the reply is on screen: keep any personal detail the message carried, and say so.
            val learned = if (response !is NilaResponse.Remember) brain.learn(prompt) else null
            learned?.let {
                memory.save(it.fact, it.kind, it.about)
                turns += Turn("My NILA", it)
            }
            // Someone close came up and isn't linked to a contact yet: ask once, in chat.
            val named = listOfNotNull((response as? NilaResponse.Remember)?.about, learned?.about, mentionedPerson(prompt)).filter { it != ME }.distinct()
            named.firstOrNull { memory.claimContactAsk(it) }?.let { about ->
                turns += Turn("My NILA", LinkContact(about, withContext(Dispatchers.IO) { suggestContacts(context, about) }))
            }
        }
    }

    // The agent screen always opens ready to type; listening starts only when the mic is tapped.
    fun openAgent() = open(NilaScreen.Nila)

    fun closeOverlay() {
        screen = navigation.back()
    }

    fun dragPanel(dx: Float) {
        panelDragging = true
        scope.launch { sidePanel.snapTo((sidePanel.value + dx / panelWidth).coerceIn(0f, 1f)) }
    }

    fun releasePanel(velocity: Float) {
        panelDragging = false
        val settleOpen = panelSettlesOpen(sidePanel.value, velocity)
        scope.launch { sidePanel.animateTo(if (settleOpen) 1f else 0f, PanelSpring, initialVelocity = velocity / panelWidth) }
        if (settleOpen && screen != NilaScreen.MyNila) open(NilaScreen.MyNila)
        if (!settleOpen && screen == NilaScreen.MyNila) closeOverlay()
    }

    // Opening My NILA any other way (the greeting, Settings, a chat card) slides it in too.
    LaunchedEffect(screen) {
        if (!panelDragging) sidePanel.animateTo(if (screen == NilaScreen.MyNila) 1f else 0f, PanelSpring)
    }

    BackHandler(enabled = navigation.canGoBack) { closeOverlay() }
    // "Add to My NILA" on a chat card opens My NILA's health form, prefilled.
    LaunchedEffect(MyNilaRequests.pending) { if (MyNilaRequests.pending != null) open(NilaScreen.MyNila) }

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
                Box(Modifier.fillMaxSize().graphicsLayer { translationX = sidePanel.value * size.width * HOME_PARALLAX }) {
                HomeScreen(
                    resumeVersion = resumeVersion,
                    nilaMood = nilaMood,
                    onInvokeNila = { openAgent() },
                    onOpenApps = { open(NilaScreen.Apps) },
                    onChangeWallpaper = { changeWallpaper(context) },
                    facts = facts,
                    onOpenMyNila = { open(NilaScreen.MyNila) },
                    onSuggestion = { request ->
                        openAgent()
                        submit(request)
                    },
                    onSideDrag = ::dragPanel,
                    onSideDragEnd = ::releasePanel,
                )
                }

                // My NILA slides in from the left and follows the finger, like Pixel's Discover page.
                if (sidePanel.value > 0f || screen == NilaScreen.MyNila) {
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { translationX = (sidePanel.value - 1f) * size.width }
                            .background(Ink)
                            .pointerInput(Unit) {},
                    ) {
                    MyNilaScreen(
                            facts = facts,
                            onForgetFact = { id -> scope.launch { memory.delete(id) } },
                            onEditFact = { id, text ->
                                val old = facts.firstOrNull { it.id == id }
                                if (old != null) scope.launch { memory.delete(id); memory.save(text, old.kind, old.about) }
                            },
                            onAddFact = { text, about -> scope.launch { memory.save(text, MemoryKind.Other, about) } },
                            onForgetAll = { scope.launch { memory.clear() } },
                            health = health,
                            readReport = brain::readReport,
                            // A report that asks for a follow-up becomes a My NILA reminder on that day.
                            onSaved = { record -> followUpReminder(record)?.let(reminders::add) },
                            onDeleted = { record -> reminders.remove("followup-${record.id}") },
                            draft = MyNilaRequests.pending,
                            onDraftUsed = { MyNilaRequests.pending = null },
                            onBack = ::closeOverlay,
                            onSideDrag = ::dragPanel,
                            onSideDragEnd = ::releasePanel,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = screen != NilaScreen.Home && screen != NilaScreen.MyNila,
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
                                    openAgent()
                                    submit(request)
                                },
                                onBack = ::closeOverlay,
                                onOpenSettings = { open(NilaScreen.Settings) },
                                onChangeWallpaper = { changeWallpaper(context) },
                            )
                            NilaScreen.Nila -> AgentSpace(
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
                                onOpenCart = groceryFlow.address?.let { { groceryActions.openCart() } },
                                context = context,
                                prepare = engine::prepare,
                                confirm = { token ->
                                    // Replace the card with its result so the Confirm button cannot be tapped again.
                                    val index = turns.indexOfFirst { (it.response as? NilaResponse.NeedsConfirmation)?.token == token }
                                    val result = engine.confirm(token).let { if (it is NilaResponse.DismissAlarm) dismissAlarm(context, it) else it }
                                    if (index >= 0) turns[index] = turns[index].copy(response = result) else turns += Turn("Action result", result)
                                },
                                food = foodActions,
                                suggestion = homeSuggestion(facts, java.time.LocalDateTime.now()),
                                grocery = groceryActions,
                                forget = { saved ->
                                    // Undo for an automatic save: the fact goes, and the card says so instead of offering Forget again.
                                    scope.launch { memory.forget(saved.fact, saved.about) }
                                    val index = turns.indexOfFirst { it.response === saved }
                                    if (index >= 0) turns[index] = turns[index].copy(response = NilaResponse.Text("Forgotten: ${saved.fact}"))
                                },
                            ) { label, next -> turns += Turn(label, next) }
                            NilaScreen.Home -> Unit
                            else -> NilaScreenCatalogue(
                                screen = overlayScreen,
                                onOpen = ::open,
                                onBack = ::closeOverlay,
                                onChooseDefaultHome = ::chooseDefaultHome,
                                onForgetFact = { id -> scope.launch { memory.delete(id) } },
                                onForgetAll = { scope.launch { memory.clear() } },
                                onWelcomeTour = { showWelcome = true },
                                facts = facts,
                                accounts = accountsRevision.let { listOf(
                                    ServiceAccount(
                                        name = "Swiggy",
                                        icon = LucideR.drawable.lucide_ic_utensils,
                                        detail = "Orders food for you. Sign in again every 5 days.",
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
                                        icon = LucideR.drawable.lucide_ic_utensils,
                                        detail = "Compares your Swiggy cart with the same order on Zomato.",
                                        connected = zomatoSession != null,
                                        onConnect = {
                                            SignIns.start(ZomatoAuth, context) {
                                                Toast.makeText(context.applicationContext, "Zomato sign-in didn't complete.", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onDisconnect = { SignIns.signOut(ZomatoAuth, context) },
                                    ),
                                    ServiceAccount(
                                        name = "Zepto",
                                        icon = LucideR.drawable.lucide_ic_shopping_basket,
                                        detail = "Groceries in minutes, next to Swiggy Instamart.",
                                        connected = zeptoAuth.isConnected,
                                        onConnect = {
                                            scope.launch(Dispatchers.IO) {
                                                val result = zeptoAuth.connect(context)
                                                withContext(Dispatchers.Main) {
                                                    if (result.isFailure) Toast.makeText(context.applicationContext, "Zepto sign-in didn't complete.", Toast.LENGTH_SHORT).show()
                                                    accountsRevision++
                                                }
                                            }
                                        },
                                        onDisconnect = { zeptoAuth.signOut(); accountsRevision++ },
                                    ),
                                    ServiceAccount(
                                        name = "Web search",
                                        icon = LucideR.drawable.lucide_ic_globe,
                                        detail = "Live answers from the web, through Tavily.",
                                        connected = tavilyAuth.isConnected,
                                        onConnect = {},
                                        onDisconnect = { tavilyAuth.disconnect(); accountsRevision++ },
                                        keyUrl = "https://app.tavily.com",
                                        saveKey = { pasted ->
                                            tavilyKey(pasted)?.let { tavilyAuth.apiKey = it; accountsRevision++ } != null
                                        },
                                    ),
                                ) },
                            )
                        }
                    }
                }
                if (showWelcome) {
                    // First launch, or "Welcome tour" in Settings. The flag is set when it ends, so a tour cut short shows again.
                    OnboardingScreen(
                        onSaveFact = { text, kind, about -> scope.launch { memory.save(text, kind, about) } },
                        onFinish = { request ->
                            launcherPreferences.edit().putBoolean(ONBOARDED_KEY, true).apply()
                            showWelcome = false
                            if (request != null) {
                                openAgent()
                                submit(request)
                            } else open(NilaScreen.Home)
                        },
                    )
                }
            }
        }
    }
}

/** Turns NILA adds on its own (a provider follow-up, an action's result) carry a label, not something the person said. */
private val FollowUpLabels = setOf("Zepto", "Zepto tools result", "Action result", "Prepared action", "My NILA")

internal fun isFollowUpTurn(label: String): Boolean = label in FollowUpLabels

/**
 * A monthly reminder kept by My NILA: one tap schedules it and NILA notifies every month, even when closed.
 * Notification permission is asked for on Android 13 and newer; without it the reminder is still saved.
 */
@Composable
private fun MonthlyReminderCard(reminder: MonthlyReminder, context: Context, onResult: (String) -> Unit) {
    val first = firstReminder(reminder, java.time.LocalDateTime.now())
    var saved by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onResult("Saved, but notifications are off for NILA. Turn them on in Android settings to be reminded.")
    }
    ActionCard(
        label = "Monthly reminder",
        title = reminder.title,
        detail = "Every month on ${dayLabel(reminder)} at %02d:%02d. First: %s.\nMy NILA notifies you, even when NILA is closed."
            .format(reminder.time.hour, reminder.time.minute, eventWhen(first.toLocalDate(), null).substringBefore(",")),
        action = if (saved) "Reminder on" else "Remind me every month",
    ) {
        if (saved) return@ActionCard
        NilaReminders(context).add(monthlyReminderOf(reminder, java.time.LocalDateTime.now()))
        saved = true
        onResult("Done. My NILA will remind you every month on ${dayLabel(reminder)}. Stop it from the notification when it comes.")
        if (needsNotificationPermission(context)) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}
/** Android 13 and newer need permission before any notification shows. */
internal fun needsNotificationPermission(context: Context) =
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/** A one-time reminder: one tap and My NILA notifies at that time, even when NILA is closed. */
@Composable
private fun OneOffReminderCard(reminder: OneOffReminder, context: Context, onResult: (String) -> Unit) {
    var saved by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onResult("Saved, but notifications are off for NILA. Turn them on in Android settings to be reminded.")
    }
    val time = "%s, %d:%02d %s".format(
        eventWhen(reminder.at.toLocalDate(), null).substringBefore(","),
        (reminder.at.hour + 11) % 12 + 1, reminder.at.minute, if (reminder.at.hour < 12) "am" else "pm",
    )
    ActionCard(
        label = "Reminder",
        title = reminder.title,
        detail = "$time\nMy NILA sends a notification then, even when NILA is closed.",
        action = if (saved) "Reminder set" else "Remind me",
    ) {
        if (saved) return@ActionCard
        NilaReminders(context).add(NilaReminder(java.util.UUID.randomUUID().toString(), reminder.title, "Your reminder from My NILA.", reminder.at))
        saved = true
        onResult("Done. I'll remind you: ${reminder.title}, $time.")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

private val MascotTapSize = 40.dp
private val HeaderMascotSize = 34.dp
private val WorkingMascotSize = 28.dp
private val EmptyMascotSize = 72.dp
private val FoodRequestSize = 14.sp
private val PromptMinHeight = 48.dp
private val ProductImageSize = 60.dp
private val OptionImageSize = 52.dp
private const val BUSY_CARD_ALPHA = 0.35f
private const val BOUNDARY_ALPHA = 0.35f

/** Where the agent screen was opened from: the mascot starts listening straight away, typed hand-offs do not. */

@Composable
private fun AgentSpace(
    turns: List<Turn>,
    input: String,
    isThinking: Boolean,
    modelStatus: String,
    transcribe: suspend (wav: ByteArray) -> String?,
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
    suggestion: MemorySuggestion?,
    grocery: GroceryActions,
    forget: (NilaResponse.Remember) -> Unit,
    addTurn: (String, NilaResponse) -> Unit,
) {
    val lastWasConfirmation = turns.lastOrNull()?.response is NilaResponse.Confirmation
    val lastText = (turns.lastOrNull()?.response as? NilaResponse.Text)?.value.orEmpty()
    val lastWasError = !isThinking && (lastText.contains("could not", true) || lastText.contains("unavailable", true) || lastText.contains("No alarm", true))
    var celebrate by remember { mutableStateOf(false) }
    // Model load status is debug info: hidden until the mascot is tapped.
    var showStatus by remember { mutableStateOf(false) }
    // Why voice failed, shown in the listening state. A transcript is sent at once, like a typed message:
    // waiting for "Use this request" made every voice request take two steps, and commit actions still ask.
    var notice by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceCapture(
        transcribe = transcribe,
        onFinal = onVoiceRequest,
        onMessage = { notice = it },
        onListenStart = {
            notice = null
            onListenStart()
        },
    )
    val voiceShown = voice.state != VoiceState.IDLE || notice != null
    fun dismissVoice() {
        voice.cancel()
        notice = null
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
            onOpenCart?.let { RoundButton(LucideR.drawable.lucide_ic_shopping_cart, "Your carts", it) }
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
            ListeningTurn(voice = voice, notice = notice, modifier = Modifier.weight(1f))
        } else LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (turns.isEmpty()) item { EmptyAgent(suggestion, onSubmit) }
            itemsIndexed(turns) { index, turn ->
                val busy = index == turns.lastIndex && isThinking
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TurnHeading(turn)
                    when {
                        busy && (turn.response is NilaResponse.Food || turn.response is NilaResponse.Grocery) -> {
                            // Keep the booking card on screen while Swiggy answers, faded so taps wait.
                            WorkingOnIt()
                            Box(Modifier.alpha(BUSY_CARD_ALPHA)) {
                                ResponseView(response = turn.response, context = context, prepare = prepare, confirm = confirm, food = food, grocery = grocery, forget = forget, addTurn = addTurn)
                            }
                        }
                        busy -> WorkingOnIt()
                        else -> ResponseView(response = turn.response, context = context, prepare = prepare, confirm = confirm, food = food, grocery = grocery, forget = forget, addTurn = addTurn)
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
        turn.response is NilaResponse.Food || turn.response is NilaResponse.Grocery -> Text(turn.user, color = Muted, fontSize = FoodRequestSize, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun EmptyAgent(suggestion: MemorySuggestion?, onSubmit: (String) -> Unit) {
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
        DemoPrompts(suggestion, onSubmit)
    }
}

/** The fixed examples, led by one suggestion from memory when there is one; it sends its own request when tapped. */
@Composable
private fun DemoPrompts(suggestion: MemorySuggestion?, onSubmit: (String) -> Unit) {
    val examples = listOf(
        "Find dinner under ₹800",
        "What is 18% of 4250?",
        "Set an alarm for 6:30 am",
        "Open YouTube",
    ).map { MemorySuggestion("“$it”", it) }
    val prompts = listOfNotNull(suggestion) + examples
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        SectionLabel("Try asking")
        prompts.forEach { prompt ->
            val fromMemory = prompt == suggestion
            Box(
                Modifier.heightIn(min = PromptMinHeight).clip(CircleShape).background(Panel)
                    .border(1.dp, if (fromMemory) Lime else Stroke, CircleShape)
                    .clickable { onSubmit(prompt.request) }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(prompt.label, color = if (fromMemory) Lime else Paper, fontSize = 13.sp, fontWeight = FontWeight.Medium)
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
    grocery: GroceryActions,
    forget: (NilaResponse.Remember) -> Unit,
    addTurn: (String, NilaResponse) -> Unit,
) {
    when (response) {
        is NilaResponse.Food -> FoodCardView(response.card, food)
        is NilaResponse.Grocery -> GroceryCardView(response.card, grocery)
        // Resolved into the live flow in submit(); shown only if that ever fails to happen.
        is NilaResponse.FoodSearch -> AnswerCard("Looking for ${response.query}…")
        is NilaResponse.GrocerySearch -> AnswerCard("Comparing Zepto and Swiggy Instamart for ${response.query}…")
        is WebAnswer -> WebAnswerView(response, context)
        // Confirmation is this card only: the iQOO 15 loan devices have fingerprint locked for the hackathon.
        is NilaResponse.NeedsConfirmation -> CommitCard(response) { confirm(response.token) }
        is NilaResponse.Text -> AnswerCard(response.value)
        is HealthPrompt -> ActionCard(
            label = "My NILA · Health",
            title = "${response.title} · ${eventWhen(response.date, null).substringBefore(",")}",
            detail = "Upload the report's photo or PDF: NILA reads it on this phone and files it. It stays encrypted here.",
            action = "Upload the report",
        ) { MyNilaRequests.pending = response }
        is PhotoSearch -> PhotoSearchCard(response)
        is FitnessLogged -> {
            var saved by remember(response.entry.id) { mutableStateOf(false) }
            NilaCard {
                SectionLabel(if (saved) "Saved to My NILA · Fitness" else "Fitness entry", color = Lime)
                Text(entryLine(response.entry), color = Paper, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(eventWhen(response.entry.date, null).substringBefore(",") + " · on this phone", color = Muted, fontSize = 12.sp)
                if (!saved) PrimaryAction("Save to Fitness", onClick = {
                    FitnessStore(context).add(response.entry)
                    saved = true
                })
            }
        }
        is FileSearch -> FileSearchCard(response)
        is StepsAnswer -> NilaCard {
            SectionLabel("Steps", color = Lime)
            Text(
                when {
                    response.trackingStarted -> "Step tracking started just now. Ask again later to see today's count."
                    response.today != null -> "%,d steps today".format(response.today)
                    else -> "NILA can't read the step counter. Allow physical activity in Settings › Phone access."
                },
                color = Paper, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            )
            if (response.week.any { it.second > 0 }) Text("Last 7 days: %,d steps".format(response.week.sumOf { it.second }), color = Muted, fontSize = 13.sp)
        }
        is LinkContact -> LinkContactCard(response, context)
        is NilaResponse.Remember -> NilaCard {
            SectionLabel("Saved to My NILA", color = Lime)
            Text(
                if (response.about == ME) "I'll remember: ${response.fact}" else "${aboutLabel(response.about)}: ${response.fact}",
                color = Paper,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${response.kind.label} · on this phone, encrypted", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    "Forget",
                    color = Lime,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { forget(response) }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                )
            }
        }
        // Only reached if a dismissal was not confirmed through the card; it is never sent without confirmation.
        is NilaResponse.DismissAlarm -> AnswerCard("This alarm was not turned off.")
        // Torch, Camera and Note are done in performNow before they reach the chat.
        is Torch, is Camera, VoiceRecorder, is Note, is TimerControl, is MapSearch, is WebSearch, is PlayMusic, is MediaKey, is SoundChange,
        is SettingsPage, is LaunchApp, ShowAlarms, BatteryLevel, ShowNotes -> AnswerCard("Done.")
        is Message -> MessageCard(response, context) { addTurn("Action result", NilaResponse.Text(it)) }
        is Timer -> TimerCard()
        is NoteSaved -> ActionCard(
            label = "Notes",
            title = "Saved to your notes",
            detail = "\"${response.text}\"\nSay \"show my notes\" to see them all.",
            action = "Add to Notes app",
        ) { addTurn("Action result", NilaResponse.Text(copyToNotesApp(context, response.text))) }
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
        is MonthlyReminder -> MonthlyReminderCard(response, context) { addTurn("Action result", NilaResponse.Text(it)) }
        is OneOffReminder -> OneOffReminderCard(response, context) { addTurn("Action result", NilaResponse.Text(it)) }
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
                .onSuccess {
                    returnToNila(context)
                    addTurn("Action result", NilaResponse.Text("Alarm set for %02d:%02d in your Clock.".format(response.hour, response.minute)))
                }
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
            // OSRM has no live traffic: Bengaluru drives took far longer in Google Maps, so say so plainly.
            Text("%.1f km · about %.0f min without traffic".format(response.distanceKm, response.durationMin), color = Paper, fontSize = 14.sp)
            Text("Live traffic time is in Maps · © OpenStreetMap contributors", color = Muted, fontSize = 10.sp)
            PrimaryAction("Open Maps for live traffic", { openGoogleMaps(context, response.destination, addTurn) }, Modifier.padding(top = 4.dp))
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
internal fun ProductProviderGroupCard(group: ProductProviderGroup) {
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
    // Android only tells apps the NEXT enabled alarm. vivo's Clock removed other alarms when asked to turn off
    // a time it did not have, so NILA dismisses only an alarm it can see; otherwise the user picks in Clock.
    val next = context.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.let {
        java.time.Instant.ofEpochMilli(it.triggerTime).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    }
    return when (dismissPlan(alarm.hour, alarm.minute, next?.hour, next?.minute)) {
        DismissPlan.NOTHING_SET -> NilaResponse.Text("You have no alarm switched on, so there's nothing to turn off.")
        DismissPlan.SHOW_LIST -> runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }.fold(
            onSuccess = {
                NilaResponse.Text(
                    "I can only see your next alarm" + (next?.let { " (%02d:%02d)".format(it.hour, it.minute) } ?: "") +
                        ", not one at %02d:%02d. I opened your alarms so you can turn the right one off.".format(alarm.hour, alarm.minute),
                )
            },
            onFailure = { NilaResponse.Text("I couldn't open your alarms. Nothing was changed.") },
        )
        DismissPlan.DISMISS_NEXT -> {
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            val which = "%02d:%02d".format(next!!.hour, next.minute)
            runCatching { context.startActivity(intent) }.fold(
                onSuccess = { returnToNila(context); NilaResponse.Text("Asked the Clock app to turn off your $which alarm.") },
                onFailure = { NilaResponse.Text("The Clock app on this phone can't turn off alarms for NILA. Nothing was changed.") },
            )
        }
    }
}

/** The answer first; the pages it came from fold into one line, opened only if wanted. */
@Composable
private fun WebAnswerView(response: WebAnswer, context: Context) {
    var showSources by remember { mutableStateOf(false) }
    NilaCard {
        SectionLabel("WEB")
        Text(response.answer, color = Paper, fontSize = 15.sp, lineHeight = 22.sp)
        if (response.sources.isNotEmpty()) {
            TextAction(if (showSources) "Hide sources" else "Sources (${response.sources.size})", onClick = { showSources = !showSources })
        }
        if (showSources) {
            response.sources.forEach { source ->
                TextAction(source.title, onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url))) } })
            }
            TextAction("Open in browser", onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(response.question)))) }
            })
        }
    }
}

/** Does harmless, reversible actions straight away and reports what happened; everything else is returned as is. */
private fun performNow(context: Context, response: NilaResponse): NilaResponse = when (response) {
    is Torch -> NilaResponse.Text(setTorch(context, response.on))
    is Camera -> runCatching { context.startActivity(cameraIntent(response)) }.fold(
        { NilaResponse.Text(if (response.video) "Opening the camera to record video." else if (response.front) "Opening the front camera." else "Opening the camera.") },
        { NilaResponse.Text("No camera app is available.") },
    )
    // The recorder registered for RECORD_SOUND (vivo: com.vivo.soundrecorder).
    VoiceRecorder -> runCatching { context.startActivity(Intent(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION)) }.fold(
        { NilaResponse.Text("Opening the voice recorder.") },
        { NilaResponse.Text("No voice recorder app is available.") },
    )
    is Note -> { saveNote(context, response.text); NoteSaved(response.text) }
    is Timer -> response.also { NilaTimer.start(context, it.seconds) }
    is TimerControl -> NilaResponse.Text(controlTimer(response.action))
    is MapSearch -> openUri(context, "geo:0,0?q=" + Uri.encode(response.query), "Showing ${response.query} nearby in Maps.", "No maps app is available.")
    is PlayMusic -> if (response.query == null) NilaResponse.Text(resumeOrStartMusic(context)) else rememberMusic(context, response.query).let { start(
        context,
        Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(android.app.SearchManager.QUERY, response.query)
            .apply { musicApp(context)?.let(::setPackage) }
            .putExtra(android.provider.MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*"),
        "Playing ${response.query}.",
        "No music app can play that.",
    ) }
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

/**
 * "Play some music": resume what was playing, and if nothing starts (no paused session to resume, the case on
 * the iQOO where NILA said "Playing." and stayed silent), ask the music app to play something on its own.
 */
private fun resumeOrStartMusic(context: Context): String {
    pressMediaKey(context, "play")
    val audio = context.getSystemService(android.media.AudioManager::class.java)
    val last = lastMusic(context)
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        if (audio.isMusicActive) return@postDelayed
        runCatching {
            context.startActivity(
                Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(android.app.SearchManager.QUERY, last.orEmpty())
                    .apply { musicApp(context)?.let(::setPackage) }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }, MUSIC_START_CHECK_MS)
    return last?.let { "Playing $it again." } ?: "Starting your music."
}

/**
 * A music app that actually starts playback from a search. Without a target, YouTube took the intent and only
 * opened its home screen. The first installed one wins; null leaves the choice to Android.
 */
private val MusicApps = listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.jio.media.jiobeats", "com.gaana", "com.bsbportal.music")

private fun musicApp(context: Context): String? = MusicApps.firstOrNull { pkg ->
    runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
}

private const val MUSIC_PREFS = "nila_music"
private const val LAST_MUSIC = "last_query"

/** What the user last asked to play, so "play some music" picks up where they were. Stays on the phone. */
private fun rememberMusic(context: Context, query: String) =
    context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).edit().putString(LAST_MUSIC, query).apply()

private fun lastMusic(context: Context): String? =
    context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).getString(LAST_MUSIC, null)

/** How long to wait for a resumed session to become audible before asking the music app to play. */
private const val MUSIC_START_CHECK_MS = 900L

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
    ActionCard(label = "MESSAGE", title = "Message ${message.who}", detail = "\"${message.body}\"\nOpens Messages with this filled in. You press Send.", action = "Write") {
        val number = phoneNumber(message.who)
        when {
            number != null -> compose(number)
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> composeForContact()
            else -> askContacts.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }
}

/**
 * Video uses the recording request (vivo opened photo mode for the video-camera one). A selfie adds the
 * front-camera hints most camera apps, vivo's included, honour; Android has no official extra for it.
 */
private fun cameraIntent(camera: Camera): Intent {
    val intent = Intent(if (camera.video) android.provider.MediaStore.ACTION_VIDEO_CAPTURE else android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
    if (camera.front) {
        intent.putExtra("android.intent.extras.CAMERA_FACING", 1)
            .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            .putExtra("camerafacing", "front")
    }
    return intent
}

/** Brings NILA back after another app (the Clock) has taken a request, so the user stays in the chat. */
private fun returnToNila(context: Context) {
    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }, 900)
}

private fun controlTimer(action: String): String = when (action) {
    "pause" -> if (NilaTimer.pause()) "Timer paused at ${timerText(NilaTimer.remainingMs)}." else "No timer is running."
    "resume" -> if (NilaTimer.resume()) "Timer resumed: ${timerText(NilaTimer.remainingMs)} left." else "There's no paused timer."
    "stop" -> if (NilaTimer.active) { NilaTimer.stop(); "Timer stopped." } else "No timer is running."
    else -> when {
        NilaTimer.ringing -> "Time's up. Say \"stop the timer\" to silence it."
        NilaTimer.running -> "${timerText(NilaTimer.remainingMs)} left on the timer."
        NilaTimer.remainingMs > 0 -> "Timer paused with ${timerText(NilaTimer.remainingMs)} left."
        else -> "No timer is running."
    }
}

/** The live timer in the chat: counts down, with Pause or Resume and Stop. */
@Composable
private fun TimerCard() {
    val detail = when {
        NilaTimer.ringing -> "Time's up!"
        NilaTimer.running -> "Counting down"
        NilaTimer.remainingMs > 0 -> "Paused"
        else -> "Stopped"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF292925)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(timerText(NilaTimer.remainingMs), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (NilaTimer.ringing) Orange else Paper)
            Text(detail, color = Muted)
            if (NilaTimer.active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!NilaTimer.ringing) {
                        Button(onClick = { if (NilaTimer.running) NilaTimer.pause() else NilaTimer.resume() }) { Text(if (NilaTimer.running) "Pause" else "Resume") }
                    }
                    Button(onClick = { NilaTimer.stop() }) { Text("Stop") }
                }
            }
        }
    }
}

/** Copies a note into the phone's notes app: vivo Notes or Google Keep when installed, else the share sheet. */
private fun copyToNotesApp(context: Context, text: String): String {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val app = listOf("com.vivo.notes" to "vivo Notes", "com.google.android.keep" to "Google Keep").firstOrNull { (pkg) ->
        send.setPackage(pkg).resolveActivity(context.packageManager) != null
    }
    return runCatching {
        if (app != null) context.startActivity(send.setPackage(app.first))
        else context.startActivity(Intent.createChooser(send.setPackage(null), "Save note to"))
    }.fold({ app?.let { "Opening ${it.second} with your note. Save it there." } ?: "Pick an app to save the note." }, { "No notes app is available." })
}

/** Home drifts this fraction of the screen while My NILA slides over it. */
private const val HOME_PARALLAX = 0.25f
