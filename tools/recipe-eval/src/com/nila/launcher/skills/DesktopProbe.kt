package com.nila.launcher.skills

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Spec §7.3 on a PC: does ResponseFormat (constrained decoding) give valid fill JSON with Gemma 4 E2B, or does
 * LiteRT-LM #3510 (no spaces in constrained strings) break labels like "Glasses today"? Args: <model.litertlm> <out dir>.
 */
fun main(args: Array<String>) {
    val (modelPath, outPath) = args
    val cache = File(outPath, "cache").apply { mkdirs() }
    val components = RecipeCatalog.components.keys.joinToString(",") { "\"$it\"" }
    val schema = """{"type":"object","properties":{"components":{"type":"array","items":{"type":"object",""" +
        """"properties":{"id":{"type":"string"},"component":{"enum":[$components]}},"required":["id","component"]}},""" +
        """"rules":{"type":"array"}},"required":["components"]}"""
    val cases = listOf(
        "count how many glasses of water I drink" to Plan("counter", "Water", listOf("storage")),
        "cigarette counter to help me quit" to Plan("counter", "Cigarettes", listOf("storage")),
        "reading log for books I finish" to Plan("log", "Reading Log", listOf("storage")),
        "track money I lent to friends" to Plan("log", "Money Lent", listOf("storage")),
        "packing list for my Goa trip" to Plan("checklist", "Goa Packing", listOf("storage")),
        "I want an expense tracker that shows where my money goes" to Plan("money", "Expenses", listOf("transactions", "rules")),
    )
    val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it).apply { isDaemon = true } }
    Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = cache.absolutePath)).use { engine ->
        engine.initialize()
        for ((idea, plan) in cases) {
            val skeleton = skeleton(plan)
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(AppBuilderPrompts.fillSystem(RecipeExamples.forTemplate(plan.template))),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    maxOutputToken = LiteRtRecipeModel.MAX_OUTPUT_TOKENS,
                    enableResponseFormat = true,
                ),
            ).use { conversation ->
                val started = System.currentTimeMillis()
                val timer = watchdog.schedule({ conversation.cancelProcess() }, PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val reply = runCatching {
                    conversation.sendMessage(AppBuilderPrompts.fillRequest(idea, skeleton), responseFormat = ResponseFormat.json(schema))
                        .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                }.getOrElse { "<${it.message}>" }
                timer.cancel(false)
                val ms = System.currentTimeMillis() - started
                val filled = RecipeRepair.extractJson(reply)
                val errors = filled?.let {
                    val screen = listOfNotNull("components" to (it["components"] ?: kotlinx.serialization.json.JsonArray(emptyList())), it["rules"]?.let { r -> "rules" to r })
                    RecipeValidator.validate(RecipeRepair.repair(kotlinx.serialization.json.JsonObject(skeleton + screen)))
                }
                val verdict = when {
                    ms >= PROBE_TIMEOUT_MS -> "runaway"
                    filled == null -> "not-json"
                    errors.isNullOrEmpty() -> "valid"
                    else -> "invalid: ${errors.take(2).joinToString("; ")}"
                }
                println("$verdict | ${ms}ms | $idea | ${reply.take(PREVIEW_CHARS).replace('\n', ' ')}")
            }
        }
    }
}

private const val PROBE_TIMEOUT_MS = 120_000L
private const val PREVIEW_CHARS = 160
