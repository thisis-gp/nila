package com.nila.launcher

// Shared contracts from TEAM_BRIEF.md. Keep them small: add a field only when a working flow needs it.

/** Read and Prepare run automatically. Commit always stops for explicit confirmation. */
enum class PermissionClass { READ, PREPARE, COMMIT }

/** Every result and component says where its data came from, so fixtures are never shown as real. */
enum class DataSource { REAL, FIXTURE, SIMULATED }

/** PAISE is a whole, non-negative amount of money in paise, shown to people as rupees. */
enum class ParamType { STRING, INT, NUMBER, BOOLEAN, PAISE }

/** [label] is the name people see, for example on a confirmation card. */
data class Param(val name: String, val type: ParamType, val required: Boolean = true, val label: String = name)

data class CapabilityDescriptor(
    val id: String,
    val description: String,
    val params: List<Param>,
    val permission: PermissionClass,
    /** Android permissions the capability needs. They must already be declared in the APK manifest. */
    val androidPermissions: Set<String> = emptySet(),
) {
    val needsConfirmation get() = permission == PermissionClass.COMMIT
}

/** One function call proposed by FunctionGemma. Untrusted until [checkCall] returns Ok. */
data class ToolCall(val capabilityId: String, val args: Map<String, Any>)

/** FunctionGemma's routing decision. */
sealed interface NilaIntent {
    data class Call(val call: ToolCall) : NilaIntent
    /** No tool fits; Gemma answers in conversation. */
    data class Converse(val text: String) : NilaIntent
}

sealed interface CallCheck {
    data class Ok(val call: ToolCall, val capability: CapabilityDescriptor) : CallCheck
    /** Ask the user for these params, then route again. */
    data class Missing(val capabilityId: String, val params: List<String>) : CallCheck
    data class Rejected(val reason: String) : CallCheck
}

/** Boundary check for model output: known tool, no unknown args, correct types, required args present. */
fun checkCall(call: ToolCall, registry: Map<String, CapabilityDescriptor>): CallCheck {
    val capability = registry[call.capabilityId] ?: return CallCheck.Rejected("Unknown tool ${call.capabilityId}")
    val params = capability.params.associateBy { it.name }
    for ((name, value) in call.args) {
        val param = params[name] ?: return CallCheck.Rejected("Unknown argument $name")
        val typeOk = when (param.type) {
            ParamType.STRING -> value is String
            ParamType.INT -> value is Int
            ParamType.NUMBER -> value is Number
            ParamType.BOOLEAN -> value is Boolean
            ParamType.PAISE -> (value is Int || value is Long) && (value as Number).toLong() >= 0
        }
        if (!typeOk) return CallCheck.Rejected("Argument $name must be ${param.type}")
    }
    val missing = capability.params.filter { it.required && it.name !in call.args }.map { it.name }
    // Ok carries a copy of the arguments: a caller's mutable map changing later cannot alter what was checked or shown.
    return if (missing.isEmpty()) CallCheck.Ok(call.copy(args = call.args.toMap()), capability) else CallCheck.Missing(capability.id, missing)
}

/** Provider results in one shape so they can be compared. Money is in paise. */
data class ResultItem(
    val id: String,
    val title: String,
    val detail: String,
    val pricePaise: Long? = null,
    val etaMinutes: Int? = null,
)

data class NormalizedResult(
    val capabilityId: String,
    val source: DataSource,
    val items: List<ResultItem>,
)

/** The only UI a model or skill can request. The launcher renders each case with native Compose code. */
sealed interface TrustedComponent {
    data class Text(val value: String) : TrustedComponent
    data class Cards(val result: NormalizedResult) : TrustedComponent
    /** Shows provider, account, destination, data shared and final price before a Commit call runs. */
    data class Confirmation(val call: ToolCall, val lines: List<Pair<String, String>>) : TrustedComponent
}

/** A generated skill is data interpreted by trusted code, never executable code. */
data class SkillManifest(
    val id: String,
    val name: String,
    val purpose: String,
    val version: Int,
    val triggers: List<String>,
    val capabilityIds: List<String>,
    val androidPermissions: Set<String>,
    val ui: List<TrustedComponent>,
    val enabled: Boolean = true,
)

enum class MemoryType { SEMANTIC, EPISODIC, SKILL }

/** Durable memory. Vector similarity may suggest context but never authorizes an action. */
data class MemoryRecord(
    val id: String,
    val type: MemoryType,
    val text: String,
    val source: String,
    val confidence: Float,
    val sensitive: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long,
    val expiresAt: Long? = null,
    val skillId: String? = null,
    val deleted: Boolean = false,
)

enum class ActionStatus { EXECUTED, CANCELLED, FAILED }

/** Local history entry for every Prepare or Commit action. */
data class ActionReceipt(
    val call: ToolCall,
    val permission: PermissionClass,
    val status: ActionStatus,
    val message: String,
    val at: Long,
)
