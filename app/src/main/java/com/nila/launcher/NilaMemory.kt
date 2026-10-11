package com.nila.launcher

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.ContactsContract
import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray

/** What a remembered fact is about. The model picks the kind when it saves a fact; NILA never guesses it from keywords. */
enum class MemoryKind(val label: String) {
    Preference("Likes"),
    Diet("Diet"),
    Allergy("Allergy"),
    Routine("Routine"),
    Place("Place"),
    Other("Note");

    companion object {
        /** The kind a model named, or Other when it named none or one NILA doesn't know. */
        fun from(raw: String?): MemoryKind = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: Other
    }
}

/** One thing NILA knows about the user or someone close to them ([about]: "me", "wife", "amma"). Stays on the phone. */
data class MemoryFact(val id: String, val text: String, val kind: MemoryKind, val savedAt: Long, val about: String = ME)

internal const val ME = "me"

internal fun newFact(text: String, kind: MemoryKind, savedAt: Long, about: String = ME) =
    MemoryFact(UUID.randomUUID().toString(), text.trim(), kind, savedAt, aboutKey(about))

/** Adds [fact] unless it is blank or the same text (ignoring case, spacing and a closing full stop) is already saved. */
internal fun withFact(facts: List<MemoryFact>, fact: MemoryFact): List<MemoryFact> =
    if (fact.text.isBlank() || facts.any { sameText(it.text, fact.text) && it.about == fact.about }) facts else facts + fact

private val SelfWords = setOf("", "me", "i", "myself", "user", "the user", "self", "you", "mine")

/** Who a fact is about, as one lowercase key: "My Wife" and "wife" are the same person; "I" is "me". */
internal fun aboutKey(raw: String?): String {
    val key = raw.orEmpty().trim().lowercase().removePrefix("my ").removeSuffix("'s").trim()
    return if (key in SelfWords) ME else key
}

/** "You", or the person as written: "Wife", "Amma". */
internal fun aboutLabel(about: String): String = if (about == ME) "You" else about.replaceFirstChar(Char::uppercase)

/** Who a fact is for, in a sentence: "you", "your wife" (a relation) or "Priya" (a name). */
internal fun aboutPhrase(about: String): String = when {
    about == ME -> "you"
    about in RelationWords -> "your $about"
    else -> aboutLabel(about)
}

/** Facts grouped by person, the user first, then everyone else in the order NILA learned about them. */
internal fun factsByPerson(facts: List<MemoryFact>): List<Pair<String, List<MemoryFact>>> =
    facts.groupBy { it.about }.toList().sortedWith(compareBy<Pair<String, List<MemoryFact>>> { it.first != ME }.thenBy { p -> p.second.minOf { it.savedAt } })

private val PersonalCue = Regex(
    """\b(my|our)\s+(wife|husband|partner|son|daughter|kids?|children|mom|mother|amma|dad|father|appa|brother|sister|friend|boss|grandma|grandpa|baby)\b|""" +
        """\b(i|i'm|i am)\s+(love|like|hate|prefer|am allergic|allergic|vegetarian|vegan|don't eat|do not eat|can't eat)\b|""" +
        """\bmy\s+(favourite|favorite|birthday|anniversary|name|diet|allergy)\b|\b(favourite|favorite)\b""",
)

/** True when a message may carry a personal detail worth keeping; only then is Gemma asked to pick it out. */
internal fun mightBePersonal(text: String): Boolean = PersonalCue.containsMatchIn(text.lowercase())

private val PersonalStatement = Regex(
    """^\s*(?:my|our)\s+[\p{L}]+(?:'s)?\s+(?:loves?|likes?|hates?|prefers?|enjoys?|is|isn't|eats|doesn't|does not|can't|cannot|favourite|favorite|birthday|name|allergy)\b|""" +
        """^\s*(?:i|i'm|i am)\s+(?:love|like|hate|prefer|enjoy|am|'m|allergic|vegetarian|vegan|don't|do not|can't|cannot)\b|^\s*i'm\b""",
)
private val RequestWords = Regex(
    """\b(order|buy|get me|book|call|message|text|tell|remind|set|show|find|search|open|play|take me|turn|switch|start|stop|send|add|cancel|""" +
        """pay|delete|remove|schedule|navigate|what|who|how|when|where|can you|please)\b""",
)

/**
 * "my wife loves butter chicken" tells NILA something; it does not ask for anything. Such a statement is
 * remembered and answered, never routed to an order (it started a butter chicken search on the iQOO 15).
 */
