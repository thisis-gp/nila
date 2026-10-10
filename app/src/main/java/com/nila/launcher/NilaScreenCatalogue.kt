package com.nila.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR

private val ScreenTitleSize = 34.sp
private val SecondaryActionHeight = 48.dp

@Composable
internal fun NilaScreenCatalogue(
    screen: NilaScreen,
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    rememberedFact: String?,
    onChooseDefaultHome: () -> Unit,
    onForgetMemory: () -> Unit,
    accounts: List<ServiceAccount>,
) {
    when (screen) {
        NilaScreen.Welcome -> WelcomeScreen(onOpen)
        NilaScreen.Permissions -> PermissionsScreen(onOpen, onBack)
        NilaScreen.Voice -> VoiceScreen(onOpen, onBack)
        NilaScreen.Answer -> AnswerScreen(onOpen, onBack)
        NilaScreen.GeneratedCanvas -> GeneratedCanvasScreen(onOpen, onBack)
        NilaScreen.Food -> FoodScreen(onOpen, onBack)
        NilaScreen.PreparedOrder -> PreparedOrderScreen(onOpen, onBack)
        NilaScreen.ActionResult -> ActionResultScreen(onOpen)
        NilaScreen.People -> PeopleScreen(onOpen, onBack)
        NilaScreen.Memory -> MemoryScreen(onOpen, onBack, rememberedFact, onForgetMemory)
        NilaScreen.Capabilities -> CapabilitiesScreen(onOpen, onBack)
        NilaScreen.Activity -> ActivityScreen(onOpen, onBack)
        NilaScreen.Settings -> SettingsScreen(onOpen, onBack, onChooseDefaultHome, accounts)
        NilaScreen.Languages -> LanguagesScreen(onOpen, onBack)
        NilaScreen.Home, NilaScreen.Nila, NilaScreen.Apps -> Unit
    }
}

@Composable
private fun WelcomeScreen(onOpen: (NilaScreen) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        SectionLabel("NILA  ·  Setup 1/3", color = Lime)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NilaMark(168.dp, Modifier.align(Alignment.CenterHorizontally))
            ScreenTitle("Your phone,\nwithout the hunt.")
            BodyText("Ask in English, हिंदी or മലയാളം. NILA prepares actions and keeps you in control.")
        }
        Column {
            PrimaryButton("Set up NILA") { onOpen(NilaScreen.Permissions) }
            SectionLabel("Private by design · on your device", Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }
}

@Composable
private fun PermissionsScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("SETUP · 2/3", onOpen, onBack) {
    ScreenTitle("Let NILA help,\none permission at a time.")
    BodyText("Setup preview only. Android will ask for each permission when a real feature needs it.")
    SectionCard("Microphone", "For private voice requests", "NOT REQUESTED")
    SectionCard("Notifications", "Read and summarise updates", "NOT REQUESTED")
    SectionCard("Contacts & messages", "Find people and prepare drafts", "NOT REQUESTED")
    SectionCard("Alarms", "Create alarms using Android", "AVAILABLE")
    PrimaryButton("Continue to NILA") { onOpen(NilaScreen.Home) }
}

@Composable
private fun VoiceScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("VOICE MODE", onOpen, onBack) {
    Spacer(Modifier.height(12.dp))
    NilaMark(164.dp, Modifier.align(Alignment.CenterHorizontally), voice = true)
    SectionLabel("Voice preview · not listening", Modifier.align(Alignment.CenterHorizontally), color = Lime)
    ScreenTitle("“Find chicken biryani for three under ₹800.”")
    BodyText("Lakshmi voice and on-device speech packs are not connected in this build. Use the typed demo for reliable judging.")
    PrimaryButton("Use sample request") { onOpen(NilaScreen.Food) }
}

@Composable
private fun AnswerScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("SIMPLE ANSWER", onOpen, onBack) {
    SectionLabel("18% of ₹4,250", color = Lime)
    SerifTitle("₹765", size = 64.sp)
    BodyText("4,250 × 0.18 = 765")
    SectionCard("Split the full amount", "Ask NILA how many people", "READ")
    SectionCard("Set an alarm", "Use an Android device action", "PREPARE")
}

