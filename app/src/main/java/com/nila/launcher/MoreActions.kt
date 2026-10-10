package com.nila.launcher

// More everyday phone actions: messages, maps, web search, music, volume, settings, any installed app,
// alarms list and battery. None needs a new permission. Values come from the user's words; only a message
// to someone goes through a card, and the user still presses Send in Messages.

data class Message(val who: String, val body: String) : NilaResponse
data class Directions(val place: String) : NilaResponse
data class MapSearch(val query: String) : NilaResponse
data class WebSearch(val query: String) : NilaResponse
/** [query] is null to resume whatever was playing. */
data class PlayMusic(val query: String?) : NilaResponse
data class MediaKey(val action: String) : NilaResponse
data class SoundChange(val action: String) : NilaResponse
data class SettingsPage(val page: String) : NilaResponse
data class LaunchApp(val name: String) : NilaResponse
data object ShowAlarms : NilaResponse
data object BatteryLevel : NilaResponse

val moreCapabilities = listOf(
    CapabilityDescriptor(
        "send_message", "Draft a text message to a person; the user presses Send in Messages.",
        listOf(Param("who", ParamType.STRING), Param("body", ParamType.STRING)), PermissionClass.PREPARE, label = "Message",
    ),
    CapabilityDescriptor("directions", "Get directions to a place in Maps.", listOf(Param("place", ParamType.STRING)), PermissionClass.READ, label = "Directions"),
    CapabilityDescriptor("map_search", "Find places nearby, for example \"petrol pumps near me\".", listOf(Param("query", ParamType.STRING)), PermissionClass.READ, label = "Nearby"),
    CapabilityDescriptor("web_search", "Search the web.", listOf(Param("query", ParamType.STRING)), PermissionClass.READ, label = "Web search"),
    CapabilityDescriptor("play_music", "Play a song, artist or playlist.", listOf(Param("query", ParamType.STRING, required = false)), PermissionClass.READ, label = "Play music"),
    CapabilityDescriptor("media_control", "Pause, resume, skip to the next or go to the previous song.", listOf(Param("action", ParamType.STRING)), PermissionClass.READ, label = "Media"),
    CapabilityDescriptor("sound", "Change volume (up, down, mute) or the ring mode (silent, vibrate, ring).", listOf(Param("action", ParamType.STRING)), PermissionClass.PREPARE, label = "Sound"),
    CapabilityDescriptor("open_settings", "Open a settings page: wifi, bluetooth, battery, display, sound, location, airplane, notifications, storage, apps.", listOf(Param("page", ParamType.STRING)), PermissionClass.READ, label = "Settings"),
    CapabilityDescriptor("launch_app", "Open any installed app by name.", listOf(Param("name", ParamType.STRING)), PermissionClass.READ, label = "Open app"),
    CapabilityDescriptor("show_alarms", "Show the alarms set on this phone.", emptyList(), PermissionClass.READ, label = "Alarms"),
    CapabilityDescriptor("battery", "Tell the battery level and whether it is charging.", emptyList(), PermissionClass.READ, label = "Battery"),
)

private fun Regex.after(text: String, group: Int) = find(text)?.groupValues?.get(group)?.trim()?.trimEnd('.', '!', '?')?.takeIf { it.isNotBlank() }

private val messageLead = Regex("""^\s*(?:please\s+)?(?:tell|text|message|sms|send (?:a )?(?:message|text) to)\s+(\S+)\s+(?:that\s+|saying\s+|:\s*)?(.+)$""", RegexOption.IGNORE_CASE)

/** "tell Rahul I'll be late" → (Rahul, I'll be late). The name is one word; the rest is the message. */
fun messageParts(text: String): Pair<String, String>? = messageLead.find(text)?.let { m ->
    if (m.groupValues[1].lowercase() in setOf("me", "us", "you", "him", "her", "them")) return null
    val body = m.groupValues[2].trim().takeIf { it.isNotBlank() } ?: return null
    m.groupValues[1] to body.replaceFirstChar { it.uppercase() }
}

fun directionsTo(text: String) = Regex("""^\s*(?:please\s+)?(?:take me to|navigate to|directions to|get directions to|how do i get to|route to)\s+(.+)$""", RegexOption.IGNORE_CASE).after(text, 1)

fun nearbyQuery(text: String) = Regex("""^\s*(?:find\s+|show\s+|search\s+)?(.+?)\s+(?:near me|nearby|around me|close by)\b""", RegexOption.IGNORE_CASE).after(text, 1)

fun webQuery(text: String) = Regex("""^\s*(?:please\s+)?(?:search the web for|search for|search|google|look up)\s+(.+)$""", RegexOption.IGNORE_CASE).after(text, 1)

/** "play arijit singh" → "arijit singh"; "play music" or "resume" → resume, shown as a null query. */
fun musicQuery(text: String): String? = Regex("""^\s*(?:please\s+)?play\s+(.+)$""", RegexOption.IGNORE_CASE).after(text, 1)
    ?.takeUnless { it.lowercase() in setOf("music", "a song", "some music", "songs", "my music") }