internal fun isPersonalStatement(text: String): Boolean {
    val lower = text.lowercase()
    return PersonalStatement.containsMatchIn(lower) && !RequestWords.containsMatchIn(lower)
}

/** A saved fact must be made of the user's own words: every subject word of it appears in what they said. */
internal fun groundedFact(fact: String, said: String): Boolean {
    val subject = factWords(fact).filter { it !in RelationWords }
    return subject.isNotEmpty() && subject.all { mentions(said, it) }
}

private val RelationWords = setOf(
    "wife", "husband", "partner", "son", "daughter", "kid", "kids", "children", "mom", "mother", "amma", "dad", "father",
    "appa", "brother", "sister", "friend", "boss", "grandma", "grandpa", "baby", "user", "she", "he", "her", "his", "their",
)

private val FavouriteOf = Regex("""\bmy\s+([\p{L}]+?)(?:'s|s)?\s+(?:favourite|favorite|usual)(?:\s+(?:food|dish|meal|order))?\b""")

/** "order my wife's favourite" → the person ("wife"); null when no one's favourite is asked for. */
internal fun favouriteOf(text: String): String? = FavouriteOf.find(text.lowercase())?.groupValues?.get(1)?.let(::aboutKey)

/** The dish or item [about] likes most recently, in the fact's own words: "butter chicken". */
internal fun favouriteFor(about: String, facts: List<MemoryFact>): String? =
    facts.filter { it.about == about && it.kind == MemoryKind.Preference }.maxByOrNull { it.savedAt }
        ?.let { factWords(it.text).filter { w -> w !in RelationWords }.joinToString(" ").ifBlank { null } }

/** "order my wife's favourite" → "order butter chicken", or null when NILA doesn't know it yet. */
internal fun withFavourite(text: String, facts: List<MemoryFact>): String? {
    val about = favouriteOf(text) ?: return null
    val dish = favouriteFor(about, facts) ?: return null
    return FavouriteOf.replace(text.lowercase(), dish)
}

// Questions about the user or their people, answered from My NILA. Group 1 is the person; empty means the user.
private val AskAbout = listOf(
    Regex("""\bwhat do you (?:know|remember) about\s+(?:my\s+)?([\p{L} ]+?)\s*\??$"""),
    Regex("""\btell me (?:what you know )?about\s+(?:my\s+)?([\p{L} ]+?)\s*\??$"""),
    Regex("""\bwhat (?:does|do) my\s+([\p{L} ]+?)\s+(?:like|love|eat|prefer|enjoy)\b"""),
    Regex("""\b(?:what are|show|show me|give me|list) my (?:details|info|information|profile|preferences|likes)()\b"""),
    Regex("""^\s*my (?:details|info|information|profile)()\s*\??$"""),
    Regex("""\bwho am i()\b"""),
    Regex("""\bwhat do i (?:like|love|eat|prefer|enjoy)()\b"""),
)
private val AskName = Regex("""\b(?:what(?:'s| is) my name|do you know my name|what do you call me)\b""")

private fun normalized(text: String) = text.lowercase().trim().replace('’', '\'')

/** "what do you know about my wife" → "wife"; "…about me", "my details", "who am I" → "me". */
internal fun askedAbout(text: String): String? {
    val t = normalized(text)
    if (AskName.containsMatchIn(t)) return ME
    return AskAbout.firstNotNullOfOrNull { it.find(t) }?.groupValues?.get(1)?.let { if (it.isBlank()) ME else aboutKey(it) }
}

/** True for "what's my name": answered with the name alone, not the whole list. */
internal fun askedName(text: String): Boolean = AskName.containsMatchIn(normalized(text))

/** Everything NILA knows about one person, in the same clean lines My NILA shows, or that it knows nothing yet. */
internal fun personSummary(about: String, facts: List<MemoryFact>): String {
    val known = facts.filter { it.about == about && !isNameFact(it) }
    val name = if (about == ME) userName(facts) else null
    val who = if (about == ME) "you" else "your $about"
    if (known.isEmpty()) {
        return if (name != null) "You're $name. That's all I know so far: tell me more, like \"I'm vegetarian\"."
        else "I don't know anything about $who yet. Tell me, like \"my wife loves butter chicken\"."
    }
    val heading = if (name != null) "Here's what I know about you, $name:" else "Here's what I know about $who:"
    return heading + "\n" + known.joinToString("\n") { "• ${factLine(it.text)}" } + "\nYou can change any of this in My NILA."
}

