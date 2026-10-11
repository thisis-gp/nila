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
/** "build me an expense tracker", "I want a water counter", "can you create a packing checklist". */
private val buildApp = Regex("""^\s*(please\s+)?(can you\s+)?((make|build|create)\s+(me\s+)?|i\s+(want|need)\s+)(an?\s+)?([\w'-]+\s+){0,4}$APP_WORDS\b""")
private val listApps = Regex("""\b(my|built|nila'?s?)\s+apps\b|\bapps\s+(you|nila)\s+(made|built)\b""")
private val removeApp = Regex("""^\s*(please\s+)?(delete|remove|uninstall)\b""")
private val openApp = Regex("""^\s*(please\s+)?(open|show|launch|start)\b""")

/**
 * Skill phrases, checked before phone-app launching so "open water" opens the water app NILA built.
 * [installed] is (id, title) for each installed app; the longest title said wins.
 */
internal fun skillCall(input: String, normalized: String, installed: List<Pair<String, String>>): ToolCall? {
    if (buildApp.containsMatchIn(normalized)) return ToolCall("build_app", mapOf("idea" to input.trim()))
    if (listApps.containsMatchIn(normalized)) return ToolCall("list_built_apps", emptyMap())
    val app = installed.filter { (id, title) -> normalized.contains(title.lowercase()) || normalized.contains(id.replace('-', ' ')) }
        .maxByOrNull { it.second.length } ?: return null
    if (removeApp.containsMatchIn(normalized)) return ToolCall("remove_built_app", mapOf("app" to app.first))
    if (openApp.containsMatchIn(normalized)) return ToolCall("open_built_app", mapOf("app" to app.first))
    return null
}

internal fun skillRun(call: ToolCall): NilaResponse? = when (call.capabilityId) {
    "build_app" -> BuildMiniApp(call.args["idea"] as String)
    "open_built_app" -> OpenMiniApp(call.args["app"] as String)
    "list_built_apps" -> ShowMiniApps
    "remove_built_app" -> RemoveMiniApp(call.args["app"] as String)
    else -> null
}
