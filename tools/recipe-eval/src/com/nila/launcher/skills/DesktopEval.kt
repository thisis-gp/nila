package com.nila.launcher.skills

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Spec §9.1 accuracy gate on a PC: same model file, same greedy decoding, same pipeline as the phone.
 * Latency here is the PC's, not the phone's, so calls get a longer timeout than the phone's 20 s.
 * Args: <model.litertlm> <prompts.txt> <out dir> [max prompts].
 */
fun main(args: Array<String>) {
    require(args.size in MIN_ARGS..MAX_ARGS) { "usage: <model.litertlm> <prompts.txt> <out dir> [max prompts]" }
    val (modelPath, promptsPath, outPath) = args
    val limit = args.getOrNull(MAX_ARGS - 1)?.toInt() ?: Int.MAX_VALUE
    val outDir = File(outPath).apply { mkdirs() }
    val results = File(outDir, "recipe_eval.jsonl").apply { delete() }
    val transcript = File(outDir, "model_calls.jsonl").apply { delete() }
    val prompts = parsePrompts(File(promptsPath).readLines()).take(limit)
    val cache = File(outDir, "cache").apply { mkdirs() }

    val rows = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = cache.absolutePath)).use { engine ->
        engine.initialize()
        val gemma = LiteRtRecipeModel(engine, timeoutMs = PC_TIMEOUT_MS)
        // Every raw answer is kept, so a failure can be traced to the model, the timeout or the pipeline.
        val logged = RecipeModel { system, user ->
            val started = System.currentTimeMillis()
            gemma.complete(system, user).also { reply ->
                val entry = buildJsonObject {
                    put("ms", System.currentTimeMillis() - started)
                    put("user", user.take(PREVIEW_CHARS))
                    put("reply", reply)
                }
                transcript.appendText("$entry\n")
            }
        }
        runEval(AppBuilder(logged), prompts, System::currentTimeMillis) { row ->
            results.appendText(row.json() + "\n")
            println("${row.outcome.padEnd(OUTCOME_WIDTH)} ${row.template.padEnd(OUTCOME_WIDTH)} ${row.ms}ms  ${row.idea}")
        }
    }
    val summary = summarize(rows)
    File(outDir, "recipe_eval_summary.txt").writeText(summary)
    println(summary)
}

private const val MIN_ARGS = 3
private const val MAX_ARGS = 4
private const val OUTCOME_WIDTH = 9
private const val PREVIEW_CHARS = 300
private const val PC_TIMEOUT_MS = 180_000L
