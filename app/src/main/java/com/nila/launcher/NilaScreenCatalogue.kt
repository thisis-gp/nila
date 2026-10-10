package com.nila.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR

@Composable
internal fun NilaScreenCatalogue(
    screen: NilaScreen,
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    rememberedFact: String?,
    onChooseDefaultHome: () -> Unit,
    onForgetMemory: () -> Unit,
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
        NilaScreen.Settings -> SettingsScreen(onOpen, onBack, onChooseDefaultHome)
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
        Text("NILA  ·  SETUP 1/3", color = Lime, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NilaMark(168.dp, Modifier.align(Alignment.CenterHorizontally))
            ScreenTitle("Your phone,\nwithout the hunt.")
            BodyText("Ask in English, हिंदी or മലയാളം. NILA prepares actions and keeps you in control.")
        }
        Column {
            PrimaryButton("Set up NILA") { onOpen(NilaScreen.Permissions) }
            Text("PRIVATE BY DESIGN · ON YOUR DEVICE", Modifier.fillMaxWidth().padding(top = 12.dp), color = Muted, fontSize = 11.sp)
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
    Text("VOICE PREVIEW · NOT LISTENING", Modifier.align(Alignment.CenterHorizontally), color = Lime, fontSize = 11.sp)
    ScreenTitle("“Find chicken biryani for three under ₹800.”")
    BodyText("Lakshmi voice and on-device speech packs are not connected in this build. Use the typed demo for reliable judging.")
    PrimaryButton("Use sample request") { onOpen(NilaScreen.Food) }
}

@Composable
private fun AnswerScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("SIMPLE ANSWER", onOpen, onBack) {
    Text("18% OF ₹4,250", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    Text("₹765", fontSize = 64.sp, lineHeight = 68.sp, fontWeight = FontWeight.Black)
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
    Text("CONFIRMATION REQUIRED", color = Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    ScreenTitle("Review the exact action.")
    SectionCard("3 × Boneless chicken biryani", "Mock provider · Home · includes simulated fees", "₹717")
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF321F19))) {
        Column(Modifier.padding(16.dp)) {
            Text("NILA WILL", color = Orange, fontWeight = FontWeight.Bold)
            BodyText("Record approval for this simulation. No real provider, payment, order or personal data is involved.")
        }
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
    Text("SIMULATION COMPLETE", color = Lime, fontWeight = FontWeight.Bold)
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
    Card(colors = CardDefaults.cardColors(containerColor = CardInk)) {
        Column(Modifier.padding(16.dp)) {
            Text("DRAFT · NOT SENT", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            BodyText("Running a little late. I’ll be at the studio by 6:30.", Paper)
        }
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

@Composable
private fun SettingsScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit, onChooseDefaultHome: () -> Unit) = Page("SETTINGS", onOpen, onBack) {
    ScreenTitle("NILA is yours.")
    BodyText("Language, voice, privacy and intelligence stay understandable.")
    PrimaryButton("Choose default Home app") { onChooseDefaultHome() }
    PrimaryButton("Voice languages · English · हिंदी · മലയാളം") { onOpen(NilaScreen.Languages) }
    SectionCard("Lakshmi voice", "Android on-device voice candidate", "NOT CONNECTED")
    SectionCard("Memory", "Typed demo fact stays in app storage", "LOCAL")
    SectionCard("Commit actions", "Pay, send, book, delete or share", "ALWAYS CONFIRM")
    PrimaryButton("Permission centre") { onOpen(NilaScreen.Permissions) }
    SecondaryButton("Welcome preview") { onOpen(NilaScreen.Welcome) }
    SecondaryButton("Capabilities") { onOpen(NilaScreen.Capabilities) }
    SecondaryButton("Memory") { onOpen(NilaScreen.Memory) }
    SecondaryButton("People & messages") { onOpen(NilaScreen.People) }
    SecondaryButton("Activity & approvals") { onOpen(NilaScreen.Activity) }
}

@Composable
private fun LanguagesScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("VOICE LANGUAGES", onOpen, onBack) {
    val context = LocalContext.current
    var input by remember { mutableStateOf<Map<String, SpeechInputStatus>>(emptyMap()) }
    var output by remember { mutableStateOf<Map<String, SpeechOutputStatus>>(emptyMap()) }
    var checks by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(checks) {
        probeSpeechInput(context) { input = it }
        probeSpeechOutput(context) { output = it }
    }

    ScreenTitle("Speak in your\nlanguage.")
    BodyText("NILA checks this phone's on-device speech packs. Typing works in every language, even when a pack is missing.")
    voiceLanguages.forEach { language ->
        val support = LanguageSupport(
            language,
            input[language.tag] ?: SpeechInputStatus.UNKNOWN,
            output[language.tag] ?: SpeechOutputStatus.UNKNOWN,
        )
        val checked = language.tag in input && language.tag in output
        SectionCard(
            title = language.name,
            detail = "Listening: ${support.input.label}\nSpeaking: ${support.output.label}",
            status = when {
                !checked -> "CHECKING…"
                support.voiceReady -> "VOICE READY · OFFLINE"
                else -> "TEXT ONLY FOR NOW"
            },
        )
        if (support.input == SpeechInputStatus.DOWNLOADABLE) {
            SecondaryButton("Download ${language.name} speech pack") {
                message = if (downloadSpeechInput(context, language.tag)) "Download requested. Tap Check again in a minute." else "This phone cannot download that pack from here."
            }
        }
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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) RoundIconButton(LucideR.drawable.lucide_ic_arrow_left, "Back", onBack) else Spacer(Modifier.size(44.dp))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            RoundIconButton(LucideR.drawable.lucide_ic_house, "Home") { onOpen(NilaScreen.Home) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun ScreenTitle(value: String, color: Color = Paper) = Text(
    value,
    color = color,
    fontSize = 32.sp,
    lineHeight = 34.sp,
    fontWeight = FontWeight.SemiBold,
)

@Composable
private fun BodyText(value: String, color: Color = Muted) = Text(value, color = color, lineHeight = 20.sp)

@Composable
private fun SectionCard(title: String, detail: String, status: String) {
    Card(
        Modifier.fillMaxWidth().border(1.dp, Color(0xFF3A3A35), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(status, color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(title, Modifier.padding(top = 7.dp), fontWeight = FontWeight.Bold)
            Text(detail, Modifier.padding(top = 4.dp), color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(52.dp),
    shape = RoundedCornerShape(16.dp),
) { Text(label) }

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(48.dp),
    shape = RoundedCornerShape(16.dp),
) { Text(label) }

@Composable
private fun RoundIconButton(
    icon: Int,
    label: String,
    onClick: () -> Unit,
) = IconButton(
    onClick = onClick,
    modifier = Modifier.size(44.dp).background(Panel, CircleShape),
) { Icon(painterResource(icon), contentDescription = label, tint = Paper, modifier = Modifier.size(20.dp)) }

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
