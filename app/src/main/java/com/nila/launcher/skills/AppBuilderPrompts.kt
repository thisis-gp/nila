package com.nila.launcher.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Prompts for Gemma. The component and value lists come from RecipeCatalog, so prompt and validator cannot drift. */
object AppBuilderPrompts {
    /** How much of a broken answer is echoed back when asking for a fix; more only costs prefill time. */
    private const val MAX_ECHO_CHARS = 2_000

    val PLAN_SYSTEM = """
        You plan small phone apps for NILA. Reply with JSON only, no other text:
        {"template": "<template>", "title": "<2-3 word app name>", "providers": ["<provider>"]}
        Templates:
        - money: spending, expenses, budgets and payments from the user's bank and UPI.
          Money the user types in by hand, like fuel costs or loans to friends, is a log.
        - log: entries the user adds by hand, like books, workouts, weight, sleep or journal lines.
        - counter: counting something during the day, like glasses of water or cigarettes.
        - checklist: things to tick off, like packing, groceries, todos or daily habits.
        - none: needs something NILA apps cannot do: going online, live data, camera, video, maps, messaging, booking or paying.
        Providers:
        - transactions: the user's bank and UPI payments. Only for money.
        - storage: things the user adds by hand.
        - rules: sorts payments into categories automatically. Use it with transactions.
        The user may write in English, Hindi, Tamil or a mix of them.
    """.trimIndent()

    fun fillSystem(example: String): String = listOf(
        "You build the screen of a NILA phone app. Reply with compact JSON on one line only: no spaces, no code fences, no other text:",
        """{"components": [...], "rules": [...]}""",
        "\"components\" is a FLAT list. Each item has \"id\", \"component\" and that component's props.",
        "Children are lists of ids. Exactly one item has id \"root\". Every other item must be reachable from root.",
        """"rules" is optional: [{"when": {"op": "contains", "field": "merchant", "value": "SWIGGY"}, "set": {"category": "Food"}}]""",
        "Components (* = required):",
        catalogText(),
        "Value kinds:",
        "- path: \"/\" followed by a data key from the request, like \"/items\".",
        """- function: {"fn": "<${RecipeCatalog.functions.joinToString("|")}>", "of": "<path>", "field": "<field name>", "period": "<${RecipeCatalog.periods.joinToString("|")}>"}""",
        """- action: {"action": "<${RecipeCatalog.actions.joinToString("|")}>"}""",
        "Use nothing else. Example screen:",
        screenOnly(example),
    ).joinToString("\n")

    fun fillRequest(idea: String, skeleton: JsonObject): String {
        val data = (skeleton.getValue("data") as JsonObject).entries.joinToString(", ") { (key, source) ->
            val provider = RecipeCatalog.providers.getValue((source as JsonObject).string("provider").orEmpty())
            "/$key (${provider.name}${if (provider.fields.isEmpty()) "" else ": " + provider.fields.joinToString()})"
        }
        return "Idea: $idea\nApp title: ${skeleton.string("title")}\nData keys: $data\nBuild the screen."
    }

    fun fixRequest(idea: String, skeleton: JsonObject, previous: String, errors: List<RecipeError>): String =
        fillRequest(idea, skeleton) +
            "\n\nYour last answer had these problems:\n" + errors.joinToString("\n") { "- $it" } +
            "\n\nLast answer:\n" + previous.take(MAX_ECHO_CHARS) +
            "\n\nReply with the corrected JSON only."

    /**
     * Just the parts the model writes, compact. Shown a whole pretty-printed recipe, Gemma echoed all of it
     * (~1.5k chars) and ran into the output token cap.
     */
    private fun screenOnly(example: String): String {
        val recipe = Json.parseToJsonElement(example).jsonObject
        return JsonObject(recipe.filterKeys { it == "components" || it == "rules" }).toString()
    }

    internal fun catalogText(): String = RecipeCatalog.components.values.joinToString("\n") { spec ->
        "- ${spec.name}: " + spec.props.entries.joinToString(", ") { (name, prop) ->
            "$name${if (prop.required) "*" else ""} (${kindText(prop)})"
        }
    }

    private fun kindText(prop: PropSpec): String = when (prop.kind) {
        PropKind.TEXT -> "text"
        PropKind.NUMBER -> "number"
        PropKind.ENUM -> prop.values.joinToString("|")
        PropKind.PATH -> "path"
        PropKind.ID -> "component id"
        PropKind.ID_LIST -> "list of component ids"
        PropKind.FUNCTION -> "function"
        PropKind.ACTION -> "action"
    }
}
