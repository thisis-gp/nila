package com.nila.launcher.skills

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.nila.launcher.ModelFiles
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec §9.1 gate. Not a pass/fail test: it writes files/recipe_eval.jsonl and files/recipe_eval_summary.txt.
 * Run with scripts/run-recipe-eval.ps1, which keeps app data (connectedAndroidTest would uninstall the pushed model).
 */
@RunWith(AndroidJUnit4::class)
class RecipeEvalTest {
    private data class Row(val expected: String, val idea: String, val result: Result<BuildResult>, val ms: Long) {
        val outcome: String get() = result.fold({
            when (it) {
                is BuildResult.Built -> "built"
                is BuildResult.Fallback -> "fallback"
                is BuildResult.Gap -> "gap"
            }
        }, { "crash" })
        val template: String get() = when (val r = result.getOrNull()) {
            is BuildResult.Built -> r.plan.template
            is BuildResult.Fallback -> r.plan.template
            else -> RecipeCatalog.NO_TEMPLATE
        }

        fun json(): String = buildJsonObject {
            put("expected", expected)
            put("idea", idea)
            put("outcome", outcome)
            put("template", template)
            put("ms", ms)
            when (val r = result.getOrNull()) {
                is BuildResult.Built -> { put("attempts", r.attempts); put("recipe", r.recipe.toString()) }
                is BuildResult.Fallback -> put("errors", r.errors.joinToString("; "))
                else -> result.exceptionOrNull()?.let { put("error", it.toString()) }
            }
        }.toString()
    }

    @Test
    fun evaluateAppBuilder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = ModelFiles.find(context, ModelFiles.ASSISTANT)
            ?: error("Push ${ModelFiles.ASSISTANT} into the app's files/models first")
        val prompts = instrumentation.context.assets.open(PROMPTS).bufferedReader().readLines()
            .filter { it.isNotBlank() }
            .map { it.substringBefore('|') to it.substringAfter('|') }
        val out = File(context.filesDir, RESULTS).apply { delete() }

        // CPU, as the spec's latency target assumes; the GPU path is measured separately if this passes.
        val engine = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath))
        val rows = engine.use {
            it.initialize()
            val builder = AppBuilder(LiteRtRecipeModel(it))
            prompts.map { (expected, idea) ->
                val started = SystemClock.elapsedRealtime()
                val result = runCatching { builder.build(idea) }
                Row(expected, idea, result, SystemClock.elapsedRealtime() - started).also { row ->
                    out.appendText(row.json() + "\n")
                    Log.i(TAG, "${row.outcome} ${row.ms}ms $idea")
                }
            }
        }
        File(context.filesDir, SUMMARY).writeText(summarize(rows))
    }

    private fun summarize(rows: List<Row>): String {
        val buildable = rows.filter { it.expected != RecipeCatalog.NO_TEMPLATE }
        val built = buildable.count { it.outcome == "built" }
        val validity = PERCENT * built / buildable.size
        val impossible = rows.filter { it.expected == RecipeCatalog.NO_TEMPLATE }
        val times = rows.map { it.ms }.sorted()
        fun percentile(p: Double) = times[((times.size - 1) * p).toInt()]
        val firstTry = rows.count { (it.result.getOrNull() as? BuildResult.Built)?.attempts == 1 }
        return """
            valid after repair: $built/${buildable.size} (${"%.0f".format(validity)}%)
            built first try: $firstTry
            fallbacks: ${rows.count { it.outcome == "fallback" }}, crashes: ${rows.count { it.outcome == "crash" }}
            template correct: ${rows.count { it.template == it.expected }}/${rows.size}
            impossible ideas refused: ${impossible.count { it.outcome == "gap" }}/${impossible.size}
            latency p50 ${percentile(MEDIAN)} ms, p95 ${percentile(P95)} ms (target < $TARGET_MS ms)
            verdict: ${if (validity >= GO_PERCENT) "GO" else "NO-GO: fine-tune next (spec §9.1)"}
        """.trimIndent()
    }

    private companion object {
        const val TAG = "RecipeEval"
        const val PROMPTS = "app_builder_prompts.txt"
        const val RESULTS = "recipe_eval.jsonl"
        const val SUMMARY = "recipe_eval_summary.txt"
        const val PERCENT = 100.0
        const val GO_PERCENT = 85.0
        const val MEDIAN = 0.5
        const val P95 = 0.95
        const val TARGET_MS = 60_000
    }
}
