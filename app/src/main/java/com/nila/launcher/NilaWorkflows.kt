package com.nila.launcher

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NilaWorkflowStep(
    val id: String,
    val capabilityId: String,
    val arguments: Map<String, String>,
    val dependsOn: List<String> = emptyList(),
)

data class NilaWorkflow(
    val id: String,
    val name: String,
    val trigger: String,
    val steps: List<NilaWorkflowStep>,
    val enabled: Boolean = true,
)

internal data class NilaWorkflowRun(val workflow: NilaWorkflow, val calls: List<ToolCall>, val nextIndex: Int, val pendingToken: String? = null)

internal const val MAX_NILA_WORKFLOWS = 20
internal const val MAX_NILA_WORKFLOW_STEPS = 8
internal val WORKFLOW_AUTORUN_CAPABILITIES = setOf("battery", "show_notes", "sound", "set_timer", "timer_control", "add_note", "flashlight")

/**
 * Dynamically discovered MCP tools (`mcp_…`) confirm and run through the interactive chat flow, which a
 * workflow run cannot host: its Confirm step would produce an invocation nobody executes. Workflows stay
 * on the built-in capability registry.
 */
private const val DYNAMIC_MCP_ID_PREFIX = "mcp_"
private const val MAX_WORKFLOW_BYTES = 16_384

internal fun workflowCanRunWithoutHandoff(capability: CapabilityDescriptor) =
    !capability.needsConfirmation && capability.id in WORKFLOW_AUTORUN_CAPABILITIES

/** Data-only workflows: every step is validated against the same trusted capability registry as chat. */
internal fun workflowCalls(workflow: NilaWorkflow, registry: Map<String, CapabilityDescriptor>): List<ToolCall>? {
    if (workflow.id.length !in 1..64 || workflow.name.trim().length !in 1..60 || workflow.trigger.trim().length !in 1..120 ||
        workflow.steps.isEmpty() || workflow.steps.size > MAX_NILA_WORKFLOW_STEPS ||
        workflow.steps.map { it.id }.distinct().size != workflow.steps.size ||
        workflow.steps.any { it.id.length !in 1..64 || it.dependsOn.size > MAX_NILA_WORKFLOW_STEPS }
    ) return null

    val byId = workflow.steps.associateBy { it.id }
    if (workflow.steps.any { step -> step.dependsOn.any { it !in byId } }) return null
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    fun cyclic(id: String): Boolean {
        if (id in visiting) return true
        if (id in visited) return false
        visiting += id
        if (byId.getValue(id).dependsOn.any(::cyclic)) return true
        visiting -= id
        visited += id
        return false
    }
    if (workflow.steps.any { cyclic(it.id) }) return null

    val positions = workflow.steps.mapIndexed { index, step -> step.id to index }.toMap()
    if (workflow.steps.any { step -> step.dependsOn.any { positions.getValue(it) >= positions.getValue(step.id) } }) return null
    return workflow.steps.map { step ->
        if (step.arguments.size > 12 || step.arguments.any { (key, value) -> key.length !in 1..64 || value.length > 256 }) return null
        if (step.capabilityId.startsWith(DYNAMIC_MCP_ID_PREFIX)) return null
        val capability = registry[step.capabilityId] ?: return null
        val call = ToolCall(step.capabilityId, coerceArgs(step.arguments.filterValues(String::isNotBlank), capability))
        (checkCall(call, registry) as? CallCheck.Ok)?.call ?: return null
    }
}

internal fun matchingWorkflow(input: String, workflows: List<NilaWorkflow>): NilaWorkflow? {
    if (input.length > 120) return null
    val key = skillTriggerKey(input)
    return workflows.firstOrNull { it.enabled && key.isNotBlank() && skillTriggerKey(it.trigger) == key }
}

