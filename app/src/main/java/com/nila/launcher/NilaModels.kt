package com.nila.launcher

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Model files pushed with adb into the app's external files dir. Never bundled: Gemma is over the 2 GB APK limit. */
object ModelFiles {
    const val ASSISTANT = "gemma-4-E2B-it.litertlm"
    const val ROUTER = "functiongemma-mobile-actions_q8_ekv1024.litertlm"

    /**
     * Created from the app's own process: a folder made by `adb shell mkdir` is shell-owned and the app
     * cannot read files pushed into it (same lesson as JIMI's ExternalModelFiles).
     */
    fun dir(context: Context): File? = context.getExternalFilesDir("models")?.apply { mkdirs() }

    /**
     * Internal storage first: on vivo's ROM, files written by `adb shell` into the external dir stay
     * owned by `shell` and open() fails with PERMISSION_DENIED. `run-as` copies into filesDir are app-owned.
     */
    fun find(context: Context, name: String): File? =
        listOfNotNull(File(context.filesDir, "models"), dir(context))
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.length() > 0 }
}

enum class ModelState(val label: String) { MISSING("not installed"), LOADING("loading…"), READY("ready"), FAILED("failed to load") }

/** JSON-safe string literal. */
private fun json(value: String) = buildString {
    append('"')
    value.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}

/** OpenAPI description of one NILA capability, the schema FunctionGemma routes against. */
fun toolSchemaJson(capability: CapabilityDescriptor): String {
    val properties = capability.params.joinToString(",") { param ->
        val type = when (param.type) {
            ParamType.STRING -> "string"
            ParamType.INT, ParamType.PAISE -> "integer"
            ParamType.NUMBER -> "number"
            ParamType.BOOLEAN -> "boolean"
        }
        "${json(param.name)}:{\"type\":\"$type\",\"description\":${json(param.label)}}"
    }
    val required = capability.params.filter { it.required }.joinToString(",") { json(it.name) }
    return "{\"name\":${json(capability.id)},\"description\":${json(capability.description)}," +
        "\"parameters\":{\"type\":\"object\",\"properties\":{$properties},\"required\":[$required]}}"
}

/**
 * Converts model-produced arguments to the Kotlin types [checkCall] expects. Only lossless conversions:
 * 6.0 becomes 6, "30" becomes 30, but 6.5 stays a Double so checkCall rejects it for an INT param.
 */
fun coerceArgs(raw: Map<String, Any?>, capability: CapabilityDescriptor?): Map<String, Any> {
    val types = capability?.params?.associate { it.name to it.type }.orEmpty()
    return raw.mapNotNull { (name, value) ->
        if (value == null) return@mapNotNull null
        val converted: Any = when (types[name]) {
            ParamType.INT -> wholeNumber(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: value
            ParamType.PAISE -> wholeNumber(value) ?: value
            ParamType.NUMBER -> (value as? String)?.toDoubleOrNull() ?: value
            ParamType.BOOLEAN -> (value as? String)?.toBooleanStrictOrNull() ?: value
            ParamType.STRING -> value as? String ?: value.toString()
            null -> value
        }
        name to converted
    }.toMap()
}

/**
 * True when every number the model proposed was actually said by the user. Small models invent values:
 * FunctionGemma turned "remind me at 9 pm" into hour = 10 on the iQOO 15.
 */
fun numbersGrounded(args: Map<String, Any>, input: String): Boolean {
    val said = Regex("""\d+""").findAll(input).map { it.value.toLong() }.toSet()
    // Zero is a default ("at 9" means minute 0), not an invented value.
    return args.values.filterIsInstance<Number>().all { (it.toDouble() % 1.0 == 0.0) && (it.toLong() == 0L || it.toLong() in said) }
}

private fun wholeNumber(value: Any): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is Number -> value.toDouble().takeIf { it % 1.0 == 0.0 }?.toLong()
    is String -> value.trim().toLongOrNull()
    else -> null
}

