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
    // Compared as digit strings: toLong() on a huge number the user typed would throw.
    val said = Regex("""\d+""").findAll(input).map { it.value.trimStart('0').ifEmpty { "0" } }.toSet()
    // Zero is a default ("at 9" means minute 0), not an invented value.
    return args.values.filterIsInstance<Number>().all { (it.toDouble() % 1.0 == 0.0) && (it.toLong() == 0L || it.toLong().toString() in said) }
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
    """(?i)\b(i'?ve|i have|i just|i went ahead|i'?m|i am|has been|have been|successfully|done)\b[^.!?]*""" +
        """\b(set|created|scheduled|ordered|placed|sent|booked|paid|called|turn(?:ed|ing)? ?(?:on|off)|switch(?:ed|ing)? ?(?:on|off))\b""" +
        """|(?i)\b(alarm|order|message|booking|reminder|flashlight|torch)\b[^.!?]*\b(is|has been) (now )?(set|placed|sent|confirmed|scheduled|on|off)\b""" +
        """|(?i)^\s*(alarm|reminder) (set|scheduled)\b""",
).containsMatchIn(reply)

// Gemma's own tool-call syntax, seen leaked onto the text channel instead of a real structured call:
// "<|tool_call>call:remember_fact{fact:<|"|>...". Checked anywhere in the reply, not just the start —
// "Sure! <|tool_call>call:x{...}" leaks the same way with real text ahead of it.
private val rawToolCall = Regex("""<\|?tool_call>|call:\w+\{|<\|"\|>""")

/** True when a reply is (or contains) Gemma's own tool-call syntax, never meant for a person to see. */
fun looksLikeRawToolCall(reply: String): Boolean = rawToolCall.containsMatchIn(reply)

/** Cleans Gemma's rewrite: first line, no quotes, labels or final full stop. Null for CHAT or nothing. */
fun cleanCommand(raw: String): String? {
    val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
    val command = line.removePrefix("Command:").removePrefix("command:").trim().trim('"', '\'', '`', '*').trim().trimEnd('.')
    return command.takeUnless { it.isBlank() || it.equals("chat", ignoreCase = true) }
}

/** True when every number in [command] appears in what was said, so a rewrite never invents a time or amount. */
fun numbersSaid(command: String, said: String): Boolean {
    // Digit strings without leading zeros ("07" is 7): toLong() threw on a 30-digit amount and aborted the request.
    fun numbers(text: String) = Regex("""\d+""").findAll(text).map { it.value.trimStart('0').ifEmpty { "0" } }
    val heard = numbers(said).toSet()
    return numbers(command).all { it in heard }
}

private val confirmation = Regex("""^\s*(yes|yeah|yep|ya|ok|okay|sure|confirm|go ahead|do it|please do|haan|ha|ji)\b""")
private val reference = Regex("""\b(it|that|this|same|again)\b""")
private val numberWord = Regex(
    """\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|""" +
        """seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|hundred|thousand|half|quarter|noon|midnight|""" +
        // Hindi and Malayalam typed in Latin letters. "do" (2) counts only next to a unit, so "do it" stays a follow-up.
        """ek|teen|char|paanch|panch|chhe|chhah|saat|aath|nau|das|gyarah|barah|baje|bajkar|dedh|dhai|sava|saade|paune|""" +
        """do (baje|minute|minutes|ghante|ghanta|second)|onnu|randu|moonu|naalu|anchu|aaru|ezhu|ettu|onpathu|pathu|""" +
        """pathinonnu|panthrandu|mani|minuttu)\b""",
)

/** Number words and digits in Devanagari and Malayalam script. Matched anywhere: a false match only stops borrowing. */
private val nativeNumber = Regex(
    """[०-९൦-൯]|एक|दो|तीन|चार|पांच|पाँच|छह|छः|सात|आठ|नौ|दस|ग्यारह|बारह|बजे|डेढ़|ढाई|सवा|साढ़े|पौने|""" +
        """ഒന്ന|രണ്ട|മൂന്ന|നാല|അഞ്ച|ആറ|ഏഴ|എട്ട|ഒമ്പത|ഒൻപത|പത്ത|മണി""",
)

