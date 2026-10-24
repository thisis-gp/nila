package com.nila.launcher

import java.util.Locale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
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
    facts: List<MemoryFact>,
    onChooseDefaultHome: () -> Unit,
    onForgetFact: (String) -> Unit,
    onForgetAll: () -> Unit,
    onWelcomeTour: () -> Unit,
    accounts: List<ServiceAccount>,
    mcpTools: List<DynamicMcpTool>,
    modelStatus: String,
    modelOptions: List<ModelOption>,
    selectedModelId: String?,
    onSelectModel: (String) -> Unit,
    skills: List<NilaSkill>,
    workflows: List<NilaWorkflow>,
    capabilities: List<CapabilityDescriptor>,
    onSaveSkill: (NilaSkill) -> Unit,
    onDeleteSkill: (String) -> Unit,
    onSaveWorkflow: (NilaWorkflow) -> Boolean,
    onDeleteWorkflow: (String) -> Unit,
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
        NilaScreen.Memory -> MemoryScreen(onOpen, onBack, facts, onForgetFact, onForgetAll)
        NilaScreen.Capabilities -> CapabilitiesScreen(onOpen, onBack, mcpTools)
        NilaScreen.Activity -> ActivityScreen(onOpen, onBack)
        NilaScreen.Settings -> SettingsScreen(onOpen, onBack, onChooseDefaultHome, onWelcomeTour, facts, accounts, modelStatus, modelOptions, selectedModelId, onSelectModel)
        NilaScreen.Skills -> SkillBuilderScreen(onOpen, onBack, skills, workflows, capabilities, onSaveSkill, onDeleteSkill, onSaveWorkflow, onDeleteWorkflow)
        NilaScreen.Languages -> LanguagesScreen(onOpen, onBack)
        NilaScreen.PhoneAccess -> Page("PHONE ACCESS", onOpen, onBack) { PhoneAccessTab() }
        NilaScreen.ScreenGuidance -> ScreenGuidanceScreen(onBack)
        NilaScreen.Home, NilaScreen.Nila, NilaScreen.Apps, NilaScreen.MyNila -> Unit
    }
}

