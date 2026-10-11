package com.nila.launcher.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nila.launcher.Muted
import com.nila.launcher.Paper
import com.nila.launcher.PrimaryAction
import com.nila.launcher.SectionLabel
import com.nila.launcher.SerifTitle
import com.nila.launcher.TextAction
import java.io.File
import java.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Plain words for what an app may touch, shown before install (spec §7.5, §8). */
private val PermissionText = mapOf(
    "storage.local" to "Keeps its own data on this phone",
    "transactions.read" to "Reads your bank and UPI payments on this phone",
)
private const val PREVIEW_DIR = "skill-preview"
/** Bank and UPI reading is sub-project 3; until then money apps show their empty state. */
private val NoTransactionsYet = TransactionSource { emptyList() }

/** An installed app, opened full screen from NILA ("open water"). */
@Composable
fun MiniAppPage(skills: SkillStore, appId: String, onBack: () -> Unit) {
    val app = remember(appId) { skills.get(appId) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(app?.title ?: "App", onBack)
        if (app == null) {
            Text("That app is no longer installed.", color = Muted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        val clock = remember { Clock.systemDefaultZone() }
        val runtime = remember(appId) { AppRuntime(app.recipe, app.grants, skills.data, NoTransactionsYet, clock) }
        var revision by remember { mutableIntStateOf(0) }
        val state = remember(revision) { MiniAppModel.build(app.recipe, runtime, clock) }
        MiniAppScreen(
            state = state,
            actions = { action, target, values ->
                runtime.dispatch(action, target, values)
                revision++
            },
            onGrant = {},
        )
    }
}

/** What Gemma built, shown before anything is installed: a live preview, what it may touch, Install or Cancel. */
@Composable
fun AppReviewPage(result: BuildResult, onInstall: (JsonObject) -> Unit, onCancel: () -> Unit) {
    val recipe = when (result) {
        is BuildResult.Built -> result.recipe
        is BuildResult.Fallback -> result.recipe
        is BuildResult.Gap -> null
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("New app", onCancel)
        if (recipe == null) {
            Text((result as BuildResult.Gap).reason, color = Paper, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
            return@Column
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SerifTitle(recipe.string("title").orEmpty())
            if (result is BuildResult.Fallback) Text("I couldn't shape it exactly, so this is the closest app I can build.", color = Muted, fontSize = 13.sp)
            SectionLabel("This app can")
            (recipe["permissions"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.forEach { permission ->
                Text("• ${PermissionText[permission] ?: permission}", color = Paper, fontSize = 13.sp)
            }
        }
        val context = LocalContext.current
        val preview = remember(recipe) {
            val clock = Clock.systemDefaultZone()
            val store = FileAppStore(File(context.cacheDir, PREVIEW_DIR))
            val grants = (recipe["permissions"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }.toSet()
            MiniAppModel.build(recipe, AppRuntime(recipe, grants, store, NoTransactionsYet, clock), clock)
        }
        Column(Modifier.weight(1f)) { MiniAppScreen(preview, actions = { _, _, _ -> }, onGrant = {}) }
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextAction("Cancel", onCancel)
            PrimaryAction("Install", { onInstall(recipe) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TextAction("Back", onBack)
        Text(title.uppercase(), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}
