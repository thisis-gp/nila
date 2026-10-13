package com.nila.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR

private val RowMinHeight = 60.dp
private val RowIconSize = 20.dp

/** A service NILA can act on, shown in Settings. Sign-in services use [onConnect]; key services (web search) take the key in a hidden field through [saveKey], which returns false for something that is not a key. */
internal data class ServiceAccount(
    val name: String,
    val detail: String,
    val connected: Boolean,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val saveKey: ((String) -> Boolean)? = null,
    val keyUrl: String? = null,
    val icon: Int = LucideR.drawable.lucide_ic_globe,
)

/**
 * Settings as one grouped list: General, Accounts, Privacy, About. Every older destination stays one tap
 * away: People, Activity, Capabilities and the setup preview live in Privacy and About.
 */
@Composable
internal fun SettingsScreen(
    onOpen: (NilaScreen) -> Unit,
    onBack: () -> Unit,
    onChooseDefaultHome: () -> Unit,
    onWelcomeTour: () -> Unit,
    facts: List<MemoryFact>,
    accounts: List<ServiceAccount>,
    customMcpServers: List<McpServer>,
    mcpServerStatus: String?,
    mcpBusy: Boolean,
    onAddMcpServer: (String, String, String) -> Unit,
    onRemoveMcpServer: (String) -> Unit,
) {
    val people = factsByPerson(facts).size
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val prefs = remember(context) { context.getSharedPreferences("nila_launcher", android.content.Context.MODE_PRIVATE) }
    // Turning the lock on needs nothing; turning it off needs the phone's lock first (unless the phone has none).
    fun toggleLock() {
        if (!MyNilaLock.enabled) return MyNilaLock.setEnabled(prefs, true)
        val activity = context as? androidx.fragment.app.FragmentActivity
        if (activity == null || MyNilaLock.support(context) != LockSupport.Ready) return MyNilaLock.setEnabled(prefs, false)
        MyNilaLock.authenticate(activity) { ok -> if (ok) MyNilaLock.setEnabled(prefs, false) }
    }
    Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(horizontal = ScreenGutter, vertical = 12.dp)) {
        RoundButton(LucideR.drawable.lucide_ic_chevron_left, "Back", onBack)
        SerifTitle("Settings", size = 34.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            GroupLabel("General")
            SettingsRow(LucideR.drawable.lucide_ic_house, "Home screen", "Make NILA your home app", onClick = onChooseDefaultHome)
            SettingsRow(LucideR.drawable.lucide_ic_languages, "Languages", "English, हिंदी, മലയാളം", onClick = { onOpen(NilaScreen.Languages) })
            SettingsRow(LucideR.drawable.lucide_ic_shield_check, "Permissions", "What Android has allowed", onClick = { onOpen(NilaScreen.Permissions) })

            GroupLabel("Accounts")
            accounts.forEach { AccountRow(it) }

            GroupLabel("MCP servers")
            McpServerSettings(customMcpServers, mcpServerStatus, mcpBusy, onAddMcpServer, onRemoveMcpServer)

            GroupLabel("Privacy")
            SettingsRow(
                LucideR.drawable.lucide_ic_user,
                "My NILA",
                if (facts.isEmpty()) "Nothing remembered yet" else "${plural(people, "person", "people")}, ${plural(facts.size, "fact", "facts")}",
                onClick = { onOpen(NilaScreen.MyNila) },
            )
            SettingsRow(
                LucideR.drawable.lucide_ic_lock,
                "Lock My NILA",
                "Ask for your fingerprint or PIN to open it",
                onClick = ::toggleLock,
                end = { Switch(checked = MyNilaLock.enabled, onCheckedChange = { toggleLock() }, colors = SwitchDefaults.colors(checkedTrackColor = Lime, checkedThumbColor = Ink)) },
            )
            SettingsRow(LucideR.drawable.lucide_ic_smartphone, "Phone access", "Photos, files, contacts", onClick = { onOpen(NilaScreen.PhoneAccess) })
            SettingsRow(LucideR.drawable.lucide_ic_history, "Activity and approvals", "What NILA has done for you", onClick = { onOpen(NilaScreen.Activity) })
            SettingsRow(LucideR.drawable.lucide_ic_hand, "Confirm before acting", "NILA always asks before paying, sending or booking", trailing = "Always")

            GroupLabel("About")
            SettingsRow(LucideR.drawable.lucide_ic_info, "NILA $version", "Everything else stays on this phone")
            SettingsRow(LucideR.drawable.lucide_ic_sparkles, "Welcome tour", "Meet NILA again", onClick = onWelcomeTour)
            SettingsRow(LucideR.drawable.lucide_ic_list_checks, "What NILA can use", "Tools and which ones are live", onClick = { onOpen(NilaScreen.Capabilities) })
            SettingsRow(LucideR.drawable.lucide_ic_users, "People and messages", "Preview of grouped messages", onClick = { onOpen(NilaScreen.People) })
            SettingsRow(LucideR.drawable.lucide_ic_circle_check, "Setup preview", "The first-run screens, as a demo", onClick = { onOpen(NilaScreen.Welcome) })
        }
    }
}