/** "what's my name" → the saved name, or an invitation to share it. */
internal fun nameAnswer(facts: List<MemoryFact>): String =
    userName(facts)?.let { "You're $it." } ?: "You haven't told me your name yet. Say \"my name is …\"."

/** The single fact older builds saved joins the list as an Other fact. */
internal fun migrated(facts: List<MemoryFact>, legacy: String?, savedAt: Long): List<MemoryFact> =
    legacy?.takeIf { it.isNotBlank() }?.let { withFact(facts, newFact(it, MemoryKind.Other, savedAt)) } ?: facts

private fun sameText(a: String, b: String) = comparable(a) == comparable(b)

private fun comparable(text: String) = text.trim().lowercase().replace(Regex("""\s+"""), " ").trimEnd('.', '!')

/** The answer to "what do you remember": every fact with its kind, or a plain "nothing yet". */
internal fun memorySummary(facts: List<MemoryFact>): String =
    if (facts.isEmpty()) "You have not asked me to remember anything yet."
    else "You asked me to remember:\n" + facts.joinToString("\n") { fact ->
        val who = if (fact.about == ME) "" else "${aboutLabel(fact.about)}: "
        "• $who${fact.text} (${fact.kind.label.lowercase()})"
    }

// ---- Pure rules: legacy JSON to entities, people, asking once ---------------------------

/** The facts older builds kept as JSON in plain preferences, plus the single `fact` older still. Malformed entries are skipped. */
internal fun legacyFacts(json: String?, singleFact: String?, now: Long): List<MemoryFact> {
    val list = json?.let { runCatching { JSONArray(it) }.getOrNull() }
    val stored = list?.let {
        (0 until it.length()).mapNotNull { i ->
            val o = it.optJSONObject(i) ?: return@mapNotNull null
            val text = o.optString("text").ifBlank { return@mapNotNull null }
            MemoryFact(o.optString("id").ifBlank { UUID.randomUUID().toString() }, text, MemoryKind.from(o.optString("kind")), o.optLong("savedAt"), aboutKey(o.optString("about")))
        }
    }.orEmpty()
    return migrated(stored.fold(emptyList()) { acc, fact -> withFact(acc, fact) }, singleFact, now)
}

internal fun combinedLegacyFacts(secureJson: String?, plainJson: String?, singleFact: String?, now: Long): List<MemoryFact> =
    (legacyFacts(secureJson, null, now) + legacyFacts(plainJson, singleFact, now)).fold(emptyList(), ::withFact)

internal fun encodeFacts(facts: List<MemoryFact>): String = JSONArray().apply {
    facts.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("kind", it.kind.name).put("savedAt", it.savedAt).put("about", it.about)) }
}.toString()

internal fun decodeFacts(raw: String): List<MemoryFact> = legacyFacts(raw, null, 0)

internal fun movedFacts(secure: String?, plain: String?, plainLegacy: String?, savedAt: Long): List<MemoryFact> =
    combinedLegacyFacts(secure, plain, plainLegacy, savedAt)

/** One person row per about key (the user first), and every fact filed under its person. */
internal fun entitiesFromFacts(facts: List<MemoryFact>, now: Long, newId: () -> String = { UUID.randomUUID().toString() }): Pair<List<PersonEntity>, List<FactEntity>> {
    val people = factsByPerson(facts).map { (about, known) ->
        PersonEntity(newId(), about, aboutLabel(about), contactLookupKey = null, contactUri = null, linkAsked = false, createdAt = known.minOfOrNull { it.savedAt } ?: now)
    }
    val idByKey = people.associate { it.key to it.id }
    return people to facts.map { FactEntity(it.id, idByKey.getValue(it.about), it.text, it.kind.name, it.savedAt) }
}

/** True exactly when NILA should ask "Which contact is your [about]?": someone other than the user, not linked, never asked. */
internal fun shouldAskContactLink(about: String, person: PersonEntity?): Boolean =
    about != ME && (person == null || (person.contactLookupKey == null && !person.linkAsked))

private val MentionedRelation = Regex(
    """\bmy\s+(wife|husband|partner|son|daughter|mom|mother|amma|dad|father|appa|brother|sister|boss|grandma|grandpa)\b""",
)

/** "order something light for my wife" gives "wife"; null when the message names no one close to the user. */
internal fun mentionedPerson(text: String): String? =
    MentionedRelation.find(text.lowercase())?.groupValues?.get(1)?.let(::aboutKey)?.takeIf { it != ME }