/**
 * True when a chat reply says an action happened. Gemma only talks, so any such claim is false:
 * it told a user "set an alarm for 1:30" was done when no alarm existed.
 */
fun claimsAction(reply: String): Boolean = Regex(
    """(?i)\b(i'?ve|i have|i just|i went ahead|has been|have been|successfully|done)\b[^.!?]*\b(set|created|scheduled|ordered|placed|sent|booked|paid|called|turned on)\b""" +
        """|(?i)\b(alarm|order|message|booking|reminder)\b[^.!?]*\b(is|has been) (now )?(set|placed|sent|confirmed|scheduled)\b""" +
        """|(?i)^\s*(alarm|reminder) (set|scheduled)\b""",
).containsMatchIn(reply)

/** Asks Gemma for a transcript only, in the language's own script, never an answer to the request. */
fun transcriptionPrompt(languageName: String) =
    "Transcribe this speech exactly as spoken. The speaker most likely uses $languageName. " +
        "Write it in that language's own script. Output only the transcript, with no answer, translation or notes."

/** Exposes a capability's schema to LiteRT-LM. Execution stays in NilaEngine, never inside the model runtime. */
private class CapabilityTool(private val capability: CapabilityDescriptor) : OpenApiTool {
    override fun getToolDescriptionJsonString() = toolSchemaJson(capability)
    override fun execute(paramsJsonString: String) = error("NILA runs tools itself after validation")
}

/**
 * The two on-device models. FunctionGemma 270M picks tools; Gemma 4 E2B talks. Both stay resident once
 * loaded. Order per ARCHITECTURE.md: deterministic fast paths, then the router, then conversation.
 * Business rules and permissions stay in NilaEngine: model output is only a proposal checked by checkCall.
 */
class NilaBrain(private val context: Context, private val engine: NilaEngine) {
    var assistantState by mutableStateOf(ModelState.MISSING)
        private set
    var routerState by mutableStateOf(ModelState.MISSING)
        private set
    /** Last model load failure, shown in the chat status; this vivo ROM drops app logcat output. */
    var loadError by mutableStateOf<String?>(null)
        private set

    // LiteRT-LM engines are used from one thread only.
    private val llm = Executors.newSingleThreadExecutor { Thread(it, "nila-llm") }.asCoroutineDispatcher()
    private var assistant: Engine? = null
    private var router: Engine? = null
    private val history = ArrayDeque<Pair<String, String>>()

    /**
     * Frees both native engines and the model thread. Runs on the model thread after any load or reply in
     * progress, so nothing is closed while in use. Call when the screen that owns this brain goes away.
     */
    fun close() {
        llm.executor.execute {
            router?.close(); router = null
            assistant?.close(); assistant = null
        }
        llm.close()
    }

    suspend fun load() = withContext(llm) {
        router = loadModel(ModelFiles.ROUTER, gpuFirst = false, audio = false) { routerState = it }
        assistant = loadModel(ModelFiles.ASSISTANT, gpuFirst = true, audio = true) { assistantState = it }
    }

