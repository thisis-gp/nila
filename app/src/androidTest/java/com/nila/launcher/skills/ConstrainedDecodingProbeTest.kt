package com.nila.launcher.skills

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.nila.launcher.ModelFiles
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

/** Spec §7.3: is ResponseFormat safe with Gemma 4 E2B on LiteRT-LM 0.17.1, or does #3510 make it loop? Writes files/constrained_probe.txt. */
@RunWith(AndroidJUnit4::class)
class ConstrainedDecodingProbeTest {
    private val templates = (RecipeCatalog.templates + RecipeCatalog.NO_TEMPLATE).joinToString(",") { "\"$it\"" }
    private val enumOnly = """{"type":"object","properties":{"template":{"enum":[$templates]}},"required":["template"]}"""
    private val withTitle = """{"type":"object","properties":{"template":{"enum":[$templates]},"title":{"type":"string"}},"required":["template","title"]}"""
    private val ideas = listOf("count how many glasses of water I drink", "expense tracker from my bank sms", "packing list for my Goa trip")

    @Test
    fun probe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelFile = ModelFiles.find(context, ModelFiles.ASSISTANT) ?: error("Push ${ModelFiles.ASSISTANT} first")
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val lines = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath)).use { engine ->
            engine.initialize()
            listOf("enum-only" to enumOnly, "with-title" to withTitle).flatMap { (label, schema) ->
                ideas.map { idea ->
                    engine.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(AppBuilderPrompts.PLAN_SYSTEM),
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                            maxOutputToken = LiteRtRecipeModel.MAX_OUTPUT_TOKENS,
                            enableResponseFormat = true,
                        ),
                    ).use { conversation ->
                        val started = SystemClock.elapsedRealtime()
                        val timer = watchdog.schedule({ conversation.cancelProcess() }, LiteRtRecipeModel.TIMEOUT_MS, TimeUnit.MILLISECONDS)
                        val text = runCatching {
                            conversation.sendMessage(idea, responseFormat = ResponseFormat.json(schema))
                                .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                        }.getOrElse { "<${it.message}>" }
                        timer.cancel(false)
                        val ms = SystemClock.elapsedRealtime() - started
                        val verdict = when {
                            ms >= LiteRtRecipeModel.TIMEOUT_MS -> "runaway"
                            RecipeRepair.extractJson(text) == null -> "invalid"
                            else -> "ok"
                        }
                        "$label $verdict ${ms}ms ${text.take(PREVIEW_CHARS).replace('\n', ' ')}"
                    }
                }
            }
        }
        File(context.filesDir, OUTPUT).writeText(lines.joinToString("\n"))
    }

    private companion object {
        const val OUTPUT = "constrained_probe.txt"
        const val PREVIEW_CHARS = 120
    }
}