@Composable
private fun GeneratedCanvasScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("GENERATED FOR THIS REQUEST", onOpen, onBack) {
    ScreenTitle("Reach the venue\nbefore 5:15.")
    BodyText("A trusted route component makes the choices easier to compare. Route details below are mock demo data.")
    SectionCard("4:22 · Walk", "4 minutes to Indiranagar Metro", "MOCK")
    SectionCard("4:31 · Metro", "Toward Challaghatta · 5 stops", "MOCK")
    SectionCard("5:08 · Arrive", "7 minutes to spare", "MOCK")
    PrimaryButton("Open immersive apps") { onOpen(NilaScreen.Apps) }
}

@Composable
private fun FoodScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("FOOD COMPARISON", onOpen, onBack) {
    ScreenTitle("Two strong matches.")
    BodyText("For three people, under ₹800. All restaurants, ratings, prices and ETAs shown here are simulated—not live Swiggy results.")
    SectionCard("Meghana Foods · ₹717", "Boneless chicken biryani · 4.6★ · 24 min", "MOCK · BEST FIT")
    SectionCard("Mani’s Dum Biryani · ₹657", "Chicken dum biryani · 4.4★ · 29 min", "MOCK")
    PrimaryButton("Prepare best fit") { onOpen(NilaScreen.PreparedOrder) }
}

@Composable
private fun PreparedOrderScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("PREPARED · NOT ORDERED", onOpen, onBack) {
    SectionLabel("Confirmation required", color = Orange)
    ScreenTitle("Review the exact action.")
    SectionCard("3 × Boneless chicken biryani", "Mock provider · Home · includes simulated fees", "₹717")
    NilaCard(color = CommitInk) {
        SectionLabel("NILA will", color = Orange)
        BodyText("Record approval for this simulation. No real provider, payment, order or personal data is involved.")
    }
    PrimaryButton("Confirm simulation") { onOpen(NilaScreen.ActionResult) }
    SecondaryButton("Change order", onClick = onBack)
}

@Composable
private fun ActionResultScreen(onOpen: (NilaScreen) -> Unit) = Page("ACTION RESULT", onOpen, null) {
    Box(
        Modifier.size(72.dp).background(Lime, RoundedCornerShape(36.dp)),
        contentAlignment = Alignment.Center,
    ) { Text("✓", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Black) }
    SectionLabel("Simulation complete", color = Lime)
    ScreenTitle("Prepared.\nYou stayed in control.")
    BodyText("The demo stopped before a real provider or payment. No external data was shared.")
    SectionCard("NILA-DEMO-2048", "Meghana Foods · ₹717", "SIMULATED")
    PrimaryButton("Back home") { onOpen(NilaScreen.Home) }
    SecondaryButton("View activity") { onOpen(NilaScreen.Activity) }
}

@Composable
private fun PeopleScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("PEOPLE & MESSAGES", onOpen, onBack) {
    ScreenTitle("Messages, grouped\nby person.")
    BodyText("Demo content only. NILA has not read notifications, contacts or messages on this device.")
    SectionCard("Arjun Menon", "“Are we still meeting at the studio?” · mock WhatsApp", "MOCK · 4 MIN")
    SectionCard("Devananda", "Sent 3 launcher references · mock Messages", "MOCK · 21 MIN")
    NilaCard {
        SectionLabel("Draft · not sent", color = Lime)
        BodyText("Running a little late. I’ll be at the studio by 6:30.", Paper)
    }
    SecondaryButton("Continue in NILA") { onOpen(NilaScreen.Home) }
}

@Composable
private fun MemoryScreen(
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    rememberedFact: String?,
    onForgetMemory: () -> Unit,
) = Page("MEMORY · ON DEVICE", onOpen, onBack) {
    ScreenTitle("What NILA\nremembers.")
    BodyText("Saved facts stay on this device and can be removed here.")
    if (rememberedFact == null) {
        SectionCard("No saved memories", "Tell NILA ‘remember …’ to save a fact.", "EMPTY")
    } else {
        SectionCard(rememberedFact, "Stored in this app on this device.", "YOU ASKED NILA TO REMEMBER")
        SecondaryButton("Forget this") { onForgetMemory() }
    }
    SecondaryButton("Back to chat") { onOpen(NilaScreen.Home) }
}

