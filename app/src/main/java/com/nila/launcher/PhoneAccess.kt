package com.nila.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** One thing NILA can reach on the phone: what it is for, and the Android permissions behind it. */
internal data class PhoneAccess(val name: String, val why: String, val permissions: Array<String>)

/** Everything My NILA can use on this phone, each for a reason the user can read. */
internal fun phoneAccesses(): List<PhoneAccess> = listOfNotNull(
    PhoneAccess("Photos and videos", "Find what you took on a day: \"photos from 18 December 2024\".", mediaPermissions),
    PhoneAccess("Contacts", "Call or message people by name.", arrayOf(Manifest.permission.READ_CONTACTS)),
    PhoneAccess("Microphone", "Talk to NILA in English, हिंदी or മലയാളം.", arrayOf(Manifest.permission.RECORD_AUDIO)),
    PhoneAccess("Location", "Deliver food and groceries to where you are.", arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)),
    PhoneAccess("Physical activity", "Count your steps for the fitness tracker.", arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)),
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PhoneAccess("Notifications", "My NILA reminders: bills, follow-ups.", arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    } else null,
)

internal fun granted(context: Context, access: PhoneAccess) =
    access.permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/**
 * My NILA › Phone access: what NILA can reach, each with why, and one button for all of it. Android still
 * asks for each permission itself; a permission refused twice is changed in Android's app settings.
 */
@Composable
internal fun PhoneAccessTab() {
    val context = LocalContext.current
    var checks by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checks++ }
    val accesses = remember(checks) { phoneAccesses().map { it to granted(context, it) } }
    val missing = accesses.filterNot { it.second }.flatMap { it.first.permissions.toList() }

    Text("Give NILA access once and it can answer from your phone. Everything stays on this phone.", color = Muted, fontSize = 13.sp)
    if (missing.isNotEmpty()) PrimaryAction("Allow everything", onClick = { ask.launch(missing.toTypedArray()) })
    else NilaCard { Text("NILA has full access. You can turn anything off in Android settings.", color = Lime, fontWeight = FontWeight.SemiBold) }
    accesses.forEach { (access, on) ->
        Surface(color = CardInk, shape = RoundedCornerShape(CardRadius), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(access.name, color = Paper, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(access.why, color = Muted, fontSize = 13.sp)
                }
                if (on) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).background(Lime, CircleShape))
                        Text("On", color = Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    TextAction("Allow", onClick = { ask.launch(access.permissions) })
                }
            }
        }
    }
    TextAction("Open Android's settings for NILA", onClick = {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }, color = Muted)
    Text("On Android 14, choose \"Allow all\" for photos: with \"selected photos\" NILA can only search those.", color = Muted, fontSize = 12.sp)
}
