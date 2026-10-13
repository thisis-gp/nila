package com.nila.launcher

enum class ModelFeature { CHAT, TOOL_ROUTING, AUDIO_INPUT, IMAGE_INPUT }

data class ModelSpec(
    val id: String,
    val name: String,
    val fileName: String,
    val minimumAvailableMemoryMiB: Long,
    val features: Set<ModelFeature>,
    val gpuFirst: Boolean = true,
)

data class ModelOption(val model: ModelSpec, val installed: Boolean, val fitsMemory: Boolean) {
    val reason: String
        get() = when {
            !installed -> "Model file not installed on this phone"
            !fitsMemory -> "Not enough free memory to load safely right now"
            else -> "Ready · loads when needed"
        }
}

object ModelCatalog {
    const val ROUTER_FILE = "nila-router-functiongemma-run8-q8-ekv2048.litertlm"
    const val SELECTED_MODEL_KEY = "selected_model_id"

    val assistants = listOf(
        ModelSpec(
            id = "gemma3-1b",
            name = "Gemma 3 1B · on-device",
            fileName = "gemma3-1b-it-int4.litertlm",
            minimumAvailableMemoryMiB = 850,
            features = setOf(ModelFeature.CHAT),
        ),
        ModelSpec(
            id = "jimi-gemma3-1b",
            name = "Jimi · fine-tuned Gemma 3 1B",
            fileName = "nila-assistant-jimi-gemma3-1b-v8-wi8-afp32.litertlm",
            minimumAvailableMemoryMiB = 1650,
            features = setOf(ModelFeature.CHAT),
        ),
        ModelSpec(
            id = "gemma4-e2b",
            name = "Gemma 4 E2B · multimodal",
            fileName = "gemma-4-E2B-it.litertlm",
            minimumAvailableMemoryMiB = 3400,
            features = setOf(ModelFeature.CHAT, ModelFeature.AUDIO_INPUT, ModelFeature.IMAGE_INPUT),
        ),
    )

    val router = ModelSpec(
        id = "functiongemma-mobile-actions",
        name = "FunctionGemma · tool router",
        fileName = ROUTER_FILE,
        minimumAvailableMemoryMiB = 500,
        features = setOf(ModelFeature.TOOL_ROUTING),
        gpuFirst = false,
    )

    fun choices(installedFiles: Set<String>, availableMemoryMiB: Long): List<ModelOption> =
        assistants.map { model ->
            val installed = model.fileName in installedFiles
            ModelOption(model, installed, installed && availableMemoryMiB >= model.minimumAvailableMemoryMiB)
        }

    fun select(options: List<ModelOption>, preferredId: String?): ModelSpec? =
        options.firstOrNull { it.model.id == preferredId && it.installed && it.fitsMemory }?.model
            ?: options.filter { it.installed && it.fitsMemory }.minByOrNull { it.model.minimumAvailableMemoryMiB }?.model
}
