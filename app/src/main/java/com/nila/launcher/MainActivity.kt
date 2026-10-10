package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

private data class Turn(val user: String, val response: NilaResponse)

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
    var swiggySession by remember { mutableStateOf(SwiggyAuth.currentSession(context)) }
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

    fun submit(prompt: String = input) {
        if (prompt.isBlank() || isThinking) return
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
                                modelStatus = "Gemma 4 E2B ${brain.assistantState.label} · FunctionGemma ${brain.routerState.label}" +
                                    (brain.loadError?.let { "\n$it" } ?: ""),
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
                                food = foodActions,
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
                            swiggyConnected = swiggySession != null,
                            onConnectSwiggy = {
                                android.util.Log.d("NilaSwiggy", "Connect Swiggy tapped")
                                scope.launch {
                                    android.util.Log.d("NilaSwiggy", "login() starting")
                                    val session = SwiggyAuth.login(context)
                                    android.util.Log.d("NilaSwiggy", "login() returned: $session")
                                    swiggySession = session
                                    if (session == null) {
                                        Toast.makeText(context, "Swiggy sign-in didn't complete.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onDisconnectSwiggy = {
                                SwiggyAuth.logout(context)
                                swiggySession = null
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
    // No separate imePadding() here: the root Box already applies safeDrawingPadding(),
    // which includes the IME inset. Adding it again double-counted the keyboard height.
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
    food: FoodActions,
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
                    val busy = index == turns.lastIndex && isThinking
                    if (busy && turn.response is NilaResponse.Food) {
                        // Keep the booking card on screen while Swiggy answers, faded so taps wait.
                        Text("Working on it…", color = Lime, style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.alpha(0.35f)) {
                            ResponseView(response = turn.response, context = context, prepare = prepare, confirm = confirm, food = food, addTurn = addTurn)
                        }
                    } else if (busy) {
                        Text("NILA is thinking…", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        ResponseView(response = turn.response, context = context, prepare = prepare, confirm = confirm, food = food, addTurn = addTurn)
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
        is NilaResponse.FoodSearch -> Text("Looking for ${response.query}…")
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
        is Torch, is Camera, is Note -> Text("Done.")
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
        is NilaResponse.Navigate -> ActionCard(
            title = "Navigate to ${response.destination}",
            detail = "Opens Google Maps for live turn-by-turn directions.",
            action = "Open Maps",
        ) { openGoogleMaps(context, response.destination, addTurn) }
        is NilaResponse.RouteCard -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(response.source, color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardInk),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(response.destination, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "%.1f km · about %.0f min by road".format(response.distanceKm, response.durationMin),
                        color = Color.LightGray,
                    )
                    Text("© OpenStreetMap contributors", color = Muted, fontSize = 10.sp)
                    Button(onClick = { openGoogleMaps(context, response.destination, addTurn) }) {
                        Text("Open Maps for navigation")
                    }
                }
            }
        }
        is NilaResponse.Choices -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                response.source,
                color = if (response.source.startsWith("LIVE")) Lime else Orange,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
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