@Composable
private fun CapabilitiesScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("CAPABILITIES", onOpen, onBack) {
    ScreenTitle("What NILA\ncan use.")
    BodyText("Only Android actions marked available work in this build. Integrations marked mock are not live.")
    SectionCard("Calculator", "Deterministic · offline", "AVAILABLE")
    SectionCard("Alarms", "Android intent", "AVAILABLE")
    SectionCard("App redirects", "Installed app or browser fallback", "AVAILABLE")
    SectionCard("Memory", "One local typed fact", "DEMO")
    SectionCard("Food", "Hard-coded comparison", "MOCK PROVIDER")
    SectionCard("Messages & routes", "Catalogue preview only", "MOCK")
}

@Composable
private fun ActivityScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("ACTIVITY & APPROVALS", onOpen, onBack) {
    ScreenTitle("Everything NILA\ndid for you.")
    BodyText("Demo audit trail. It does not represent real device or provider activity.")
    SectionCard("Food order simulation", "Meghana Foods · ₹717 · no data shared", "MOCK · APPROVED")
    SectionCard("Alarm set for 6:30", "Example Android Clock action", "MOCK · COMPLETED")
    SectionCard("Message to Arjun", "Stopped before sending", "MOCK · CANCELLED")
}

/**
 * A service NILA can act on, shown in Settings. Sign-in services use [onConnect]; key services (web search)
 * take the key in a hidden field through [saveKey], which returns false for something that is not a key.
 */
internal data class ServiceAccount(
    val name: String,
    val detail: String,
    val connected: Boolean,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val saveKey: ((String) -> Boolean)? = null,
    val keyUrl: String? = null,
)

@Composable
private fun SettingsScreen(
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    onChooseDefaultHome: () -> Unit,
    accounts: List<ServiceAccount>,
) = Page("SETTINGS", onOpen, onBack) {
    ScreenTitle("NILA is yours.")
    BodyText("Language, voice, privacy and intelligence stay understandable.")
    PrimaryButton("Choose default Home app") { onChooseDefaultHome() }
    PrimaryButton("Voice languages · English · हिंदी · മലയാളം") { onOpen(NilaScreen.Languages) }
    SectionLabel("Accounts", Modifier.padding(top = 8.dp))
    BodyText("The services NILA can act on for you. Sign-ins and keys stay encrypted on this phone.")
    accounts.forEach { AccountCard(it) }
    SectionLabel("Voice, memory and consent", Modifier.padding(top = 8.dp))
    SectionCard("Lakshmi voice", "Android on-device voice candidate", "NOT CONNECTED")
    SectionCard("Memory", "Typed demo fact stays in app storage", "LOCAL")
    SectionCard("Commit actions", "Pay, send, book, delete or share", "ALWAYS CONFIRM")
    PrimaryButton("Permission centre") { onOpen(NilaScreen.Permissions) }
    SectionLabel("More", Modifier.padding(top = 8.dp))
    SecondaryButton("Welcome preview") { onOpen(NilaScreen.Welcome) }
    SecondaryButton("Capabilities") { onOpen(NilaScreen.Capabilities) }
    SecondaryButton("Memory") { onOpen(NilaScreen.Memory) }
    SecondaryButton("People & messages") { onOpen(NilaScreen.People) }
    SecondaryButton("Activity & approvals") { onOpen(NilaScreen.Activity) }
}

