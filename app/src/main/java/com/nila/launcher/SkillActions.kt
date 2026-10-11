package com.nila.launcher

// Apps NILA builds from the user's idea (skills): make, open, list and remove them by voice. Building and opening
// happen in NILA's own screens. Removing deletes the app's data, so it stops at a confirmation card.

data class BuildMiniApp(val idea: String) : NilaResponse
data class OpenMiniApp(val appId: String) : NilaResponse
data object ShowMiniApps : NilaResponse
data class RemoveMiniApp(val appId: String) : NilaResponse

val skillCapabilities = listOf(
    CapabilityDescriptor("build_app", "Build a small app from the user's idea, like an expense tracker or a water counter", listOf(Param("idea", ParamType.STRING, label = "Idea")), PermissionClass.PREPARE, label = "Build an app"),
    CapabilityDescriptor("open_built_app", "Open an app NILA built", listOf(Param("app", ParamType.STRING, label = "App")), PermissionClass.READ, label = "Open app"),
    CapabilityDescriptor("list_built_apps", "Show the apps NILA built", emptyList(), PermissionClass.READ, label = "My apps"),
    CapabilityDescriptor("remove_built_app", "Delete an app NILA built and everything saved in it", listOf(Param("app", ParamType.STRING, label = "App")), PermissionClass.COMMIT, label = "Delete app"),
)

private const val APP_WORDS = """(app|tracker|counter|checklist|log|dashboard|list)"""
private const val REMOVE_VERBS = "(delete|remove|uninstall)"
private const val OPEN_VERBS = "(open|show|launch|start)"

/**
 * "build me an expense tracker", "I want a water counter", "can you create a packing checklist". Not app ideas:
 * "I want to open the Swiggy app", "I need to log my run", "make a list of things to buy". So "want/need" must be
 * followed by "a/an", no filler word may be "to", and "list of" is a list, not an app.
 */
private val buildApp = Regex(
    """^\s*(please\s+)?(can you\s+)?((make|build|create)\s+(me\s+)?(an?\s+)?|i\s+(want|need)\s+an?\s+)((?!to\b)[\w'-]+\s+){0,4}$APP_WORDS\b(?!\s+of\b)""",
)
private val listApps = Regex("""\b(my|built|nila'?s?)\s+apps\b|\bapps\s+(you|nila)\s+(made|built)\b""")

/** Skill phrases, checked before phone-app launching so "open water" opens the water app NILA built. */
internal fun skillCall(input: String, normalized: String, installed: List<Pair<String, String>>): ToolCall? {
    if (buildApp.containsMatchIn(normalized)) return ToolCall("build_app", mapOf("idea" to input.trim()))
    if (listApps.containsMatchIn(normalized)) return ToolCall("list_built_apps", emptyMap())
    said(normalized, REMOVE_VERBS, installed)?.let { return ToolCall("remove_built_app", mapOf("app" to it)) }
    said(normalized, OPEN_VERBS, installed)?.let { return ToolCall("open_built_app", mapOf("app" to it)) }
    return null
}

/**
 * The installed app named as the whole object of [verbs]: "open water", "delete the water app"; never "show me water
 * parks near me". [installed] is (id, title); longer titles are tried first.
 */
private fun said(normalized: String, verbs: String, installed: List<Pair<String, String>>): String? =
    installed.sortedByDescending { it.second.length }.firstOrNull { (id, title) ->
        val name = "(${Regex.escape(title.lowercase())}|${Regex.escape(id.replace('-', ' '))})"
        Regex("""^\s*(please\s+)?$verbs\s+(my\s+|the\s+)?$name(\s+app)?\s*[.!?]?\s*$""").containsMatchIn(normalized)
    }?.first

internal fun skillRun(call: ToolCall): NilaResponse? = when (call.capabilityId) {
    "build_app" -> BuildMiniApp(call.args["idea"] as String)
    "open_built_app" -> OpenMiniApp(call.args["app"] as String)
    "list_built_apps" -> ShowMiniApps
    "remove_built_app" -> RemoveMiniApp(call.args["app"] as String)
    else -> null
}