@Composable
private fun SkillBuilderScreen(
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    skills: List<NilaSkill>,
    workflows: List<NilaWorkflow>,
    capabilities: List<CapabilityDescriptor>,
    onSave: (NilaSkill) -> Unit,
    onDelete: (String) -> Unit,
    onSaveWorkflow: (NilaWorkflow) -> Boolean,
    onDeleteWorkflow: (String) -> Unit,
) = Page("SKILL BUILDER", onOpen, onBack) {
    var name by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("") }
    var arguments by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(capabilities.firstOrNull()?.id.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var workflowName by remember { mutableStateOf("") }
    var workflowTrigger by remember { mutableStateOf("") }
    var workflowSteps by remember { mutableStateOf("") }
    var workflowError by remember { mutableStateOf<String?>(null) }
    val capability = capabilities.firstOrNull { it.id == selected }

    ScreenTitle("Build a skill")
    BodyText("Make a phrase for one existing NILA tool. Skills are stored on this phone; they cannot add permissions or run code.")
    OutlinedTextField(name, { if (it.length <= 60) name = it; error = null }, label = { Text("Skill name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(trigger, { if (it.length <= 120) trigger = it; error = null }, label = { Text("Exact phrase to say") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Box {
        SecondaryButton("Tool: ${capability?.id ?: "Choose"}") { menu = true }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            capabilities.forEach { item ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(item.id, color = Paper) },
                    onClick = { selected = item.id; menu = false; error = null },
                )
            }
        }
    }
    OutlinedTextField(
        arguments,
        { if (it.length <= 500) arguments = it; error = null },
        label = { Text("Arguments (key=value, comma-separated)") },
        supportingText = { Text(capability?.params?.joinToString { "${it.name}${if (it.required) "*" else ""}" } ?: "") },
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = Orange) }
    PrimaryButton("Save skill") {
        val parsed = parseSkillArguments(arguments)
        val candidate = capability?.let {
            NilaSkill(NilaSkillStore.newId(), name.trim(), trigger.trim(), it.id, parsed.orEmpty())
        }
        when {
            parsed == null -> error = "Use unique key=value arguments, separated by commas."
            candidate == null -> error = "Choose one of NILA's tools."
            skills.size >= MAX_NILA_SKILLS -> error = "NILA can store up to $MAX_NILA_SKILLS skills."
            skills.any { skillTriggerKey(it.trigger) == skillTriggerKey(candidate.trigger) } || workflows.any { skillTriggerKey(it.trigger) == skillTriggerKey(candidate.trigger) } -> error = "A skill or workflow already uses that phrase."
            skillCall(candidate, capabilities.associateBy { it.id }) == null -> error = "Provide valid values for the tool's required arguments."
            else -> {
                onSave(candidate)
                name = ""; trigger = ""; arguments = ""; error = null
            }
        }
    }
    if (skills.isEmpty()) NilaCard { BodyText("No skills yet. Create one above.") }
    skills.forEach { skill ->
        NilaCard {
            Text(skill.name, color = Paper, fontWeight = FontWeight.Bold)
            BodyText("\"${skill.trigger}\" → ${skill.capabilityId}")
            TextAction("Delete", onClick = { onDelete(skill.id) }, color = Orange)
        }
    }
    BodyText("Only the exact phrase runs a skill. Commit tools still stop for NILA's normal confirmation.")

    Spacer(Modifier.height(18.dp))
    ScreenTitle("Build a workflow")
    BodyText("Create a private, exact-phrase routine from existing NILA tools. Each line runs in order; no code or background agents are generated. Commit steps stop for your confirmation.")
    OutlinedTextField(workflowName, { if (it.length <= 60) workflowName = it; workflowError = null }, label = { Text("Workflow name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(workflowTrigger, { if (it.length <= 120) workflowTrigger = it; workflowError = null }, label = { Text("Exact phrase to run") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        workflowSteps,
        { if (it.length <= 4_000) workflowSteps = it; workflowError = null },
        label = { Text("One tool per line") },
        supportingText = { Text("Example: battery\nset_timer: minutes=5") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    workflowError?.let { Text(it, color = Orange) }
    PrimaryButton("Save workflow") {
        val steps = parseWorkflowSteps(workflowSteps, capabilities.associateBy { it.id })
        val candidate = NilaWorkflow(NilaWorkflowStore.newId(), workflowName.trim(), workflowTrigger.trim(), steps.orEmpty())
        when {
            workflowName.isBlank() || workflowName.length > 60 -> workflowError = "Add a name up to 60 characters."
            workflowTrigger.isBlank() || workflowTrigger.length > 120 -> workflowError = "Add an exact phrase up to 120 characters."
            steps == null -> workflowError = "Use up to $MAX_NILA_WORKFLOW_STEPS valid tool lines with typed arguments."
            workflowCalls(candidate, capabilities.associateBy { it.id }) == null -> workflowError = "Check the steps and their arguments."
            workflows.size >= MAX_NILA_WORKFLOWS -> workflowError = "NILA stores up to $MAX_NILA_WORKFLOWS workflows."
            workflows.any { skillTriggerKey(it.trigger) == skillTriggerKey(candidate.trigger) } || skills.any { skillTriggerKey(it.trigger) == skillTriggerKey(candidate.trigger) } -> workflowError = "A skill or workflow already uses that phrase."
            !onSaveWorkflow(candidate) -> workflowError = "Workflow could not be saved securely."
            else -> { workflowName = ""; workflowTrigger = ""; workflowSteps = ""; workflowError = null }
        }
    }
    workflows.forEach { workflow ->
        NilaCard {
            Text(workflow.name, color = Paper, fontWeight = FontWeight.Bold)
            BodyText("\"${workflow.trigger}\" · ${workflow.steps.size} sequential steps")
            TextAction("Delete", onClick = { onDeleteWorkflow(workflow.id) }, color = Orange)
        }
    }
    BodyText("Workflows start only when you use their exact phrase. They preview before running and pause for each Commit confirmation. Interactive provider steps hand off instead of running unattended.")
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
    facts: List<MemoryFact>,
    onForgetFact: (String) -> Unit,
    onForgetAll: () -> Unit,
) = Page("MY NILA · ON THIS PHONE", onOpen, onBack) {
    ScreenTitle("My NILA")
    BodyText("What NILA remembers from your chats. It stays on this phone, and you can forget any of it here.")
    if (facts.isEmpty()) {
        SectionCard("Nothing yet", "Just chat: \"my wife loves butter chicken\" or \"I'm vegetarian on Tuesdays\".", "EMPTY")
    } else {
        factsByPerson(facts).forEach { (about, known) ->
            SectionLabel(personTitle(about, known), Modifier.padding(top = 8.dp))
            known.forEach { fact ->
                NilaCard {
                    SectionLabel(fact.kind.label.uppercase(), color = Lime)
                    Text(fact.text, color = Paper, fontWeight = FontWeight.Bold)
                    TextAction("Forget", onClick = { onForgetFact(fact.id) })
                }
            }
        }
        SecondaryButton("Forget everything") { onForgetAll() }
    }
    SecondaryButton("Back to chat") { onOpen(NilaScreen.Home) }
}

@Composable
private fun CapabilitiesScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit, mcpTools: List<DynamicMcpTool>) = Page("CAPABILITIES", onOpen, onBack) {
    ScreenTitle("What NILA\ncan use.")
    BodyText("Only Android actions marked available work in this build. Integrations marked mock are not live.")
    SectionCard("Calculator", "Deterministic · offline", "AVAILABLE")
    SectionCard("Alarms", "Android intent", "AVAILABLE")
    SectionCard("App redirects", "Installed app or browser fallback", "AVAILABLE")
    SectionCard("Memory", "Typed facts on this device, used by chat and food", "AVAILABLE")
    androidx.compose.material3.OutlinedButton(onClick = { onOpen(NilaScreen.ScreenGuidance) }) {
        Text("Suggest screen controls · on-device · no automatic taps")
    }
    SectionCard("Food", "Swiggy and Zomato MCP; groceries through Zepto and Instamart", "LIVE WHEN CONNECTED")
    if (mcpTools.isEmpty()) {
        SectionCard("MCP tools", "Connect Swiggy, Zomato or Zepto in Settings to discover server tools", "NOT CONNECTED")
    } else {
        mcpTools.groupBy(DynamicMcpTool::serverName).forEach { (server, tools) ->
            SectionCard("$server MCP", "${tools.size} tools discovered. Each dynamic call asks before running.", tools.joinToString(", ") { it.name }.take(180))
        }
    }
    SectionCard("Messages & routes", "Drafts and map links use Android apps", "AVAILABLE")
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
private data class LegacyServiceAccount(
    val name: String,
    val detail: String,
    val connected: Boolean,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val saveKey: ((String) -> Boolean)? = null,
    val keyUrl: String? = null,
    val icon: Int = LucideR.drawable.lucide_ic_globe,
)

@Composable
private fun LegacySettingsScreen(
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    onChooseDefaultHome: () -> Unit,
    accounts: List<LegacyServiceAccount>,
) = Page("SETTINGS", onOpen, onBack) {
    ScreenTitle("Settings")
    BodyText("${accounts.count { it.connected }} of ${accounts.size} services connected. Everything else stays on this phone.")

    LegacySettingsGroup("General") {
        LegacySettingsRow(LucideR.drawable.lucide_ic_house, "Home screen", "Make NILA your phone's home", onClick = onChooseDefaultHome)
        LegacySettingsRow(LucideR.drawable.lucide_ic_languages, "Voice languages", "English · हिंदी · മലയാളം") { onOpen(NilaScreen.Languages) }
        LegacySettingsRow(LucideR.drawable.lucide_ic_lock, "Permissions", "Microphone, location, contacts") { onOpen(NilaScreen.Permissions) }
        LegacySettingsRow(LucideR.drawable.lucide_ic_lock, "Phone access", "Photos, steps, contacts and more") { onOpen(NilaScreen.PhoneAccess) }
    }

    LegacySettingsGroup("Accounts") {
        accounts.forEach { LegacyAccountRow(it) }
    }

    LegacySettingsGroup("Privacy and safety") {
        LegacySettingsRow(LucideR.drawable.lucide_ic_brain, "My NILA", "What NILA knows about you and your people, on this phone") { onOpen(NilaScreen.MyNila) }
        LegacySettingsRow(LucideR.drawable.lucide_ic_volume_2, "Spoken replies", "Offline voices for Lakshmi") { onOpen(NilaScreen.Languages) }
    }
    // A fact about NILA, not a setting: nothing here to switch.
    BodyText("NILA always asks before it pays, sends, books, deletes or shares anything.")

    LegacySettingsGroup("About") {
        LegacySettingsRow(LucideR.drawable.lucide_ic_sparkles, "What NILA can do", "Every action and the model behind it") { onOpen(NilaScreen.Capabilities) }
        LegacySettingsRow(LucideR.drawable.lucide_ic_info, "Welcome tour", "The first-run screens again") { onOpen(NilaScreen.Welcome) }
    }
}

/** A titled block of rows on one card, like the phone's own settings. */
@Composable
private fun LegacySettingsGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    SectionLabel(title, Modifier.padding(top = 12.dp, start = 4.dp))
    Surface(color = CardInk, shape = RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth()) {
        Column(content = rows)
    }
}

/** One setting: an icon, what it is, a line about it, and either a value or a chevron when it opens something. */
@Composable
private fun LegacySettingsRow(icon: Int, title: String, detail: String, value: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegacyRowIcon(icon)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        value?.let { Text(it, color = Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        if (onClick != null) Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), contentDescription = null, tint = Muted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LegacyRowIcon(icon: Int, tint: Color = Lime) {
    Box(Modifier.size(38.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * A service as one row: connected ones show a lime dot, the rest a Connect action. Tapping a connected row
 * reveals Disconnect, so it is never one stray tap away; a key service opens its hidden key field in place.
 */
@Composable
private fun LegacyAccountRow(account: LegacyServiceAccount) {
    var open by remember(account.name) { mutableStateOf(false) }
    var key by remember(account.name) { mutableStateOf("") }
    var keyError by remember(account.name) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (open) "Hide ${account.name} options" else "${account.name} options") {
                    if (account.connected || account.saveKey != null) open = !open else account.onConnect()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegacyRowIcon(account.icon, tint = if (account.connected) Lime else Muted)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account.name, color = Paper, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(account.detail, color = Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (account.connected) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(Lime, CircleShape))
                    Text("On", color = Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("Connect", color = Lime, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (account.connected) {
                    TextAction("Disconnect ${account.name}", onClick = { open = false; account.onDisconnect() }, color = Orange)
                } else if (account.saveKey != null) {
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
                        if (account.saveKey.invoke(key)) { key = ""; open = false } else keyError = true
                    }, enabled = key.isNotBlank())
                    account.keyUrl?.let { url -> TextAction("Get a free key", onClick = { uriHandler.openUri(url) }) }
                }
            }
        }
    }
}

@Composable
private fun LanguagesScreen(onOpen: (NilaScreen) -> Unit, onBack: () -> Unit) = Page("VOICE LANGUAGES", onOpen, onBack) {
    val context = LocalContext.current
    var output by remember { mutableStateOf<Map<String, SpeechOutputStatus>>(emptyMap()) }
    var checks by remember { mutableStateOf(0) }
    var probed by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(checks) { probeSpeechOutput(context) { output = it; probed = true } }

    ScreenTitle("Speak in your\nlanguage.")
    BodyText("Voice capture and transcription use on-device Gemma 4 E2B. Transcription language coverage has not been validated on this phone. Typing always works too.")
    output.forEach { (tag, speaking) ->
        val language = Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault()).ifBlank { tag }
        SectionCard(
            title = language,
            detail = "Transcription: not verified\nSpeaking: ${speaking.label}",
            status = when (speaking) {
                SpeechOutputStatus.OFFLINE -> "SPEECH OUTPUT · OFFLINE"
                SpeechOutputStatus.NEEDS_DOWNLOAD -> "SPEECH OUTPUT · DOWNLOAD VOICE"
                SpeechOutputStatus.ONLINE_ONLY -> "SPEECH OUTPUT · NETWORK-DEPENDENT"
                else -> "SPEECH OUTPUT · NOT AVAILABLE OFFLINE"
            },
        )
    }
    if (!probed) BodyText("Checking the voices installed on this phone…")
    else if (output.isEmpty()) BodyText("No available text-to-speech voices were reported by Android.")
    if (output.values.any { it != SpeechOutputStatus.OFFLINE }) {
        SecondaryButton("Install speaking voices") {
            if (!installSpeechOutput(context)) message = "No voice installer is available on this phone."
        }
    }
    message?.let { BodyText(it, Lime) }
    PrimaryButton("Check again") { message = null; probed = false; checks++ }
}

@Composable
private fun Page(
    title: String,
    onOpen: (NilaScreen) -> Unit,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = ScreenGutter, vertical = 12.dp),
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
