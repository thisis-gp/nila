package com.nila.launcher

import android.app.ActivityManager
import android.content.Context

internal enum class ModelCapability { TEXT, AUDIO, VISION, TOOLS }

internal data class DeviceMemory(
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemory: Boolean,
)

internal data class ModelSpec(
    val id: String,
    val filename: String,
    val label: String,
    val capabilities: Set<ModelCapability>,
    val minimumAvailableBytes: Long,
) {
    fun missingReason(capability: ModelCapability): String? = when (capability) {
        ModelCapability.AUDIO -> "Voice input needs an audio-capable on-device model. You can still type."
        ModelCapability.VISION -> "Report image reading needs a vision-capable on-device model."
        else -> null
    }.takeIf { capability !in capabilities }
}

internal const val GIB = 1024L * 1024 * 1024

internal data class ModelOption(val model: ModelSpec, val installed: Boolean, val fitsMemory: Boolean) {
    val reason: String get() = when {
        !installed -> "Model file not installed on this phone"
        !fitsMemory -> "Not enough free memory to load safely right now"
        else -> "Ready · loads when needed"
    }
}

internal object ModelCatalog {
    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b",
        filename = "gemma-4-E2B-it.litertlm",
        label = "Gemma 4 E2B",
        capabilities = setOf(ModelCapability.TEXT, ModelCapability.AUDIO, ModelCapability.VISION, ModelCapability.TOOLS),
        minimumAvailableBytes = 4 * GIB,
    )

    val GEMMA_3_1B = ModelSpec(
        id = "gemma-3-1b-it",
        filename = "gemma3-1b-it-int4.litertlm",
        label = "Gemma 3 1B · text",
        capabilities = setOf(ModelCapability.TEXT),
        minimumAvailableBytes = GIB + GIB / 4,
    )

    /** The conversational floor: same class as FunctionGemma, measured loading on the Pixel 4a. */
    val GEMMA_3_270M = ModelSpec(
        id = "gemma-3-270m-it",
        filename = "gemma3-270m-it-q8.litertlm",
        label = "Gemma 3 270M · text",
        capabilities = setOf(ModelCapability.TEXT),
        minimumAvailableBytes = GIB,
    )

    val assistants = listOf(GEMMA_3_270M, GEMMA_3_1B, GEMMA_4_E2B)

    val FUNCTION_GEMMA = ModelSpec(
        id = "functiongemma-run8-q8-ekv4096",
        filename = "nila-router-functiongemma-run8-q8-ekv4096.litertlm",
        label = "FunctionGemma run 8",
        capabilities = setOf(ModelCapability.TOOLS),
        minimumAvailableBytes = GIB,
    )

    fun deviceMemory(context: Context): DeviceMemory {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return DeviceMemory(info.totalMem, info.availMem, info.lowMemory)
    }

    /** GPU-first load measured hanging (never falling back) on the Pixel 4a; big-RAM phones keep GPU. */
    fun assistantGpuFirst(memory: DeviceMemory): Boolean = memory.totalBytes >= 8 * GIB

    fun choices(installed: Set<String>, memory: DeviceMemory): List<ModelOption> = assistants.map { model ->
        ModelOption(model, model.filename in installed, !memory.lowMemory && memory.availableBytes >= model.minimumAvailableBytes)
    }

    fun selectAssistant(installed: Set<String>, memory: DeviceMemory, preferredId: String? = null): ModelSpec? =
        choices(installed, memory).let { options ->
            options.firstOrNull { it.model.id == preferredId && it.installed && it.fitsMemory }?.model
                ?: options.filter { it.installed && it.fitsMemory }.minByOrNull { it.model.minimumAvailableBytes }?.model
        }

}

internal fun modelStatus(model: ModelSpec?, inferenceFailed: Boolean = false): String = buildString {
    if (model == null) {
        append("No compatible general assistant model installed")
    } else {
        append(model.label)
        append(" · ")
        append(if (ModelCapability.AUDIO in model.capabilities) "voice ready" else "text only; voice unavailable")
        if (ModelCapability.VISION !in model.capabilities) append("; report image reading unavailable")
    }
    if (inferenceFailed) append(" · assistant reply failed; using fallback")
}
