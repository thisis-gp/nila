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

internal enum class RouterLoadPolicy { ON_DEMAND, RESIDENT }
internal const val GIB = 1024L * 1024 * 1024

internal object ModelCatalog {
    val GEMMA_4_E2B = ModelSpec(
        id = "gemma-4-e2b",
        filename = "gemma-4-E2B-it.litertlm",
        label = "Gemma 4 E2B",
        capabilities = setOf(ModelCapability.TEXT, ModelCapability.AUDIO, ModelCapability.VISION, ModelCapability.TOOLS),
        minimumAvailableBytes = 4 * GIB,
    )

    val JIMI_GEMMA_3 = ModelSpec(
        id = "jimi-gemma-3-1b-v8",
        filename = "nila-assistant-jimi-gemma3-1b-v8-wi8-afp32.litertlm",
        label = "Jimi Gemma 3 1B v8",
        capabilities = setOf(ModelCapability.TEXT),
        minimumAvailableBytes = 2 * GIB,
    )

    val FUNCTION_GEMMA = ModelSpec(
        id = "functiongemma-run8-q8-ekv2048",
        filename = "nila-router-functiongemma-run8-q8-ekv2048.litertlm",
        label = "FunctionGemma run 8",
        capabilities = setOf(ModelCapability.TOOLS),
        minimumAvailableBytes = GIB,
    )

    fun deviceMemory(context: Context): DeviceMemory {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return DeviceMemory(info.totalMem, info.availMem, info.lowMemory)
    }

    fun selectAssistant(installed: Set<String>, memory: DeviceMemory): ModelSpec? =
        listOf(GEMMA_4_E2B, JIMI_GEMMA_3).firstOrNull { model ->
            model.filename in installed && !memory.lowMemory && memory.availableBytes >= model.minimumAvailableBytes
        }

    fun routerPolicy(memory: DeviceMemory): RouterLoadPolicy =
        if (memory.lowMemory || memory.totalBytes < 10 * GIB || memory.availableBytes < 4 * GIB) {
            RouterLoadPolicy.ON_DEMAND
        } else {
            RouterLoadPolicy.RESIDENT
        }
}

internal fun modelStatus(model: ModelSpec?): String = when (model) {
    null -> "No compatible assistant model installed"
    else -> buildString {
        append(model.label)
        append(" · ")
        append(if (ModelCapability.AUDIO in model.capabilities) "voice ready" else "text only; voice unavailable")
        if (ModelCapability.VISION !in model.capabilities) append("; report image reading unavailable")
    }
}