/**
 * What a rewrite's numbers may come from: only the current message. A short follow-up that says no number
 * itself, a confirmation ("yes") or a reference ("make it pm"), may borrow from the last exchange it answers.
 * "set an alarm at six" is a fresh request, so an earlier 7 cannot approve a rewrite to 7.
 * Number words count in every language NILA hears: "haan, saat baje" says a new time, so it borrows nothing.
 * ponytail: a fixed word list, so an unusual spelling of a number word is missed; extend the lists if seen.
 */
fun groundingText(input: String, lastTurn: Pair<String, String>?): String {
    val lower = input.lowercase()
    val followUp = lastTurn != null && !Regex("""\d""").containsMatchIn(lower) && !numberWord.containsMatchIn(lower) &&
        !nativeNumber.containsMatchIn(lower) &&
        lower.split(Regex("""\s+""")).count { it.isNotBlank() } <= 6 &&
        (confirmation.containsMatchIn(lower) || reference.containsMatchIn(lower))
    return if (followUp) "$input ${lastTurn!!.first} ${lastTurn.second}" else input
}

/** Asks Gemma for a transcript only, in each language's own script, never an answer to the request. */
// The speaker may use any of NILA's languages, or mix them in one sentence; Gemma detects which.
const val TRANSCRIPTION_PROMPT =
    "Transcribe this speech exactly as spoken. The speaker uses English, Hindi or Malayalam, and may mix them. " +
        "Write each language in its own script: Malayalam in Malayalam script, Hindi in Devanagari, English in Latin letters. " +
        "Output only the transcript, with no answer, translation or notes. If there is no clear speech, output exactly: NONE"

/**
 * Null for a transcript that isn't something the person said: "NONE" for silence, or the labelled
 * "English: … Hindi: … Malayalam: …" lines Gemma invented from background noise on the iQOO, which
 * were then sent as a request.
 */
