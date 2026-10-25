package com.nila.launcher

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays the FunctionGemma eval split through the deterministic keyword router, so routing work is
 * measured before it is tuned. Run `python training/functiongemma/make_dataset.py --seed 7` first;
 * without the generated dataset (CI) both tests skip.
 */
class RouterCoverageTest {
    private val engine = NilaEngine()

    private fun datasetOrSkip(): File {
        val file = listOf("../training/functiongemma/data/nila_router.jsonl", "training/functiongemma/data/nila_router.jsonl")
            .map(::File).firstOrNull(File::exists)
        assumeTrue("dataset not generated; run training/functiongemma/make_dataset.py --seed 7", file != null)
        return file!!
    }

    private fun evalRows(file: File) = file.readLines().filter { it.isNotBlank() }
        .map(::JSONObject).filter { it.optString("metadata") == "eval" }

    /** The eval split may only name tools the engine actually registers: dataset and registry must not drift. */
    @Test fun evalSplitNamesOnlyRegisteredTools() {
        val rows = evalRows(datasetOrSkip())
        assumeTrue("no eval rows", rows.isNotEmpty())
        rows.forEach { row ->
            val name = expectedTool(row)
            assertTrue("eval expects unknown tool $name", name == "chat" || name in engine.capabilities)
        }
    }

    /**
     * The report the router decision hangs on: how many eval prompts the keyword layer already answers
     * (and answers correctly), where it wrongly answers, and what is left for the model per tool.
     */
    @Test fun reportDeterministicCoverageOfTheEvalSplit() {
        val rows = evalRows(datasetOrSkip())
        var toolPrompts = 0
        var coveredCorrect = 0
        val wrongRoutes = mutableListOf<String>()
        var chatPrompts = 0
        var chatFalseTool = 0
        val chatWrongRoutes = mutableListOf<String>()
        val leftForModel = sortedMapOf<String, Int>()

        rows.forEach { row ->
            val prompt = row.getJSONArray("messages").getJSONObject(1).getString("content")
            when (val expected = expectedTool(row)) {
                "chat" -> {
                    chatPrompts++
                    val intent = engine.route(prompt)
                    if (intent is NilaIntent.Call) {
                        chatFalseTool++
                        chatWrongRoutes += "\"$prompt\" -> ${intent.call.capabilityId}"
                    }
                }
                else -> {
                    toolPrompts++
                    when (val intent = engine.route(prompt)) {
                        is NilaIntent.Call ->
                            if (intent.call.capabilityId == expected) coveredCorrect++
                            else wrongRoutes += "\"$prompt\": $expected -> ${intent.call.capabilityId}"
                        else -> leftForModel.merge(expected, 1, Int::plus)
                    }
                }
            }
        }
        assumeTrue("no eval rows", toolPrompts + chatPrompts > 0)

        println("ROUTER-EVAL eval rows: ${rows.size} (tool prompts: $toolPrompts, chat prompts: $chatPrompts)")
        println("ROUTER-EVAL deterministic coverage: $coveredCorrect/$toolPrompts (${percent(coveredCorrect, toolPrompts)})")
        println("ROUTER-EVAL wrong deterministic routes: ${wrongRoutes.size}")
        wrongRoutes.take(20).forEach { println("ROUTER-EVAL   wrong: $it") }
        println("ROUTER-EVAL chit-chat answered as a tool (should be 0): $chatFalseTool/$chatPrompts")
        chatWrongRoutes.forEach { println("ROUTER-EVAL   chat wrong: $it") }
        println("ROUTER-EVAL left for the model, by expected tool:")
        leftForModel.forEach { (tool, count) -> println("ROUTER-EVAL   $tool: $count") }
        println("ROUTER-EVAL model workload: ${toolPrompts - coveredCorrect - wrongRoutes.size} tool prompts + $chatPrompts chat prompts")
    }

    private fun expectedTool(row: JSONObject): String =
        row.getJSONArray("messages").getJSONObject(2).getJSONArray("tool_calls")
            .getJSONObject(0).getJSONObject("function").getString("name")

    private fun percent(part: Int, total: Int) = if (total == 0) "n/a" else "${part * 100 / total}%"
}
