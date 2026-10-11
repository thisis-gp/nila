package com.nila.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

internal const val ONBOARDED_KEY = "onboarded"

private val BigMascot = 140.dp
private val SmallMascot = 64.dp
private val LineSize = 34.sp
private const val STEP_COUNT = 4

private enum class Step { Name, Food, Permissions, Done }

private data class Ask(val title: String, val why: String, val permission: String?)

private val asks = listOf(
    Ask("Your microphone", "So you can talk to me instead of typing", Manifest.permission.RECORD_AUDIO),
    Ask("Your location", "So food comes to where you are", Manifest.permission.ACCESS_COARSE_LOCATION),
    // Notifications need a permission only on Android 13 and newer.
    Ask("Notifications", "So I can tell you when an order is near", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null),
)

private val dietChoices = listOf("Vegetarian", "Non-veg", "Jain")
private const val PEANUTS = "Allergic to peanuts"

/**
 * A short chat with NILA on first launch: your name, food basics, three permissions in plain words, then
 * a first suggestion. Name and food answers are saved as "me" facts. No contacts step: NILA asks for those
 * later, inside chat, when a person comes up.
 */
@Composable
internal fun OnboardingScreen(
    onSaveFact: (text: String, kind: MemoryKind, about: String) -> Unit,
    onFinish: (request: String?) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(Step.Name) }
    var name by rememberSaveable { mutableStateOf("") }
    var diet by rememberSaveable { mutableStateOf<String?>(null) }
    var peanuts by rememberSaveable { mutableStateOf(false) }
    var other by rememberSaveable { mutableStateOf(false) }
    var otherText by rememberSaveable { mutableStateOf("") }

    fun saveName() {
        name = name.trim()
        onSaveFact("My name is $name", MemoryKind.Other, ME)
        step = Step.Food
    }

    fun saveFood() {
        diet?.let { onSaveFact(it, MemoryKind.Diet, ME) }
        if (peanuts) onSaveFact(PEANUTS, MemoryKind.Allergy, ME)
        if (other && otherText.isNotBlank()) onSaveFact(otherText.trim(), MemoryKind.Other, ME)
        step = Step.Permissions
    }

    Column(
        Modifier.fillMaxSize().background(Ink).drawBehind { drawAgentAtmosphere() }.safeDrawingPadding().imePadding().padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("${step.ordinal + 1} of $STEP_COUNT", color = Muted, fontSize = 13.sp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
            when (step) {
                Step.Name -> NameStep(name, onName = { name = it }, onNext = ::saveName)
                Step.Food -> FoodStep(
                    name, diet, peanuts, other, otherText,
                    onDiet = { diet = if (diet == it) null else it },
                    onPeanuts = { peanuts = !peanuts },
                    onOther = { other = !other },
                    onOtherText = { otherText = it },
                    onNext = ::saveFood,
                    onSkip = { step = Step.Permissions },
                )
                Step.Permissions -> PermissionsStep(onNext = { step = Step.Done })
                Step.Done -> DoneStep(name, diet, onFinish)
            }
        }
    }
}

@Composable
private fun Speaker(mood: MascotMood, size: androidx.compose.ui.unit.Dp) {
    NilaMascot(mood = mood, size = size)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, onNext: () -> Unit) {
    Speaker(MascotMood.Greeting, BigMascot)
    SerifTitle("Hi, I'm NILA. What should I call you?", size = LineSize)
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        placeholder = { Text("Your name", color = Muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onNext() }),
        shape = RoundedCornerShape(ActionRadius),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text("You can change this later in My NILA.", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
    PrimaryAction("Next", onNext, enabled = name.isNotBlank())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FoodStep(
    name: String,
    diet: String?,
    peanuts: Boolean,
    other: Boolean,
    otherText: String,
    onDiet: (String) -> Unit,
    onPeanuts: () -> Unit,
    onOther: () -> Unit,
    onOtherText: (String) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
) {
    Speaker(MascotMood.Talking, SmallMascot)
    SerifTitle("Nice to meet you, $name. Anything I should know before I order food for you?", size = LineSize)
    Spacer(Modifier.height(24.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        dietChoices.forEach { Choice(it, diet == it) { onDiet(it) } }
        Choice(PEANUTS, peanuts, onPeanuts)
        Choice("Something else", other, onOther)
    }
    if (other) {
        OutlinedTextField(
            value = otherText,
            onValueChange = onOtherText,
            placeholder = { Text("For example: no onion or garlic", color = Muted) },
            singleLine = true,
            shape = RoundedCornerShape(ActionRadius),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
    Spacer(Modifier.height(24.dp))
    PrimaryAction("Next", onNext, enabled = diet != null || peanuts || (other && otherText.isNotBlank()))
    TextAction("Skip for now", onSkip, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
}

@Composable
private fun ColumnScope.PermissionsStep(onNext: () -> Unit) {
    Speaker(MascotMood.Talking, SmallMascot)
    SerifTitle("To help properly, I'd like to ask for three things.", size = LineSize)
    Text("Each one is optional. You can change them in Android settings any time.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
    asks.forEach { PermissionRow(it) }
    Spacer(Modifier.height(24.dp))
    PrimaryAction("Continue", onNext)
    TextAction("Not now", onNext, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
}

@Composable
private fun PermissionRow(ask: Ask) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ask.permission == null || isGranted(context, ask.permission)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(ask.title, color = Paper, fontSize = 17.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif)
            Text(ask.why, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (allowed) {
            Text("Allowed", color = Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else {
            Text(
                "Allow",
                color = Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Paper).clickable { ask.permission?.let(request::launch) }.padding(horizontal = 18.dp, vertical = 9.dp),
            )
        }
    }
}

private fun isGranted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ColumnScope.DoneStep(name: String, diet: String?, onFinish: (String?) -> Unit) {
    // A suggestion that fits what they just said; it is an offer, not a claim about their past orders.
    val dish = if (diet == "Non-veg") "dosa" else "idli"
    Speaker(MascotMood.Celebrate, BigMascot)
    SerifTitle("All set, $name.", size = LineSize)
    Text("I'll ask about the people in your life when they come up in chat.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(24.dp))
    SerifTitle("How about $dish for breakfast? I can look for places nearby.", size = 22.sp, color = Paper)
    Spacer(Modifier.height(24.dp))
    PrimaryAction("Sounds good", { onFinish("order $dish") })
    TextAction("Take me home", { onFinish(null) }, color = Muted, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).then(if (selected) Modifier.background(Lime) else Modifier.border(1.dp, Stroke, shape)).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (selected) Ink else Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Paper,
    unfocusedTextColor = Paper,
    focusedBorderColor = Lime,
    unfocusedBorderColor = Stroke,
    cursorColor = Lime,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)