fun spokenTranscript(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.equals("NONE", ignoreCase = true)) return null
    if (Regex("""(?im)^\s*(english|hindi|malayalam)\s*:""").containsMatchIn(text)) return null
    return text
}

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
/** [facts] reads what the user asked NILA to remember, fresh for every chat reply. */
class NilaBrain(
    private val context: Context,
    private val engine: NilaEngine,
    private val facts: () -> List<MemoryFact>,
) {
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
        // People and budget always come from the user's own words, whichever path chose search_food.
        fun answer(path: String, response: NilaResponse) =
            done(path, (response as? NilaResponse.FoodSearch)?.let { engine.foodConstraints(input, it) } ?: response).also { remember(input, it) }
        engine.calculate(input)?.let { return@withContext answer("calculator", it) }
        engine.calculateFollowUp(input, history.reversed().flatMap { (user, nila) -> listOf(nila, user) })?.let { return@withContext answer("calculator:follow-up", it) }
        val keyword = engine.route(input)
        if (keyword is NilaIntent.Call && keyword.call.capabilityId == REMEMBER && assistant != null) {
            return@withContext answer("rules+gemma:$REMEMBER", rememberWithKind(input, engine.execute(keyword)))
        }
        if (keyword is NilaIntent.Call) return@withContext answer("rules:${keyword.call.capabilityId}", engine.execute(keyword))
        if (!engine.isSmallTalk(input)) {
            // Any wording, follow-up or language: Gemma restates it as one plain command the rules understand.
            rewrite(input)?.let { command ->
                engine.calculate(command)?.let { return@withContext answer("rewrite:calculator", it) }
                val routed = engine.route(command)
                if (routed is NilaIntent.Call) return@withContext answer("rewrite:${routed.call.capabilityId}", engine.execute(routed))
            }
            // Stock FunctionGemma scored 4.7% on NILA's 12 tools (training eval); with Gemma 4 loaded, chat picks the tool.
            if (assistant == null) modelRoute(input)?.let { return@withContext answer("functiongemma", it) }
        }
        chat(input)?.let { return@withContext answer("gemma", it) }
        // Two different situations share this point: the model never loaded at all, or it loaded and
        // just gave nothing usable this one time. They need different messages, not the same fallback.
        if (assistant == null) {
            answer("no-model", NilaResponse.Text(NOT_LOADED_YET))
        } else {
            answer("fallback", engine.execute(NilaIntent.Converse(input)))
        }
    }

    private fun remember(input: String, response: NilaResponse) {
        history.addLast(input to (spokenText(response) ?: response::class.simpleName.orEmpty()))
        while (history.size > HISTORY_TURNS) history.removeFirst()
    }

    /**
     * Restates the user's message as one command from NILA's own phrasebook, using the last few turns, or
     * null for conversation. A command whose numbers nobody said is dropped: the model may not invent a time.
     */
    private fun rewrite(input: String): String? {
        val model = assistant ?: return null
        val transcript = history.joinToString("\n") { (user, nila) -> "User: $user\nNILA: $nila" }
        val raw = runCatching {
            model.createConversation(
                ConversationConfig(
                    // With the user's facts, "play some music" can become "play arijit singh" from "I like Arijit Singh".
                    systemInstruction = Contents.of(personaWith(REWRITE, facts())),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                ),
            ).use { conversation ->
                conversation.sendMessage("Conversation so far:\n${transcript.ifBlank { "(none)" }}\n\nNew message: $input")
                    .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
            }
        }.getOrNull() ?: return null
        val command = cleanCommand(raw) ?: return null
        return command.takeIf { numbersSaid(it, groundingText(input, history.lastOrNull())) }
            .also { Log.i(TAG, if (it == null) "rewrite dropped: unsaid numbers" else "rewrite -> command (${it.length} chars)") }
    }

    /**
     * "Remember …" is always saved in the user's own words; Gemma only names the fact's kind. If Gemma does
     * not call remember_fact, the fact is still saved, as Other: the model decides the kind, never whether to save.
     */
    private fun rememberWithKind(input: String, saved: NilaResponse): NilaResponse {
        if (saved !is NilaResponse.Remember) return saved
        val proposed = runCatching {
            assistant?.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(PERSONA),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                    tools = listOfNotNull(engine.capabilities[REMEMBER]).map { tool(CapabilityTool(it)) },
                    automaticToolCalling = false,
                ),
            )?.use { it.sendMessage(input) }
        }.getOrNull()?.toolCalls?.firstOrNull { it.name == REMEMBER }
        trace("gemma remember kind=${proposed?.arguments?.get("kind") != null}")
        return saved.copy(kind = MemoryKind.from(proposed?.arguments?.get("kind") as? String))
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
        val args = when (capability.id) {
            "create_alarm" -> engine.alarmArgs(input)
            // "Cancel my alarm" with no time means the next alarm; never trust a time the user did not say.
            "remove_alarm" -> engine.alarmArgs(input).orEmpty()
            "set_timer" -> engine.timerArgs(input)
            "create_event" -> engine.eventArgs(input)
            else -> null
        } ?: coerceArgs(arguments, capability)
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
    suspend fun transcribe(wav: ByteArray): String? = withContext(llm) {
        val model = assistant ?: return@withContext null
        val start = SystemClock.elapsedRealtime()
        runCatching {
            model.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use { conversation ->
                conversation.sendMessage(
                    Contents.of(Content.AudioBytes(wav), Content.Text(TRANSCRIPTION_PROMPT)),
                ).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
            }
        }.onFailure { Log.w(TAG, "transcribe failed", it) }.getOrNull()
            // Timing and size only: a transcript is what the user said and must never reach device logs.
            .also { Log.i(TAG, "transcribe ms=${SystemClock.elapsedRealtime() - start} bytes=${wav.size} chars=${it?.length ?: 0}") }
            .let(::spokenTranscript)
    }

    /**
     * Gemma 4 E2B answers and can also call a tool: the 270M router misses requests it was not fine-tuned
     * on ("im hungry get me some biryani" came back as chat on the iQOO 15). A proposed call goes through
     * the same [act] checks as FunctionGemma's, so chat never acts on its own.
     */
    /**
     * Answers a web question from the search results' own text, on the phone. Search services often return
     * only pages, and a list of links was all NILA showed. Null when Gemma isn't loaded or has nothing to say.
     */
    suspend fun answerFromWeb(question: String, results: List<WebResult>): String? = withContext(llm) {
        val model = assistant ?: return@withContext null
        val context = results.take(WEB_RESULTS_READ).mapIndexed { i, r -> "${i + 1}. ${r.title}: ${r.snippet.take(WEB_SNIPPET_CHARS)}" }.joinToString("\n")
        if (context.isBlank()) return@withContext null
        runCatching {
            model.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(WEB_ANSWER),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                ),
            ).use { it.sendMessage("Question: $question\n\nSearch results:\n$context").contents.contents.filterIsInstance<Content.Text>().joinToString("") { t -> t.text } }
        }.getOrNull()?.let { cleanReply(it) }
    }

    private fun chat(input: String): NilaResponse? {
        val model = assistant ?: return null
        val response = runCatching {
            model.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(personaWith(PERSONA, facts())),
                    initialMessages = history.flatMap { (user, nila) -> listOf(Message.user(user), Message.model(nila)) },
                    // A fresh seed per reply: with the default seed "tell me a joke" gave the same joke every time.
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.6, seed = kotlin.random.Random.nextInt()),
                    tools = engine.capabilities.values.map { tool(CapabilityTool(it)) },
                    automaticToolCalling = false,
                ),
            ).use { it.sendMessage(input) }
        }.getOrNull() ?: return null
        response.toolCalls.firstOrNull()?.let { proposed ->
            trace("gemma tool=${proposed.name} args=${proposed.arguments.keys}")
            act(proposed.name, proposed.arguments, input)?.let { return it }
        }
        val reply = cleanReply(response.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim())
            ?: return null
        return NilaResponse.Text(reply)
    }

    /**
     * Shared by every path that shows raw model text to the user: drops a reply that's Gemma's own
     * tool-call syntax leaked onto the text channel (e.g. "Sure! <|tool_call>call:x{...}"), and replaces
     * one that falsely claims an action happened. Null for blank, leaked or unusable text.
     */
    private fun cleanReply(text: String): String? = text.trim().takeIf { it.isNotBlank() }
        ?.also { if (looksLikeRawToolCall(it)) Log.i(TAG, "dropped a reply that leaked raw tool-call syntax") }
        ?.takeUnless { looksLikeRawToolCall(it) }
        ?.let { if (claimsAction(it)) NO_ACTION_TAKEN.also { Log.i(TAG, "replaced a reply that claimed an action") } else it }

    // vivo drops app logcat; the same keys-only lines go to a private file for device debugging.
    private fun trace(line: String) {
        runCatching { File(context.filesDir, "route.txt").appendText("$line\n") }
    }

    private companion object {
        const val TAG = "NilaBrain"
        const val HISTORY_TURNS = 4
        const val REMEMBER = "remember_fact"
        const val ALARM_HELP = "I couldn't read that time, so no alarm is set. Try \"set an alarm for 1:30 pm\"."
        const val NO_ACTION_TAKEN = "I haven't done that. Chat can't take actions. To set an alarm, say a time like \"set an alarm for 1:30 pm\" and tap Set alarm on the card."
        const val NOT_LOADED_YET = "I'm still starting up on this phone — give it a moment and try again. " +
            "Exact actions like \"set an alarm for 6\" or \"turn on the flashlight\" work even before I'm fully loaded."

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
        /** NILA's phrasebook: every command the keyword rules understand. Gemma maps any wording onto one. */
        const val WEB_RESULTS_READ = 5
        const val WEB_SNIPPET_CHARS = 400
        val WEB_ANSWER = """
            Answer the question in two or three short, plain sentences using only the search results given.
            Lead with the answer itself. If the results don't contain it, say you couldn't find it. No lists, no links.
        """.trimIndent()

        val REWRITE = """
            You turn the user's new message into ONE command for NILA, a phone assistant, using the conversation for context.
            Pick from these commands and fill the <parts> with the user's own words, names and numbers, in English:
            set an alarm for <time> | cancel the alarm for <time> | turn off my alarm | show my alarms
            set a timer for <n> minutes | pause the timer | resume the timer | stop the timer | how much time is left
            turn on the flashlight | turn off the flashlight
            open the camera | take a selfie | record a video | record my voice
            schedule <title> <day> at <time> | remind me to <task> every month on the <day> | take a note that <text> | show my notes
            call <name or number> | tell <name> <message>
            take me to <place> | <things> near me | search for <query>
            order food <cooked dish or meal from a restaurant> | shop groceries <item to buy from a store>
            Cooked dishes and meals are food: biryani, pizza, dosa, a paneer roll, dinner, lunch.
            Things you cook with or keep at home are groceries: milk, eggs, bread, fruit, bananas, vegetables, rice, atta, oil, sugar, snacks, soap, detergent.
            "buy milk" -> shop groceries milk. "we are out of eggs" -> shop groceries eggs. "buy dinner" -> order food dinner.
            A question about what to cook, make or eat with items the user already has is not a request to buy or
            order anything, even if it names milk, eggs, bread or other groceries: reply CHAT.
            "I have milk, eggs and bread, what recipe can I try" -> CHAT. "what can I cook with rice and dal" -> CHAT.
            play <song or artist> | pause the music | next song | previous song
            volume up | volume down | mute | vibrate mode | silent mode | ring mode
            open <wifi, bluetooth, battery, display, sound, location> settings | open <app name>
            how much battery is left | what is <n>% of <n> | remember that <fact>
            A short reply such as "yes", "do it" or "turn it off" means the action just discussed; restate it in full.
            Translate Hindi or Malayalam into the English command. Never invent a time, number, name or place.
            If the message is not a request for one of these commands, reply exactly: CHAT
            Reply with the command only, on one line.
        """.trimIndent()

        val PERSONA = """
            You are NILA, a friendly assistant that lives on the user's Android phone and runs fully on the device.
            Reply in one to three short, warm sentences, in the same language the user writes in (English, Hindi or Malayalam).
            Call a tool only when the user asks NILA to do something now: set or turn off an alarm, start a timer,
            turn the flashlight on or off, add a calendar event, save and show notes, open the camera, call someone through the dialer,
            draft a text message, search the web, play or pause music, change the volume or ring mode,
            open settings pages or any installed app, show alarms, tell the battery level,
            open YouTube, Netflix, Instagram or BGMI, order food on Swiggy, buy groceries on Zepto or Instamart,
            show the route to a place or find places nearby, remember facts the user tells NILA, and calculate percentages.
            Questions are not requests: "is biryani healthy", "what is Koramangala famous for" or "I have milk, eggs
            and bread, what recipe can I try" get a spoken answer, not a tool — naming an ingredient is not a request
            to buy or order it.
            Jokes, facts, advice and small talk are welcome: answer them yourself, briefly and warmly.
            For actions NILA has no tool for, such as payments outside a food or grocery order, say plainly that NILA cannot do that yet and name something it can do.
            Never offer to do it, never ask "do you want me to…", never ask for confirmation, and never say you ordered, paid, booked, sent or shared anything.
            If the user seems to want one of NILA's actions, tell them the words to say, for example "say: turn off the flashlight".
        """.trimIndent()
    }
}