/** A person NILA knows about, for the UI: name (the linked contact's when there is one), key, photo and facts. */
internal data class MemoryPerson(
    val key: String,
    val name: String,
    val photoUri: String?,
    val contactLookupKey: String?,
    val facts: List<MemoryFact>,
)

private fun factOf(row: FactRow) = MemoryFact(row.id, row.text, MemoryKind.from(row.kind), row.savedAt, row.about)

/**
 * The facts and the people they are about, in an encrypted Room database (SQLCipher; the passphrase is
 * wrapped by the Android Keystore). One database serves the whole process, so every [NilaMemory] shares it.
 * Nothing here touches the main thread: reads are Flows or suspend functions, and [facts] is a snapshot.
 */
internal class NilaMemory(context: Context) {
    private val store = MemoryStore.get(context.applicationContext)

    /** Every fact, kept current. Collect it from the UI. Empty until the database has opened. */
    val factsFlow: StateFlow<List<MemoryFact>> get() = store.facts

    /** The facts as of now, without waiting. Use [loadFacts] where a fresh read matters. */
    fun facts(): List<MemoryFact> = store.facts.value

    suspend fun loadFacts(): List<MemoryFact> = store.dao().allFacts().map(::factOf)

    /** The people NILA knows, each with their facts and, with Contacts access, their photo. */
    fun people(): Flow<List<MemoryPerson>> = store.people()

    suspend fun save(text: String, kind: MemoryKind, about: String = ME): List<MemoryFact> = store.save(newFact(text, kind, System.currentTimeMillis(), about))

    suspend fun delete(id: String): List<MemoryFact> = store.delete(id)

    suspend fun forget(text: String, about: String): List<MemoryFact> = store.forget(text, about)

    suspend fun clear(): List<MemoryFact> = store.clear()

    /**
     * True the one time NILA should ask which contact [about] is; the person is made if new, and
     * marked asked so a later call (or "Not now") never asks again.
     */
    suspend fun claimContactAsk(about: String): Boolean = store.claimContactAsk(aboutKey(about))

    /** Stores the contact's lookup key and name on the person. */
    suspend fun linkContact(about: String, contact: ContactChoice) = store.link(aboutKey(about), contact)
}

