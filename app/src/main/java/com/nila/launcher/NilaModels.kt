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
import kotlinx.coroutines.CancellationException
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
    /** My NILA's health records, for questions like "when was my last blood test". */
    private val healthRecords: () -> List<HealthRecord> = { emptyList() },
) {
    /** Set when Gemma read the last message as a personal fact, so [learn] keeps it whatever its wording. */
    private var learnNext = false
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

    private fun loadModel(name: String, gpuFirst: Boolean, audio: Boolean, vision: Boolean = false, setState: (ModelState) -> Unit): Engine? {
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
                    // Gemma 4 E2B also reads images (My NILA's report photos), but only the report reader loads
                    // vision. On the CPU, like audio: on the GPU the iQOO 15 never finished loading the assistant.
                    visionBackend = if (vision) Backend.CPU() else null,
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


    /** Builds an app recipe from the user's idea with Gemma, on the LLM thread (spec §7). Null while Gemma is loading. */
    suspend fun buildApp(idea: String): com.nila.launcher.skills.BuildResult? = withContext(llm) {
        val model = assistant ?: return@withContext null
        com.nila.launcher.skills.AppBuilder(com.nila.launcher.skills.LiteRtRecipeModel(model)).build(idea)
    }

    /**
     * My NILA first: "what do you know about my wife" answers from memory, and "order my wife's favourite"
     * becomes "order butter chicken" before anything routes it. Unknown favourites are asked for, never guessed.
     */
    suspend fun respond(input: String): NilaResponse = withContext(llm) {
        // 1. Personal memory pre-routing (from HEAD)
        photoSearch(input, java.time.LocalDate.now())?.let { return@withContext it }
        myNilaAnswer(input)?.let { return@withContext it }
        if (isHealthQuestion(input)) return@withContext healthAnswer(input)
        if (isHealthMention(input)) {
            val kind = healthKindFor(input)
            return@withContext HealthPrompt(kind, kind.label, healthDate(input, java.time.LocalDate.now()))
        }
        if (isPersonalStatement(input)) return@withContext (learn(input) ?: chat(input) ?: NilaResponse.Text("Got it."))
        withNamedFavourite(input, facts())?.let { return@withContext answerFor(it) }
        val who = favouriteOf(input)
        if (who != null) {
            whichPersonQuestion(who, facts())?.let { return@withContext NilaResponse.Text(it) }
            withFavourite(input, facts())?.let { return@withContext answerFor(it) }
            return@withContext NilaResponse.Text("I don't know your $who's favourite yet. Tell me, like "my $who loves butter chicken".")
        }

        // 2. Core routing logic (from the commit)
        val start = SystemClock.elapsedRealtime()
        fun done(path: String, response: NilaResponse) = response.also {
            Log.i(TAG, "path=$path ms=${SystemClock.elapsedRealtime() - start} response=${it::class.simpleName}")
        }
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
            rewrite(input)?.let { command ->
                engine.calculate(command)?.let { return@withContext answer("rewrite:calculator", it) }
                val routed = engine.route(command)
                if (routed is NilaIntent.Call) return@withContext answer("rewrite:${routed.call.capabilityId}", engine.execute(routed))
            }
            if (assistant == null) modelRoute(input)?.let { return@withContext answer("functiongemma", it) }
        }
        chat(input)?.let { answer("gemma", it) } ?: answer("fallback", engine.execute(NilaIntent.Converse(input)))
    }
