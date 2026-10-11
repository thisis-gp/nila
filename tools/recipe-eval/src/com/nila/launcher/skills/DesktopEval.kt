package com.nila.launcher.skills

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * Spec §9.1 accuracy gate on a PC: same model file, same greedy decoding, same pipeline as the phone.
 * Latency here is the PC's, not the phone's. Args: <model.litertlm> <prompts.txt> <out dir>.
 */
fun main(args: Array<String>) {
    require(args.size == ARG_COUNT) { "usage: <model.litertlm> <prompts.txt> <out dir>" }
    val (modelPath, promptsPath, outPath) = args
    val outDir = File(outPath).apply { mkdirs() }
    val results = File(outDir, "recipe_eval.jsonl").apply { delete() }
    val prompts = parsePrompts(File(promptsPath).readLines())
    val cache = File(outDir, "cache").apply { mkdirs() }

    val rows = Engine(EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = cache.absolutePath)).use { engine ->
        engine.initialize()
        runEval(AppBuilder(LiteRtRecipeModel(engine)), prompts, System::currentTimeMillis) { row ->
            results.appendText(row.json() + "\n")
            println("${row.outcome.padEnd(OUTCOME_WIDTH)} ${row.template.padEnd(OUTCOME_WIDTH)} ${row.ms}ms  ${row.idea}")
        }
    }
    val summary = summarize(rows)
    File(outDir, "recipe_eval_summary.txt").writeText(summary)
    println(summary)
}

private const val ARG_COUNT = 3
private const val OUTCOME_WIDTH = 9