/** The process-wide database, opened lazily on a background thread. */
private class MemoryStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = scope.async(start = CoroutineStart.LAZY) { openAndMigrate() }

    val facts: StateFlow<List<MemoryFact>> =
        flow { emitAll(ready.await().dao().observeFacts()) }
            .map { rows -> rows.map(::factOf) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun dao() = ready.await().dao()

    fun people(): Flow<List<MemoryPerson>> =
        flow { emitAll(ready.await().dao().observePeople()) }
            .combine(facts) { people, all ->
                people.map { p ->
                    MemoryPerson(p.key, p.name, p.contactLookupKey?.let { contactPhotoUri(context, it) }, p.contactLookupKey, all.filter { it.about == p.key })
                }
            }
            .flowOn(Dispatchers.IO)

    suspend fun save(fact: MemoryFact): List<MemoryFact> {
        val db = ready.await()
        db.withTransaction {
            val dao = db.dao()
            val person = dao.person(fact.about) ?: personFor(fact.about).also { dao.insertPeople(listOf(it)) }
            val already = dao.textsOf(person.id).any { sameText(it, fact.text) }
            if (fact.text.isNotBlank() && !already) dao.insertFacts(listOf(FactEntity(fact.id, person.id, fact.text, fact.kind.name, fact.savedAt)))
        }
        return db.dao().allFacts().map(::factOf)
    }

    suspend fun delete(id: String): List<MemoryFact> {
        dao().deleteFact(id)
        return dao().allFacts().map(::factOf)
    }

    suspend fun forget(text: String, about: String): List<MemoryFact> {
        dao().forgetFact(text, aboutKey(about))
        return dao().allFacts().map(::factOf)
    }

    suspend fun clear(): List<MemoryFact> {
        dao().deleteAllPeople()
        return emptyList()
    }

    suspend fun claimContactAsk(key: String): Boolean {
        val db = ready.await()
        return db.withTransaction {
            val dao = db.dao()
            val person = dao.person(key)
            if (!shouldAskContactLink(key, person)) return@withTransaction false
            if (person == null) dao.insertPeople(listOf(personFor(key)))
            dao.markAsked(key)
            true
        }
    }

    suspend fun link(key: String, contact: ContactChoice) {
        val db = ready.await()
        db.withTransaction {
            val dao = db.dao()
            if (dao.person(key) == null) dao.insertPeople(listOf(personFor(key)))
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(contact.lookupKey)).toString()
            dao.link(key, contact.lookupKey, uri, contact.name)
        }
    }

    private fun personFor(key: String) =
        PersonEntity(UUID.randomUUID().toString(), key, aboutLabel(key), null, null, linkAsked = false, createdAt = System.currentTimeMillis())

    private suspend fun openAndMigrate(): MemoryDatabase {
        val db = openOrReset(MemoryKeyStore(context))
        migrateLegacyPrefs(db)
        return db
    }

    /** Memory is a convenience: if the key can no longer open the database, start fresh rather than crash. */
    private fun openOrReset(keys: MemoryKeyStore): MemoryDatabase {
        fun attempt(): MemoryDatabase? = keys.passphrase()?.let { pass ->
            val db = MemoryDatabase.open(context, pass)
            runCatching { db.openHelper.writableDatabase }.onFailure { db.close() }.map { db }.getOrNull()
        }
        attempt()?.let { return it }
        MemoryDatabase.wipe(context)
        keys.reset()
        return checkNotNull(attempt()) { "Memory database could not be opened" }
    }

    /** Moves prior plain and encrypted JSON stores into SQLCipher; remove old copies only after the DB commit. */
    private suspend fun migrateLegacyPrefs(db: MemoryDatabase) {
        val plain = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val secure = runCatching {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, LEGACY_SECURE_PREFS, key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
        val secureJson = runCatching { secure?.getString(LEGACY_FACTS, null) }.getOrNull()
        val plainJson = plain.getString(LEGACY_FACTS, null)
        val single = plain.getString(LEGACY_FACT, null)
        if (secureJson == null && plainJson == null && single == null) return
        val now = System.currentTimeMillis()
        val (people, facts) = entitiesFromFacts(combinedLegacyFacts(secureJson, plainJson, single, now), now)
        val saved = runCatching {
            db.withTransaction {
                db.dao().insertPeople(people)
                db.dao().insertFacts(facts)
            }
        }.isSuccess
        if (saved) {
            plain.edit().remove(LEGACY_FACTS).remove(LEGACY_FACT).commit()
            if (secureJson != null) secure?.edit()?.remove(LEGACY_FACTS)?.commit()
        }
    }

    companion object {
        private const val LEGACY_PREFS = "nila_memory"
        private const val LEGACY_SECURE_PREFS = "nila_memory_secure"
        private const val LEGACY_FACTS = "facts"
        private const val LEGACY_FACT = "fact"

        @Volatile private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore = instance ?: synchronized(this) { instance ?: MemoryStore(context).also { instance = it } }
    }
}

// ---- How saved facts shape results -------------------------------------------------
// These read the words of the user's own facts. The model already chose each fact's kind;
// nothing here decides what a request means.

private const val MIN_WORD_LENGTH = 3
/** A fact with two or more subject words matches only when two of them are in the name: "filter coffee" is not "cold coffee". */
private const val STRONG_MATCH_WORDS = 2
internal const val CHAT_FACT_LIMIT = 5

private val DayWords: Map<String, Set<DayOfWeek>> =
    DayOfWeek.entries.associate { it.name.lowercase() to setOf(it) } +
        mapOf(
            "weekday" to setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
            "weekend" to setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        )
private val PartWords: Map<String, PartOfDay> = PartOfDay.entries.associateBy { it.name.lowercase() }

/** Words that frame a fact rather than name its subject: "I really like", "allergic to", "on Fridays". */
private val FramingWords = setOf(
    "the", "and", "with", "for", "from", "into", "this", "that", "are", "was", "has", "have", "had", "not",
    "like", "likes", "liked", "love", "loves", "loved", "enjoy", "enjoys", "prefer", "prefers", "preferred",
    "favourite", "favorite", "always", "usually", "often", "really", "very", "much", "lot", "some", "any",
    "allergic", "allergy", "allergies", "intolerant", "intolerance", "avoid", "can't", "cannot", "eat", "eats",
    "drink", "drinks", "eating", "drinking", "having", "order", "only", "every", "each", "day", "days", "user", "i'm",
    "who", "when", "food", "foods", "breakfast", "lunch", "dinner", "meal", "meals", "snack", "snacks",
) + DayWords.keys.flatMap { listOf(it, "${it}s") } + PartWords.keys.flatMap { listOf(it, "${it}s") }