    private fun loadModel(name: String, gpuFirst: Boolean, audio: Boolean, setState: (ModelState) -> Unit): Engine? {
        val file = ModelFiles.find(context, name) ?: return null.also { setState(ModelState.MISSING) }
        setState(ModelState.LOADING)
        val backends = if (gpuFirst) listOf(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())
        for (backend in backends) {
            val created = Engine(
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backend,
                    // Gemma's audio encoder runs on the CPU, as in Google's AI Edge Gallery.
                    audioBackend = if (audio) Backend.CPU() else null,
                    cacheDir = context.cacheDir.absolutePath,
                ),
            )
            try {
                created.initialize()
                setState(ModelState.READY)
                return created
            } catch (e: Exception) {
                // close() throws on an engine that never initialized; unguarded, that escaped and
                // crashed NILA on launch before the CPU fallback could run.
                runCatching { created.close() }
                loadError = "$name on ${backend::class.simpleName}: ${e.message}"
                runCatching { java.io.File(context.filesDir, "model_load.txt").appendText("$loadError\n") }
            }
        }
        setState(ModelState.FAILED)
        return null
    }

    suspend fun respond(input: String): NilaResponse = withContext(llm) {
        val start = SystemClock.elapsedRealtime()
        fun done(path: String, response: NilaResponse) = response.also {
            Log.i(TAG, "path=$path ms=${SystemClock.elapsedRealtime() - start} response=${it::class.simpleName}")
        }
        engine.calculate(input)?.let { return@withContext done("calculator", it) }
        val keyword = engine.route(input)
        if (keyword is NilaIntent.Call) return@withContext done("rules:${keyword.call.capabilityId}", engine.execute(keyword))
        // Stock FunctionGemma scored 31% on NILA's tools (training/functiongemma eval) and its wrong picks
        // pre-empt Gemma, so it routes only when Gemma 4 E2B is not loaded, until the fine-tuned router ships.
        if (assistant == null && !engine.isSmallTalk(input)) modelRoute(input)?.let { return@withContext done("functiongemma", it) }
        chat(input)?.let { done("gemma", it) } ?: done("fallback", engine.execute(NilaIntent.Converse(input)))
    }

    /**
     * Asks FunctionGemma for one tool call. The call is used only when checkCall accepts it (or only
     * needs more details) and the tool actually produced a result; anything else falls through to chat,
     * so a wrong guess for "hi" becomes a conversation instead of "I can't open that app".
     */
    private fun modelRoute(input: String): NilaResponse? {
        val model = router ?: return null
        val response = runCatching {
            model.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("You are a model that can do function calling with the following functions"),
                    tools = (engine.capabilities.values + CHAT_TOOL).map { tool(CapabilityTool(it)) },
                    automaticToolCalling = false,
                ),
            ).use { it.sendMessage(input) }
        }.getOrNull() ?: return null
        val proposed = response.toolCalls.firstOrNull()
        // Argument names only: values can carry what the user said.
        Log.i(TAG, "functiongemma tool=${proposed?.name} args=${proposed?.arguments?.keys}")
        trace("functiongemma tool=${proposed?.name} args=${proposed?.arguments?.keys}")
        // "chat" or an unknown tool means this is conversation, not an action.
        if (proposed == null) return null
        return act(proposed.name, proposed.arguments, input)
    }

    /**
     * Turns a model's proposed tool into a response. Business rules stay in NilaEngine: the proposal runs
     * only when checkCall accepts it (or only needs more details) and the tool produced a real result.
     */
    private fun act(name: String, arguments: Map<String, Any?>, input: String): NilaResponse? {
        val capability = engine.capabilities[name] ?: return null
        // The model picks the action; exact values come from the user's own words where NILA can read them.
        val args = (if (capability.id == "create_alarm") engine.alarmArgs(input) else null) ?: coerceArgs(arguments, capability)
        val call = ToolCall(capability.id, args)
        if (!numbersGrounded(call.args, input)) return null.also { Log.i(TAG, "rejected ungrounded numbers for ${call.capabilityId}") }
        val check = checkCall(call, engine.capabilities)
        trace("check=${check::class.simpleName}")
        return when (check) {
            // A Text result from a validated call is a refusal ("I can't open that app"); prefer conversation.
            is CallCheck.Ok, is CallCheck.Missing -> engine.execute(NilaIntent.Call(call)).let { result ->
                when {
                    result is NilaResponse.FoodSearch -> engine.foodConstraints(input, result)
                    result !is NilaResponse.Text -> result
                    // A failed alarm must never reach Gemma: on the iQOO 15 it then claimed the alarm was set.
                    capability.id == "create_alarm" -> NilaResponse.Text(ALARM_HELP)
                    else -> null
                }
            }
            is CallCheck.Rejected -> null
        }
    }

    /**
     * Turns a spoken WAV clip into text with Gemma 4 E2B on the phone. The text then takes the same path
     * as typing, so voice never skips the rules, checks or confirmations.
     */
    suspend fun transcribe(wav: ByteArray, languageName: String): String? = withContext(llm) {
        val model = assistant ?: return@withContext null
        val start = SystemClock.elapsedRealtime()
        runCatching {
            model.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use { conversation ->
                conversation.sendMessage(
                    Contents.of(Content.AudioBytes(wav), Content.Text(transcriptionPrompt(languageName))),
                ).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
            }
        }.onFailure { Log.w(TAG, "transcribe failed", it) }.getOrNull()
            // Timing and size only: a transcript is what the user said and must never reach device logs.
            .also { Log.i(TAG, "transcribe ms=${SystemClock.elapsedRealtime() - start} bytes=${wav.size} chars=${it?.length ?: 0}") }
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Gemma 4 E2B answers and can also call a tool: the 270M router misses requests it was not fine-tuned
     * on ("im hungry get me some biryani" came back as chat on the iQOO 15). A proposed call goes through
     * the same [act] checks as FunctionGemma's, so chat never acts on its own.
     */
    private fun chat(input: String): NilaResponse? {
        val model = assistant ?: return null
        val response = runCatching {
            model.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(PERSONA),
                    initialMessages = history.flatMap { (user, nila) -> listOf(Message.user(user), Message.model(nila)) },
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.6),
                    tools = engine.capabilities.values.map { tool(CapabilityTool(it)) },
                    automaticToolCalling = false,
                ),
            ).use { it.sendMessage(input) }
        }.getOrNull() ?: return null
        response.toolCalls.firstOrNull()?.let { proposed ->
            trace("gemma tool=${proposed.name} args=${proposed.arguments.keys}")
            act(proposed.name, proposed.arguments, input)?.let { return it }
        }
        val reply = response.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
            .takeIf { it.isNotBlank() }?.let { if (claimsAction(it)) NO_ACTION_TAKEN.also { Log.i(TAG, "replaced a reply that claimed an action") } else it }
            ?: return null
        history.addLast(input to reply)
        while (history.size > HISTORY_TURNS) history.removeFirst()
        return NilaResponse.Text(reply)
    }

    // vivo drops app logcat; the same keys-only lines go to a private file for device debugging.
    private fun trace(line: String) {
        runCatching { File(context.filesDir, "route.txt").appendText("$line\n") }
    }

    private companion object {
        const val TAG = "NilaBrain"
        const val HISTORY_TURNS = 4
        const val ALARM_HELP = "I couldn't read that time, so no alarm is set. Try \"set an alarm for 1:30 pm\"."
        const val NO_ACTION_TAKEN = "I haven't done that. Chat can't take actions. To set an alarm, say a time like \"set an alarm for 1:30 pm\" and tap Set alarm on the card."

        /**
         * An escape hatch for FunctionGemma, which otherwise tends to pick some tool for every input
         * ("hi" became search_food on the iQOO 15). Choosing it hands the message to Gemma.
         */
        val CHAT_TOOL = CapabilityDescriptor(
            id = "chat",
            description = "Talk with the user: greetings, questions about NILA or anything else, small talk, opinions, or any request the other functions cannot do",
            params = listOf(Param("message", ParamType.STRING)),
            permission = PermissionClass.READ,
        )
        val PERSONA = """
            You are NILA, a friendly assistant that lives on the user's Android phone and runs fully on the device.
            Reply in one to three short, warm sentences, in the same language the user writes in (English, Hindi or Malayalam).
            Call a tool only when the user asks NILA to do something now: set an alarm, open YouTube, Netflix, Instagram or BGMI,
            order food on Swiggy, show the route to a place, or remember one fact.
            Questions are not requests: "is biryani healthy" or "what is Koramangala famous for" get a spoken answer, not a tool.
            For anything else, such as the flashlight, calls, messages, payments, settings or other apps, say plainly that NILA cannot do that yet and name something it can do.
            Never offer to do it, never ask for confirmation, and never say you ordered, paid, booked, sent or shared anything.
        """.trimIndent()
    }
}
