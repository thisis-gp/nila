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
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec §9.1 gate. Not a pass/fail test: it writes files/recipe_eval.jsonl and files/recipe_eval_summary.txt.
 * Run with scripts/run-recipe-eval.ps1, which keeps app data (connectedAndroidTest would uninstall the pushed model).
 */
@RunWith(AndroidJUnit4::class)
class RecipeEvalTest {
    @Test
    fun evaluateAppBuilder() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val modelFile = ModelFiles.find(context, ModelFiles.ASSISTANT)
            ?: error("Push ${ModelFiles.ASSISTANT} into the app's files/models first")
        val prompts = parsePrompts(instrumentation.context.assets.open(PROMPTS).bufferedReader().readLines())
        val out = File(context.filesDir, RESULTS).apply { delete() }

        // CPU, as the spec's latency target assumes; the GPU path is measured separately if this passes.
        val engine = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.absolutePath))
        val rows = engine.use {
            it.initialize()
            runEval(AppBuilder(LiteRtRecipeModel(it)), prompts, SystemClock::elapsedRealtime) { row ->
                out.appendText(row.json() + "\n")
                Log.i(TAG, "${row.outcome} ${row.ms}ms ${row.idea}")
            }
        }
        File(context.filesDir, SUMMARY).writeText(summarize(rows))
    }

    private companion object {
        const val TAG = "RecipeEval"
        const val PROMPTS = "app_builder_prompts.txt"
        const val RESULTS = "recipe_eval.jsonl"
        const val SUMMARY = "recipe_eval_summary.txt"
    }
}