fun mediaAction(text: String): String? {
    val lower = text.lowercase()
    return when {
        Regex("""\b(next|skip)\b.*\b(song|track)\b|^\s*(next|skip)( song| track)?\s*$""").containsMatchIn(lower) -> "next"
        Regex("""\bprevious\b.*\b(song|track)\b|^\s*previous( song| track)?\s*$""").containsMatchIn(lower) -> "previous"
        Regex("""\b(pause|stop)\b.*\b(music|song|playback|audio|video)\b|^\s*pause\s*$""").containsMatchIn(lower) -> "pause"
        Regex("""\b(resume|continue)\b.*\b(music|song|playback)\b|^\s*(resume|play music)\s*$""").containsMatchIn(lower) -> "play"
        else -> null
    }
}

fun soundAction(text: String): String? {
    val lower = text.lowercase()
    return when {
        Regex("""\bsilent\b""").containsMatchIn(lower) -> "silent"
        Regex("""\bvibrat(e|ion)\b""").containsMatchIn(lower) -> "vibrate"
        Regex("""\b(ring|normal|sound) mode\b|\bunmute\b""").containsMatchIn(lower) -> "ring"
        Regex("""\bmute\b""").containsMatchIn(lower) -> "mute"
        Regex("""\bvolume\b.*\b(up|increase|louder|raise|higher)\b|\b(increase|raise|turn up)\b.*\bvolume\b|\blouder\b""").containsMatchIn(lower) -> "up"
        Regex("""\bvolume\b.*\b(down|decrease|lower|softer|reduce)\b|\b(decrease|lower|reduce|turn down)\b.*\bvolume\b|\bsofter\b""").containsMatchIn(lower) -> "down"
        else -> null
    }
}

private val settingsPages = listOf("wifi", "bluetooth", "battery", "display", "sound", "location", "airplane", "notifications", "storage", "apps")

/** "open wifi settings" → "wifi"; "open settings" → "main". Null when not about settings. */
fun settingsPage(text: String): String? {
    val lower = text.lowercase().replace("wi-fi", "wifi").replace("brightness", "display").replace("notification ", "notifications ")
    if (!Regex("""\bsettings?\b""").containsMatchIn(lower)) return null
    return settingsPages.firstOrNull { Regex("""\b$it\b""").containsMatchIn(lower) } ?: "main"
}

/** "open whatsapp" → "whatsapp". Checked after NILA's own actions, so "open camera" stays the camera. */
fun appToOpen(text: String) = Regex("""^\s*(?:please\s+)?(?:open|launch|start)\s+(?:the\s+)?(.+?)(?:\s+app)?$""", RegexOption.IGNORE_CASE).after(text, 1)

fun wantsAlarmList(text: String) = Regex("""\b(show|list|see|check)\b.*\balarms?\b|\bwhat alarms\b|\bmy alarms\b""").containsMatchIn(text.lowercase())

fun asksBattery(text: String) = Regex("""\bbattery\b""").containsMatchIn(text.lowercase())

/** The keyword route for these actions, in priority order; null when none matches. */
fun moreActionCall(input: String): ToolCall? {
    settingsPage(input)?.let { return ToolCall("open_settings", mapOf("page" to it)) }
    if (wantsAlarmList(input)) return ToolCall("show_alarms", emptyMap())
    if (asksBattery(input)) return ToolCall("battery", emptyMap())
    soundAction(input)?.let { return ToolCall("sound", mapOf("action" to it)) }
    mediaAction(input)?.let { return ToolCall("media_control", mapOf("action" to it)) }
    messageParts(input)?.let { (who, body) -> return ToolCall("send_message", mapOf("who" to who, "body" to body)) }
    directionsTo(input)?.let { return ToolCall("directions", mapOf("place" to it)) }
    nearbyQuery(input)?.let { return ToolCall("map_search", mapOf("query" to it)) }
    webQuery(input)?.let { return ToolCall("web_search", mapOf("query" to it)) }
    if (Regex("""^\s*(?:please\s+)?play\b""", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
        return ToolCall("play_music", musicQuery(input)?.let { mapOf("query" to it) }.orEmpty())
    }
    return null
}

/** Turns a validated call into its response; null when the call is not one of these actions. */
fun moreRun(call: ToolCall): NilaResponse? {
    val args = call.args
    return when (call.capabilityId) {
        "send_message" -> Message(args["who"] as String, args["body"] as String)
        "directions" -> Directions(args["place"] as String)
        "map_search" -> MapSearch(args["query"] as String)
        "web_search" -> WebSearch(args["query"] as String)
        "play_music" -> PlayMusic(args["query"] as String?)
        "media_control" -> (args["action"] as String).takeIf { it in setOf("play", "pause", "next", "previous") }?.let { MediaKey(it) }
            ?: NilaResponse.Text("I can pause, resume, skip or go back a song.")
        "sound" -> (args["action"] as String).takeIf { it in setOf("up", "down", "mute", "silent", "vibrate", "ring") }?.let { SoundChange(it) }
            ?: NilaResponse.Text("I can turn the volume up or down, mute, or switch to silent, vibrate or ring.")
        "open_settings" -> SettingsPage((args["page"] as String).takeIf { it in settingsPages } ?: "main")
        "launch_app" -> LaunchApp(args["name"] as String)
        "show_alarms" -> ShowAlarms
        "battery" -> BatteryLevel
        else -> null
    }
}