@Composable
private fun AccountCard(account: ServiceAccount) {
    var key by remember(account.name) { mutableStateOf("") }
    var keyError by remember(account.name) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    NilaCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(account.name, color = Paper, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            StatusPill(account.connected)
        }
        Text(account.detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        when {
            account.connected -> TextAction("Disconnect", onClick = account.onDisconnect, color = Muted)
            account.saveKey != null -> {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; keyError = false },
                    placeholder = { Text("tvly-…", color = Muted) },
                    singleLine = true,
                    // The key is a password: hidden on screen, and never suggested or learned by the keyboard.
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = keyError,
                    supportingText = if (keyError) ({ Text("That doesn't look like a Tavily key. It starts with tvly-.") }) else null,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryAction("Save key", onClick = {
                    if (account.saveKey.invoke(key)) key = "" else keyError = true
                }, enabled = key.isNotBlank())
                account.keyUrl?.let { url -> TextAction("Get a free key", onClick = { uriHandler.openUri(url) }) }
            }
            else -> PrimaryAction("Connect ${account.name}", onClick = account.onConnect)
        }
    }
}

/** "● CONNECTED" in lime, or a muted "NOT CONNECTED". */
@Composable
private fun StatusPill(connected: Boolean) {
    val color = if (connected) Lime else Muted
    Row(
        Modifier.border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        SectionLabel(if (connected) "Connected" else "Not connected", color = color)
    }
}

@Composable
private fun LanguagesScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("VOICE LANGUAGES", onOpen, onBack) {
    val context = LocalContext.current
    var output by remember { mutableStateOf<Map<String, SpeechOutputStatus>>(emptyMap()) }
    var checks by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(checks) { probeSpeechOutput(context) { output = it } }

    ScreenTitle("Speak in your\nlanguage.")
    BodyText("Gemma 4 E2B listens to every language on this phone, with nothing sent online. Pick the language on the chip next to the mic. Typing always works too.")
    voiceLanguages.forEach { language ->
        val speaking = output[language.tag]
        SectionCard(
            title = language.name,
            detail = "Listening: Gemma 4 E2B on this phone\nSpeaking:${(speaking ?: SpeechOutputStatus.UNKNOWN).label}",
            status = when (speaking) {
                null -> "CHECKING…"
                SpeechOutputStatus.OFFLINE -> "LISTENS AND SPEAKS · OFFLINE"
                else -> "LISTENS · REPLIES IN TEXT"
            },
        )
    }
    if (output.values.any { it == SpeechOutputStatus.NEEDS_DOWNLOAD || it == SpeechOutputStatus.UNAVAILABLE }) {
        SecondaryButton("Install speaking voices") {
            if (!installSpeechOutput(context)) message = "No voice installer is available on this phone."
        }
    }
    message?.let { BodyText(it, Lime) }
    PrimaryButton("Check again") { message = null; checks++ }
}

@Composable
private fun Page(
    title: String,
    onOpen: (NilaScreen) -> Unit,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            if (onBack != null) RoundButton(LucideR.drawable.lucide_ic_chevron_left, "Back", onBack, Modifier.align(Alignment.CenterStart))
            Text(
                title,
                color = LabelInk,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.15.em,
                modifier = Modifier.align(Alignment.Center),
            )
            RoundButton(LucideR.drawable.lucide_ic_house, "Home", { onOpen(NilaScreen.Home) }, Modifier.align(Alignment.CenterEnd))
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ScreenTitle(value: String) = SerifTitle(value, size = ScreenTitleSize)

@Composable
private fun BodyText(value: String, color: Color = Muted) = Text(value, color = color, lineHeight = 20.sp)

@Composable
private fun SectionCard(title: String, detail: String, status: String) {
    NilaCard {
        SectionLabel(status, color = Lime)
        Text(title, color = Paper, fontWeight = FontWeight.Bold)
        Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) = PrimaryAction(label, onClick)

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(SecondaryActionHeight),
    shape = RoundedCornerShape(ActionRadius),
    border = BorderStroke(1.dp, Stroke),
) { Text(label, color = Paper) }

@Composable
private fun NilaMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, voice: Boolean = false) {
    Box(
        modifier.size(size).background(Lime.copy(alpha = 0.06f), CircleShape).border(1.dp, Lime.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size * 0.62f).border(2.dp, Lime.copy(alpha = 0.65f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(size * 0.34f).background(Lime, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (voice) Icon(painterResource(LucideR.drawable.lucide_ic_mic), contentDescription = null, tint = Ink)
                else Text("N", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