internal fun words(text: String) = text.lowercase().split(Regex("""[^\p{L}\p{N}']+""")).filter(String::isNotBlank)

/** The words that say what a fact is about: "I really like filter coffee in the morning" gives [filter, coffee]. */
internal fun factWords(text: String): List<String> =
    words(text).filter { it.length >= MIN_WORD_LENGTH && it !in FramingWords }.distinct()

/** Whole-word match that forgives a plural on either side: "peanuts" finds "peanut chikki". */
internal fun mentions(text: String, word: String): Boolean {
    val stem = Regex.escape(if (word.length > MIN_WORD_LENGTH + 1) word.removeSuffix("s") else word)
    return Regex("""\b$stem(s|es)?\b""").containsMatchIn(text.lowercase())
}

/** "You like filter coffee" when [name] is what a Preference fact is about; null when no Preference fits. */
internal fun memoryBoost(name: String, facts: List<MemoryFact>): String? =
    facts.filter { it.kind == MemoryKind.Preference && it.about == ME }.firstNotNullOfOrNull { fact ->
        val subject = factWords(fact.text).ifEmpty { return@firstNotNullOfOrNull null }
        val matched = subject.filter { mentions(name, it) }
        matched.takeIf { it.size >= minOf(STRONG_MATCH_WORDS, subject.size) }?.let { "You like ${it.joinToString(" ")}" }
    }

/** True when any word of a Preference fact is in [name]; groceries are already narrowed by the search word. */
internal fun sharesPreferenceWord(name: String, facts: List<MemoryFact>): Boolean =
    facts.filter { it.kind == MemoryKind.Preference && it.about == ME }.flatMap { factWords(it.text) }.any { mentions(name, it) }

/** "check: peanuts" when [text] names an allergen from an Allergy fact; null otherwise. */
internal fun allergyCheck(text: String, facts: List<MemoryFact>): String? =
    facts.filter { it.kind == MemoryKind.Allergy }.flatMap { factWords(it.text) }.firstOrNull { mentions(text, it) }?.let { "check: $it" }

/** True when a fact's own day and time words, if it has any, include [now]: "on Tuesdays", "weekday mornings". */
internal fun appliesAt(text: String, now: LocalDateTime): Boolean {
    val said = words(text).map { it.removeSuffix("s") }
    val days = said.flatMap { DayWords[it].orEmpty() }.toSet()
    val parts = said.mapNotNull { PartWords[it] }.toSet()
    return (days.isEmpty() || now.dayOfWeek in days) && (parts.isEmpty() || partOfDay(now.hour) in parts)
}

internal val VegWord = Regex("""(?<!non[- ])\b(veg|vegetarian|vegan)\b""")

/** True when a Diet fact says veg and its day or time, if it names one, is now. */
internal fun wantsVeg(facts: List<MemoryFact>, now: LocalDateTime): Boolean =
    facts.any { it.kind == MemoryKind.Diet && it.about == ME && VegWord.containsMatchIn(it.text.lowercase()) && appliesAt(it.text, now) }

/** What memory changes about one food search, worked out once when the search runs. */
internal data class FoodMemory(val facts: List<MemoryFact>, val vegPreferred: Boolean) {
    companion object {
        val None = FoodMemory(emptyList(), vegPreferred = false)
    }
}

internal fun foodMemory(facts: List<MemoryFact>, now: LocalDateTime) = FoodMemory(facts, wantsVeg(facts, now))

private val PersonalKinds = setOf(MemoryKind.Preference, MemoryKind.Diet, MemoryKind.Allergy)

/** Up to [CHAT_FACT_LIMIT] facts for chat: all of them when few, else likes, diet and allergies first, newest first. */
internal fun chatFacts(facts: List<MemoryFact>): List<MemoryFact> =
    if (facts.size <= CHAT_FACT_LIMIT) facts
    else facts.sortedWith(compareByDescending<MemoryFact> { it.kind in PersonalKinds }.thenByDescending { it.savedAt }).take(CHAT_FACT_LIMIT)

/** The chat persona with the user's own facts appended; unchanged when there are none. */
internal fun personaWith(persona: String, facts: List<MemoryFact>): String {
    val chosen = chatFacts(facts).ifEmpty { return persona }
    return persona + "\nThings the user told you: " + chosen.joinToString("; ") { if (it.about == ME) it.text else "${it.about}: ${it.text}" } +
        ".\nUse them naturally when they help, and never invent other facts about the user."
}