@Composable
private fun McpServerSettings(
    servers: List<McpServer>,
    status: String?,
    busy: Boolean,
    onAdd: (String, String, String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Add a remote MCP server. NILA accepts HTTPS endpoints and asks before every tool call.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 6.dp))
    servers.forEach { server ->
        SettingsRow(LucideR.drawable.lucide_ic_globe, server.name, server.endpoint, trailing = "Remove", onClick = { onRemove(server.id) })
    }
    OutlinedTextField(name, { name = it.take(80); error = null }, label = { Text("Server name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(endpoint, { endpoint = it.take(2049); error = null }, label = { Text("HTTPS endpoint") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
    OutlinedTextField(token, { token = it.take(4097); error = null }, label = { Text("Bearer token (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth())
    PrimaryAction(
        if (busy) "Checking server…" else "Add and discover tools",
        onClick = {
            val validation = when {
                name.trim().length !in 1..40 -> "Name must be 1–40 characters."
                else -> McpEndpoint.validate(endpoint)
            }
            if (validation != null) error = validation else {
                onAdd(name.trim(), endpoint.trim(), token)
                name = ""; endpoint = ""; token = ""
                error = null
            }
        },
        enabled = !busy,
    )
    (error ?: status)?.let { Text(it, color = if (error != null || it.startsWith("Couldn't")) Orange else Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    if (servers.isEmpty()) Text("No custom servers added.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
}

private fun plural(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"

@Composable
private fun GroupLabel(text: String) = Text(
    text,
    color = Lime,
    fontSize = 13.sp,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
)

@Composable
private fun SettingsRow(icon: Int, title: String, detail: String, trailing: String? = null, onClick: (() -> Unit)? = null, end: @Composable (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RowMinHeight).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Muted, modifier = Modifier.size(RowIconSize))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp)) {
            Text(title, color = Paper, fontSize = 16.sp)
            Text(detail, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        trailing?.let { Text(it, color = Muted, fontSize = 13.sp) }
        end?.invoke()
    }
}

@Composable
private fun AccountRow(account: ServiceAccount) {
    var open by remember(account.name) { mutableStateOf(false) }
    var key by remember(account.name) { mutableStateOf("") }
    var keyError by remember(account.name) { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val needsKey = account.saveKey != null && !account.connected
    SettingsRow(
        if (account.name == "Web search") LucideR.drawable.lucide_ic_globe else LucideR.drawable.lucide_ic_utensils,
        account.name,
        account.detail,
        onClick = when {
            account.connected -> ({ open = !open })
            needsKey -> ({ open = !open })
            else -> account.onConnect
        },
        end = { StatusChip(if (account.connected) "Connected" else "Connect", filled = account.connected) },
    )
    if (!open) return
    when {
        account.connected -> TextAction("Disconnect ${account.name}", onClick = { open = false; account.onDisconnect() }, color = Muted, modifier = Modifier.padding(start = RowIconSize + 16.dp))
        needsKey -> Column(Modifier.padding(start = RowIconSize + 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            PrimaryAction("Save key", onClick = { if (account.saveKey?.invoke(key) == true) key = "" else keyError = true }, enabled = key.isNotBlank())
            account.keyUrl?.let { url -> TextAction("Get a free key", onClick = { uriHandler.openUri(url) }) }
        }
    }
}

@Composable
private fun StatusChip(label: String, filled: Boolean) {
    val shape = RoundedCornerShape(50)
    val chip = if (filled) Modifier.background(Lime.copy(alpha = 0.16f), shape) else Modifier.border(1.dp, Stroke, shape)
    Box(chip.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = if (filled) Lime else Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