internal fun parseWorkflowSteps(raw: String, registry: Map<String, CapabilityDescriptor>): List<NilaWorkflowStep>? {
    if (raw.length > 4_000) return null
    val lines = raw.lines().filter(String::isNotBlank)
    if (lines.isEmpty() || lines.size > MAX_NILA_WORKFLOW_STEPS) return null
    val steps = lines.mapIndexed { index, line ->
        val capabilityId = line.substringBefore(':').trim()
        if (capabilityId.startsWith(DYNAMIC_MCP_ID_PREFIX)) return null
        val args = if (':' in line) parseSkillArguments(line.substringAfter(':')) ?: return null else emptyMap()
        val capability = registry[capabilityId] ?: return null
        NilaWorkflowStep("step${index + 1}", capabilityId, args, if (index == 0) emptyList() else listOf("step$index"))
            .also { step ->
                val call = ToolCall(capabilityId, coerceArgs(args.filterValues(String::isNotBlank), capability))
                if (checkCall(call, registry) !is CallCheck.Ok) return null
            }
    }
    return steps
}

internal fun encodeWorkflows(workflows: List<NilaWorkflow>): String = JSONArray().apply {
    workflows.take(MAX_NILA_WORKFLOWS).forEach { workflow ->
        put(JSONObject().apply {
            put("id", workflow.id)
            put("name", workflow.name)
            put("trigger", workflow.trigger)
            put("enabled", workflow.enabled)
            put("steps", JSONArray().apply {
                workflow.steps.take(MAX_NILA_WORKFLOW_STEPS).forEach { step ->
                    put(JSONObject().apply {
                        put("id", step.id)
                        put("capabilityId", step.capabilityId)
                        put("arguments", JSONObject(step.arguments))
                        put("dependsOn", JSONArray(step.dependsOn))
                    })
                }
            })
        })
    }
}.toString()

internal fun decodeWorkflows(raw: String?): List<NilaWorkflow> {
    if (raw == null || raw.length > MAX_WORKFLOW_BYTES) return emptyList()
    return runCatching {
        val items = JSONArray(raw)
        if (items.length() > MAX_NILA_WORKFLOWS) return emptyList()
        (0 until minOf(items.length(), MAX_NILA_WORKFLOWS)).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val steps = item.optJSONArray("steps") ?: return@mapNotNull null
            if (steps.length() !in 1..MAX_NILA_WORKFLOW_STEPS) return@mapNotNull null
            val decodedSteps = (0 until steps.length()).map { stepIndex ->
                val step = steps.optJSONObject(stepIndex) ?: return@mapNotNull null
                val args = step.optJSONObject("arguments") ?: JSONObject()
                val dependsOn = step.optJSONArray("dependsOn") ?: JSONArray()
                if (args.length() > 12 || dependsOn.length() > MAX_NILA_WORKFLOW_STEPS) return@mapNotNull null
                NilaWorkflowStep(
                    id = step.optString("id"),
                    capabilityId = step.optString("capabilityId"),
                    arguments = args.keys().asSequence().take(12).associateWith { args.optString(it) },
                    dependsOn = (0 until minOf(dependsOn.length(), MAX_NILA_WORKFLOW_STEPS)).map { dependsOn.optString(it) },
                )
            }
            NilaWorkflow(
                id = item.optString("id"), name = item.optString("name"), trigger = item.optString("trigger"),
                steps = decodedSteps, enabled = item.optBoolean("enabled", true),
            )
        }
    }.getOrDefault(emptyList())
}

internal class NilaWorkflowStore(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey by lazy { MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext, "nila_workflows", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun all() = decodeWorkflows(prefs.getString(KEY, null))

    fun save(workflow: NilaWorkflow, registry: Map<String, CapabilityDescriptor>): Boolean {
        if (workflowCalls(workflow, registry) == null) return false
        val updated = all().filterNot { it.id == workflow.id } + workflow
        val encoded = encodeWorkflows(updated)
        if (updated.size > MAX_NILA_WORKFLOWS || encoded.length > MAX_WORKFLOW_BYTES) return false
        return runCatching { prefs.edit().putString(KEY, encoded).commit() }.getOrDefault(false)
    }

    fun delete(id: String) { runCatching { prefs.edit().putString(KEY, encodeWorkflows(all().filterNot { it.id == id })).apply() } }

    companion object {
        private const val KEY = "workflows"
        fun newId() = UUID.randomUUID().toString()
    }
}
