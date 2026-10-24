package com.nila.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ScreenGuidanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember(context) { loadInstalledApps(context) }
    val store = remember(context) { ScreenGuidanceStore(context.applicationContext) }
    var selected by remember { mutableStateOf<InstalledApp?>(null) }
    var version by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var targets by remember { mutableStateOf<List<ScreenTarget>>(emptyList()) }
    var saved by remember { mutableStateOf<ScreenMapping?>(null) }

    LaunchedEffect(selected?.packageName) {
        val app = selected ?: return@LaunchedEffect
        version = null
        version = runCatching {
            val info = context.packageManager.getPackageInfo(app.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }.getOrNull()
        val installedVersion = version ?: return@LaunchedEffect
        saved = runCatching { withContext(Dispatchers.IO) { store.get(app.packageName, installedVersion) } }.getOrNull()
        targets = saved?.targets.orEmpty()
        message = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val app = selected
        if (uri == null || app == null || version == null) return@rememberLauncherForActivityResult
        busy = true
        targets = emptyList()
        saved = null
        message = "Reading this screenshot on your phone…"
        scope.launch {
            var jpeg: ByteArray? = null
            try {
                jpeg = withContext(Dispatchers.IO) { screenshotJpeg(context, uri) }
                if (jpeg == null) {
                    message = "Couldn't read that image. Choose a screenshot image and try again."
                } else when (val result = ScreenGuidanceRuntime.suggest(jpeg)) {
                    ScreenTargetResult.Unavailable -> message = "Screen guidance needs the vision-capable Gemma 4 E2B model and at least 1 GiB free memory at inference. The model selector requires 4 GiB free before loading, so it may be unavailable on Pixel 4a."
                    ScreenTargetResult.Unreadable -> message = "The on-device model couldn't identify clear controls in that screenshot."
                    is ScreenTargetResult.Suggestions -> {
                        targets = result.targets
                        message = if (result.targets.isEmpty()) "No clear controls found. Try a screenshot with the relevant screen visible." else "Suggestions only — NILA will not tap or act. Save only if the app and version are correct."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                message = "Couldn't analyze that screenshot on this phone. Nothing was uploaded or saved."
            } finally {
                jpeg?.fill(0)
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).padding(horizontal = ScreenGutter, vertical = 12.dp)) {
        RoundButton(com.composables.icons.lucide.R.drawable.lucide_ic_chevron_left, "Back", onBack)
        Text("Screen guidance", color = Paper, fontSize = 30.sp, modifier = Modifier.padding(top = 10.dp))
        Text("Choose an app, then select a screenshot. NILA suggests visible controls using the selected on-device vision model. Screenshots are processed in memory and never saved by NILA.", color = Muted, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Box {
                OutlinedTextField(
                    value = selected?.label.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Choose the app shown") },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Box(Modifier.matchParentSize().clickable(enabled = !busy) { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(Panel)) {
                    apps.forEach { app ->
                        DropdownMenuItem(text = { Text(app.label, color = Paper) }, onClick = {
                            version = null
                            saved = null
                            targets = emptyList()
                            message = null
                            selected = app
                            menu = false
                        })
                    }
                }
            }
            selected?.let { app ->
                val currentVersion = version
                Text("${app.packageName} · version ${currentVersion ?: "unknown"}", color = Muted, fontSize = 12.sp)
            }
            PrimaryAction("Choose screenshot", { if (!busy && selected != null && version != null) picker.launch("image/*") }, enabled = !busy && selected != null && version != null)
            if (busy) Text("Working locally…", color = Lime)
            message?.let { Text(it, color = Muted, lineHeight = 20.sp) }
            saved?.let { Text("Saved mapping for app version ${it.appVersion}", color = Lime) }
            targets.forEachIndexed { index, target ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(14.dp)).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${index + 1}. ${target.label}", color = Paper, modifier = Modifier.weight(1f))
                    Text("${(target.x * 100).toInt()}%, ${(target.y * 100).toInt()}%", color = Muted)
                }
            }
            if (targets.isNotEmpty() && saved == null && selected != null && version != null) {
                PrimaryAction("Save mapping for this app version", {
                    val mapping = ScreenMapping(selected!!.packageName, version!!, targets)
                    busy = true
                    scope.launch {
                        val ok = runCatching { withContext(Dispatchers.IO) { store.save(mapping) } }.isSuccess
                        if (ok) { saved = mapping; message = "Saved on this phone. It will be discarded after the app version changes." }
                        else message = "Couldn't encrypt and save this mapping. Nothing was saved."
                        busy = false
                    }
                })
            }
        }
        Text("No automatic taps · no screenshot upload · saved target metadata is encrypted and invalidated when the app version changes.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

private fun screenshotJpeg(context: Context, uri: Uri): ByteArray? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 1280) sample *= 2
    val bitmap = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    return try {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
