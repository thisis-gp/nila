package com.nila.launcher.skills

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Plain-Kotlin eval core (spec §9.1), shared by RecipeEvalTest on the phone and tools/recipe-eval on a PC. */
data class EvalRow(val expected: String, val idea: String, val result: Result<BuildResult>, val ms: Long) {
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

/** Lines are `expected template|idea`. */
fun parsePrompts(lines: List<String>): List<Pair<String, String>> =
    lines.filter { it.isNotBlank() }.map { it.substringBefore('|') to it.substringAfter('|') }

fun runEval(builder: AppBuilder, prompts: List<Pair<String, String>>, clock: () -> Long, onRow: (EvalRow) -> Unit): List<EvalRow> =
    prompts.map { (expected, idea) ->
        val started = clock()
        val result = runCatching { builder.build(idea) }
        EvalRow(expected, idea, result, clock() - started).also(onRow)
    }

private const val PERCENT = 100.0
private const val GO_PERCENT = 85.0
private const val MEDIAN = 0.5
private const val P95 = 0.95
private const val TARGET_MS = 60_000

fun summarize(rows: List<EvalRow>): String {
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
