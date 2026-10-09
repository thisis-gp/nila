package com.nila.launcher

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NilaScreenCatalogue(
    screen: NilaScreen,
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    onOpenApp: (NilaResponse.OpenApp) -> Boolean,
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
        NilaScreen.Memory -> MemoryScreen(onOpen, onBack)
        NilaScreen.Capabilities -> CapabilitiesScreen(onOpen, onBack)
        NilaScreen.Apps -> AppsScreen(onOpen, onBack, onOpenApp)
        NilaScreen.Activity -> ActivityScreen(onOpen, onBack)
        NilaScreen.Settings -> SettingsScreen(onOpen, onBack)
        NilaScreen.Home -> Unit
    }
}

@Composable
private fun WelcomeScreen(onOpen: (NilaScreen) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Lime).padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("NILA  ·  SETUP 1/3", color = Ink, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        Column {
            Box(
                Modifier.size(72.dp).background(Ink, RoundedCornerShape(36.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("N", color = Lime, fontSize = 34.sp, fontWeight = FontWeight.Black) }
            ScreenTitle("Your phone,\nwithout the hunt.", Ink)
            BodyText("Ask in English, हिंदी or മലയാളം. NILA prepares actions and keeps you in control.", Color(0xFF55540F))
        }
        Column {
            PrimaryButton("Set up NILA") { onOpen(NilaScreen.Permissions) }
            Text("PRIVATE BY DESIGN · ON YOUR DEVICE", Modifier.fillMaxWidth().padding(top = 10.dp), color = Ink, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PermissionsScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("SETUP · 2/3", onOpen, onBack, false) {
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
    Spacer(Modifier.height(36.dp))
    Box(
        Modifier.size(150.dp).align(Alignment.CenterHorizontally).background(Lime, RoundedCornerShape(75.dp)),
        contentAlignment = Alignment.Center,
    ) { Text("N", color = Ink, fontSize = 54.sp, fontWeight = FontWeight.Black) }
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
private fun PreparedOrderScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("PREPARED · NOT ORDERED", onOpen, onBack, false) {
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
private fun ActionResultScreen(onOpen: (NilaScreen) -> Unit) = Page("ACTION RESULT", onOpen, null, false) {
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
    SecondaryButton("Sending disabled in prototype", enabled = false) { }
}

@Composable
private fun MemoryScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("MEMORY · ON DEVICE", onOpen, onBack) {
    ScreenTitle("What NILA\nremembers.")
    BodyText("Illustrative memories below are mock data. Real typed ‘remember’ requests stay in this app’s local storage.")
    SectionCard("Vegetarian food on Fridays", "Used only while comparing meals", "MOCK · USER SAID")
    SectionCard("Prefer metro when it saves 10+ minutes", "Suggested preference · reviewable", "MOCK · INFERRED")
    SecondaryButton("Manage typed memory on Home") { onOpen(NilaScreen.Home) }
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
private fun AppsScreen(
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    onOpenApp: (NilaResponse.OpenApp) -> Boolean,
) = Page("IMMERSIVE APPS", onOpen, onBack) {
    var status by remember { mutableStateOf<String?>(null) }
    fun launch(app: NilaResponse.OpenApp) {
        status = if (onOpenApp(app)) "Opening ${app.name}." else "${app.name} could not be opened."
    }

    ScreenTitle("Some experiences\ndeserve the full screen.")
    BodyText("Movies, games and social feeds open in their own installed app or website.")
    AppButton("YouTube") { launch(NilaResponse.OpenApp("YouTube", "com.google.android.youtube", "https://youtube.com")) }
    AppButton("Netflix") { launch(NilaResponse.OpenApp("Netflix", "com.netflix.mediaclient", "https://netflix.com")) }
    AppButton("Instagram") { launch(NilaResponse.OpenApp("Instagram", "com.instagram.android", "https://instagram.com")) }
    AppButton("BGMI") { launch(NilaResponse.OpenApp("BGMI", "com.pubg.imobile", "https://www.battlegroundsmobileindia.com")) }
    status?.let { BodyText(it, Paper) }
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
private fun SettingsScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("SETTINGS", onOpen, onBack) {
    ScreenTitle("NILA is yours.")
    BodyText("Language, voice, privacy and intelligence stay understandable.")
    SectionCard("Languages", "English · हिंदी · മലയാളം", "PLANNED")
    SectionCard("Lakshmi voice", "Android on-device voice candidate", "NOT CONNECTED")
    SectionCard("Memory", "Typed demo fact stays in app storage", "LOCAL")
    SectionCard("Commit actions", "Pay, send, book, delete or share", "ALWAYS CONFIRM")
    PrimaryButton("Permission centre") { onOpen(NilaScreen.Permissions) }
    SecondaryButton("Welcome preview") { onOpen(NilaScreen.Welcome) }
    SecondaryButton("Capabilities") { onOpen(NilaScreen.Capabilities) }
    SecondaryButton("Memory") { onOpen(NilaScreen.Memory) }
}

@Composable
private fun Page(
    title: String,
    onOpen: (NilaScreen) -> Unit,
    onBack: (() -> Unit)?,
    showNavigation: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ink).padding(20.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) OutlinedButton(onClick = onBack) { Text("Back") } else Spacer(Modifier.size(72.dp, 40.dp))
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            OutlinedButton(onClick = { onOpen(NilaScreen.Home) }) { Text("Home") }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (showNavigation) BottomNavigation(onOpen)
    }
}

@Composable
internal fun BottomNavigation(onOpen: (NilaScreen) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(
            NilaScreen.Home to "Home",
            NilaScreen.People to "People",
            NilaScreen.Voice to "NILA",
            NilaScreen.Activity to "Activity",
            NilaScreen.Apps to "Apps",
        ).forEach { (screen, label) ->
            Button(
                onClick = { onOpen(screen) },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
                colors = ButtonDefaults.buttonColors(containerColor = CardInk),
            ) { Text(label, fontSize = 10.sp) }
        }
    }
}

@Composable
private fun ScreenTitle(value: String, color: Color = Paper) = Text(
    value,
    color = color,
    fontSize = 36.sp,
    lineHeight = 37.sp,
    fontWeight = FontWeight.SemiBold,
)

@Composable
private fun BodyText(value: String, color: Color = Muted) = Text(value, color = color, lineHeight = 20.sp)

@Composable
private fun SectionCard(title: String, detail: String, status: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardInk)) {
        Column(Modifier.padding(16.dp)) {
            Text(status, color = Lime, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(title, Modifier.padding(top = 7.dp), fontWeight = FontWeight.Bold)
            Text(detail, Modifier.padding(top = 4.dp), color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(48.dp),
) { Text(label) }

@Composable
private fun SecondaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) = OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.fillMaxWidth().height(48.dp),
) { Text(label) }

@Composable
private fun AppButton(label: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().height(54.dp),
    colors = ButtonDefaults.buttonColors(containerColor = CardInk),
) { Text("Open $label") }
