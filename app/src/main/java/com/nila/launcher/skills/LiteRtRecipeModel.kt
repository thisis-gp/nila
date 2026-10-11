package com.nila.launcher.skills

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Gemma behind RecipeModel. Greedy decoding, a token cap and a timeout: a runaway answer (#3510) becomes "". */
class LiteRtRecipeModel(private val engine: Engine, private val timeoutMs: Long = TIMEOUT_MS) : RecipeModel {
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "recipe-watchdog").apply { isDaemon = true } }

    override fun complete(system: String, user: String, schema: String?): String =
        engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                enableResponseFormat = schema != null,
            ),
        ).use { conversation ->
            val timer = watchdog.schedule({ conversation.cancelProcess() }, timeoutMs, TimeUnit.MILLISECONDS)
            try {
                runCatching { conversation.sendMessage(user, responseFormat = schema?.let { ResponseFormat.json(it) }) }.getOrNull()
                    ?.contents?.contents?.filterIsInstance<Content.Text>()?.joinToString("") { it.text }
                    .orEmpty()
            } finally {
                timer.cancel(false)
            }
        }

    companion object {
        /** Spec §7.3. The money example's screen is about 350 tokens. */
        const val MAX_OUTPUT_TOKENS = 512
        const val TIMEOUT_MS = 20_000L
    }
}
