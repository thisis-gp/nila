package com.nila.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// NILA owns one delivery address. Every app (Swiggy, Instamart, Zepto, Zomato, any added later) delivers
// there; NILA finds it among each app's saved addresses, or saves it in the app when the app allows that.

/** The one address NILA delivers to. [lat]/[lng] are present when an app or the phone's location gave them. */
data class NilaAddress(
    val label: String,
    val line: String,
    val area: String = "",
    val city: String = "",
    val pincode: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
) {
    /** The pincode from its own field, else the last one in the line. */
    val pin: String? get() = pincode?.trim()?.takeIf(PINCODE::matches) ?: pincodeIn(line)
}

/** An address as one app saved it, with that app's id for it. */
data class ProviderAddress(val id: String, val address: NilaAddress)

/** The one thing the user can do when an app can't take NILA's address. */
sealed interface AddressFix {
    data class OpenApp(val appPackage: String) : AddressFix
}

/** Whether one app now has NILA's address. */
sealed interface AddressResult {
    data class Ready(val providerAddressId: String) : AddressResult
    /** The app didn't have the address, so NILA saved it there just now. */
    data class Added(val providerAddressId: String) : AddressResult
    /**
     * The app doesn't have the address, but has [label] close by, so prices come from there. [distanceKm] is null
     * when only the pincode is known to match.
     */
    data class Nearby(val providerAddressId: String, val label: String, val distanceKm: Double?) : AddressResult
    data class CantAdd(val reason: String, val fix: AddressFix?) : AddressResult
    data class Failed(val reason: String) : AddressResult
}

/** The app's id for the address, when it has one. */
val AddressResult.providerAddressId: String?
    get() = when (this) {
        is AddressResult.Ready -> providerAddressId
        is AddressResult.Added -> providerAddressId
        is AddressResult.Nearby -> providerAddressId
        else -> null
    }

/** The name and phone an app needs to save a new address, remembered from the address form. */
data class NilaContact(val name: String, val phone: String)

/** The few prefs calls the address book needs, so it can be tested without Android. */
internal interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

private class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

/**
 * NILA's address of record and the few addresses used before it. The single source of truth: apps read the
 * address from here and never keep their own choice. The old Swiggy-only choice (`last_address`, a Swiggy
 * address id) moves here once, the first time Swiggy's saved addresses are at hand.
 */
internal class NilaAddressBook(private val store: KeyValueStore) {
    constructor(context: Context) : this(PrefsStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))

    fun chosen(): NilaAddress? = store.get(CHOSEN)?.let { runCatching { nilaAddressFrom(JSONObject(it)) }.getOrNull() }

    fun recent(): List<NilaAddress> = store.get(RECENT)?.let { raw ->
        runCatching { JSONArray(raw).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::nilaAddressFrom) } } }.getOrNull()
    }.orEmpty()

    /** Makes [address] the one NILA delivers to and puts it first among the recent ones. */
    fun choose(address: NilaAddress) {
        store.put(CHOSEN, address.toJson().toString())
        val recent = listOf(address) + recent().filterNot { sameAddress(it, address) }
        store.put(RECENT, JSONArray(recent.take(RECENT_COUNT).map { it.toJson() }).toString())
    }

    var contact: NilaContact?
        get() = store.get(CONTACT_NAME)?.let { name -> store.get(CONTACT_PHONE)?.let { NilaContact(name, it) } }
        set(value) {
            store.put(CONTACT_NAME, value?.name)
            store.put(CONTACT_PHONE, value?.phone)
        }

    /** True while the old Swiggy-only choice hasn't moved here yet. */
    val needsMigration: Boolean get() = chosen() == null && store.get(LEGACY_SWIGGY_ID) != null

    /**
     * Moves the old Swiggy choice here: the Swiggy address with that id becomes NILA's address. Done once;
     * an id Swiggy no longer lists is kept, so a failed Swiggy call doesn't lose it.
     */
    fun migrate(swiggy: List<ProviderAddress>): NilaAddress? {
        chosen()?.let { return it }
        val legacy = store.get(LEGACY_SWIGGY_ID) ?: return null
        val found = swiggy.firstOrNull { it.id == legacy } ?: return null
        choose(found.address)
        store.put(LEGACY_SWIGGY_ID, null)
        return found.address
    }

    /** NILA's address, moving the old Swiggy choice here first if it is still there. */
    suspend fun current(swiggySaved: suspend () -> List<ProviderAddress>): NilaAddress? =
        chosen() ?: if (needsMigration) migrate(swiggySaved()) else null

    private companion object {
        const val PREFS = "nila_food"
        const val CHOSEN = "nila_address"
        const val RECENT = "nila_recent_addresses"
        const val CONTACT_NAME = "nila_contact_name"
        const val CONTACT_PHONE = "nila_contact_phone"
        const val LEGACY_SWIGGY_ID = "last_address"
        const val RECENT_COUNT = 5
    }
}

internal fun NilaAddress.toJson(): JSONObject = JSONObject()
    .put("label", label).put("line", line).put("area", area).put("city", city)
    .apply {
        pincode?.let { put("pincode", it) }
        lat?.let { put("lat", it) }
        lng?.let { put("lng", it) }
    }

internal fun nilaAddressFrom(o: JSONObject): NilaAddress? {
    val line = o.pick("line") ?: return null
    return NilaAddress(
        label = o.pick("label") ?: "Address",
        line = line,
        area = o.pick("area").orEmpty(),
        city = o.pick("city").orEmpty(),
        pincode = o.pick("pincode"),
        lat = o.pick("lat")?.toDoubleOrNull(),
        lng = o.pick("lng")?.toDoubleOrNull(),
    )
}

/** Where the phone is, as an address: street, area and city, with the coordinates of the fix. */
internal fun nilaAddressOf(place: CurrentPlace): NilaAddress = NilaAddress(
    label = place.street.ifBlank { place.area }.ifBlank { "Current location" },
    line = listOf(place.street, place.area, place.city).filter(String::isNotBlank).distinct().joinToString(", ") +
        place.pincode.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty(),
    area = place.area,
    city = place.city,
    pincode = place.pincode.takeIf(String::isNotBlank),
    lat = place.lat,
    lng = place.lng,
)

/** The comparison's view of an address: no coordinates, so matching falls back to the line's words. */
internal fun DeliveryAddress.toNila(): NilaAddress = NilaAddress(label, line, pincode = pincode)

/**
 * Every saved address in an app's answer, under the field names apps give them. An object counts only with
 * an id and an address line; a pincode is read from its own field, else from any non-id text in the object.
 */
internal fun parseProviderAddresses(result: JSONObject): List<ProviderAddress> =
    allObjects(result)
        .mapNotNull { o ->
            val id = o.pick(*ID_KEYS) ?: return@mapNotNull null
            val line = o.pick(*LINE_KEYS) ?: return@mapNotNull null
            val pin = o.pick(*PIN_KEYS)?.takeIf(PINCODE::matches) ?: pincodeIn(line)
                ?: o.keys().asSequence().filterNot { it.contains("id", ignoreCase = true) }.firstNotNullOfOrNull { o.pick(it)?.let(::pincodeIn) }
            ProviderAddress(
                id,
                NilaAddress(
                    label = o.pick(*LABEL_KEYS)?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Address",
                    line = line,
                    area = o.pick("area", "locality").orEmpty(),
                    city = o.pick("city").orEmpty(),
                    pincode = pin,
                    lat = o.pick("lat", "latitude")?.toDoubleOrNull(),
                    lng = o.pick("lng", "longitude", "lon")?.toDoubleOrNull(),
                ),
            )
        }
        .distinctBy { it.id }

private val ID_KEYS = arrayOf("id", "addressId", "address_id")
private val LINE_KEYS = arrayOf("address", "fullAddress", "full_address", "formattedAddress", "displayAddress", "addressLine", "location_name", "area")
private val LABEL_KEYS = arrayOf("annotation", "addressTag", "tag", "label", "addressCategory", "category")
private val PIN_KEYS = arrayOf("pincode", "pinCode", "postalCode", "zipCode", "zipcode", "pin")

// ---- Matching ------------------------------------------------------------------

/**
 * The saved address that is [nila], or null. Never a guess at a different building: both need the same
 * pincode, and then the coordinates decide when both sides have them (within [SAME_PLACE_METRES]); without
 * them, the line must share a building or street word. A city or area alone never matches, and two
 * different house or flat numbers are two different addresses. The very same line always matches.
 */
internal fun matchAddress(nila: NilaAddress, saved: List<ProviderAddress>): ProviderAddress? =
    saved.filter { sameAddress(nila, it.address) }
        .minWithOrNull(compareBy<ProviderAddress> { distanceMetres(nila, it.address) ?: Double.MAX_VALUE }.thenByDescending { sharedWords(nila, it.address).size })

internal fun sameAddress(a: NilaAddress, b: NilaAddress): Boolean {
    if (normalized(a.line).isNotBlank() && normalized(a.line) == normalized(b.line)) return true
    val numberA = houseNumberOf(a.line)
    val numberB = houseNumberOf(b.line)
    if (numberA != null && numberB != null && numberA != numberB) return false
    // Zomato's saved addresses are one name string, often without a pincode: then the building and street must agree.
    if (a.pin == null || b.pin == null) return sharedWords(a, b).size >= WORDS_WITHOUT_PINCODE
    if (a.pin != b.pin) return false
    distanceMetres(a, b)?.let { return it <= SAME_PLACE_METRES }
    return sharedWords(a, b).isNotEmpty()
}

/**
 * The closest saved address within [maxKm] of [nila], for an app that has no exact match. With coordinates on
 * both sides the distance decides; without them only the same pincode counts. Never the city alone.
 */
internal fun nearestAddress(nila: NilaAddress, saved: List<ProviderAddress>, maxKm: Double): AddressResult.Nearby? {
    val candidates = saved.mapNotNull { candidate ->
        val km = distanceMetres(nila, candidate.address)?.div(METRES_IN_KM)
        when {
            km != null -> candidate.takeIf { km <= maxKm }?.let { it to km }
            nila.pin != null && nila.pin == candidate.address.pin -> candidate to null
            else -> null
        }
    }
    val (best, km) = candidates.minWithOrNull(compareBy { it.second ?: Double.MAX_VALUE }) ?: return null
    return AddressResult.Nearby(best.id, best.address.label, km)
}

private const val METRES_IN_KM = 1000.0

/**
 * A short name for an address line: its first part that names a place ("WeWork Galaxy"), never the house
 * number, a floor or the pincode. A leading number is dropped from a part ("43 Residency Road" is "Residency Road").
 */
internal fun shortPlaceName(line: String, fallback: String): String {
    val parts = line.substringAfter(": ").split(',').map { it.trim().replace(LEADING_NUMBER, "").trim() }
    val name = parts.firstOrNull { part -> part.any(Char::isLetter) && !FLOOR_PART.containsMatchIn(part) } ?: return fallback
    return name.take(MAX_SHORT_NAME)
}

/** "your Home address, 3 km away": where an app priced from when it had no exact address. */
internal fun nearbyText(nearby: AddressResult.Nearby): String {
    val km = nearby.distanceKm
    val away = when {
        km == null -> "same pincode"
        km < 1.0 -> "under 1 km away"
        else -> "${km.roundToInt()} km away"
    }
    return "your ${nearby.label} address, $away"
}

private val LEADING_NUMBER = Regex("""^[\d/\-]+[a-z]?\b[\s\-]*""", RegexOption.IGNORE_CASE)
private val FLOOR_PART = Regex("""\b(floor|flr|flat|door)\b""", RegexOption.IGNORE_CASE)
private const val MAX_SHORT_NAME = 28

/** Within this distance two pins on the same pincode are one building; geocoded pins drift by a few dozen metres. */
internal const val SAME_PLACE_METRES = 300.0

/** Without a pincode on one side, this many shared building or street words make it the same place. */
internal const val WORDS_WITHOUT_PINCODE = 2

private fun distanceMetres(a: NilaAddress, b: NilaAddress): Double? {
    val (lat1, lng1) = (a.lat ?: return null) to (a.lng ?: return null)
    val (lat2, lng2) = (b.lat ?: return null) to (b.lng ?: return null)
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return 2 * EARTH_RADIUS_METRES * asin(sqrt(h))
}

private const val EARTH_RADIUS_METRES = 6_371_000.0

/** Building and street words both lines share: not the area, city, numbers or generic words like "road". */
private fun sharedWords(a: NilaAddress, b: NilaAddress): Set<String> {
    val locality = localityWords(a) + localityWords(b)
    return (placeWords(a.line) intersect placeWords(b.line)) - locality
}

private fun placeWords(line: String) = addressWords(line).filter { it.length >= MIN_WORD && it.any(Char::isLetter) && it !in GENERIC_WORDS }.toSet()

/**
 * The area and city of an address: its own fields, plus the last two parts of its line ("Koramangala,
 * Bengaluru 560034"), unless a part names a street ("Residency Road"), which stays a street word.
 */
private fun localityWords(address: NilaAddress): Set<String> {
    val parts = address.line.split(',').map(String::trim).filter(String::isNotBlank)
    val tail = parts.takeLast(LOCALITY_PARTS).filterNot { part -> addressWords(part).any { it in STREET_WORDS } }
    return (listOf(address.area, address.city) + tail).flatMap(::addressWords).toSet()
}

private fun addressWords(text: String) = text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter(String::isNotBlank)

private fun normalized(line: String) = addressWords(line.substringAfter(": ")).joinToString(" ")

/** The first house, flat or plot number in a line, never its pincode. */
private fun houseNumberOf(line: String): String? {
    val pin = pincodeIn(line)
    return HOUSE_NUMBER.findAll(line).map { it.value.lowercase() }.firstOrNull { it != pin }
}

/** An Indian pincode never starts with 0; the last one in a line is the pincode, not a flat or plot number. */
internal fun pincodeIn(text: String): String? = PINCODE_IN.findAll(text).lastOrNull()?.value

private val PINCODE = Regex("""[1-9]\d{5}""")
private val PINCODE_IN = Regex("""\b[1-9]\d{5}\b""")
private val HOUSE_NUMBER = Regex("""\b\d+[a-z]?\b""", RegexOption.IGNORE_CASE)
private const val MIN_WORD = 3
private const val LOCALITY_PARTS = 2
private val STREET_WORDS = setOf("road", "rd", "street", "st", "lane", "marg", "cross", "main", "avenue", "highway")
private val GENERIC_WORDS = STREET_WORDS + setOf(
    "floor", "flat", "block", "tower", "building", "near", "opp", "opposite", "behind", "india", "apartment",
    "apartments", "house", "plot", "phase", "sector", "stage", "layout", "nagar", "colony", "the", "and", "address",
)

// ---- The address picker --------------------------------------------------------

/** One address in NILA's picker, with the places it came from ("Recent", "Swiggy", "Zomato"). */
data class AddressChoice(val address: NilaAddress, val sources: List<String>)

/**
 * NILA's recent addresses first, then each app's saved ones, an address saved in several apps shown once
 * with every app that has it. The first copy seen is kept: a recent address keeps NILA's own label.
 */
internal fun addressChoices(recent: List<NilaAddress>, saved: List<Pair<String, List<ProviderAddress>>>): List<AddressChoice> {
    val choices = mutableListOf<AddressChoice>()
    fun add(address: NilaAddress, source: String) {
        val index = choices.indexOfFirst { sameAddress(it.address, address) }
        if (index < 0) choices += AddressChoice(address, listOf(source))
        else if (source !in choices[index].sources) choices[index] = choices[index].copy(sources = choices[index].sources + source)
    }
    recent.forEach { add(it, RECENT_SOURCE) }
    saved.forEach { (app, list) -> list.forEach { add(it.address, app) } }
    return choices
}

internal const val RECENT_SOURCE = "Recent"

// ---- Keeping every app on NILA's address --------------------------------------

/** Each app's answer for one NILA address, by provider id. */
internal data class AddressSyncState(val address: NilaAddress? = null, val results: Map<String, AddressResult> = emptyMap())

/** "Added WeWork Galaxy to Swiggy": only for apps that got the address just now; null otherwise. */
internal fun addedNote(state: AddressSyncState, providers: List<FoodProvider> = foodProviders): String? {
    val address = state.address ?: return null
    val names = providers.filter { state.results[it.id] is AddressResult.Added }.map { it.name }
    if (names.isEmpty()) return null
    return "Added ${address.label} to ${names.joinToString(" and ")}"
}

/**
 * Puts NILA's address on every signed-in app in the background, a few at a time. Choosing another address
 * cancels the run for the old one.
 */
internal class NilaAddressSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val providers: List<FoodProvider> = foodProviders,
) {
    private val board = MutableStateFlow(AddressSyncState())
    val state: StateFlow<AddressSyncState> = board
    private var job: Job? = null

    /** An app already handled in the foreground (Swiggy, which the order flow needs right away). */
    fun record(address: NilaAddress, providerId: String, result: AddressResult) = board.update {
        if (it.address == address) it.copy(results = it.results + (providerId to result)) else AddressSyncState(address, mapOf(providerId to result))
    }

    fun ensure(address: NilaAddress, skip: Set<String> = emptySet()) {
        job?.cancel()
        board.update { if (it.address == address) it else AddressSyncState(address) }
        val todo = providers.filter { it.id !in skip && it.connected(context) }
        job = scope.launch {
            boundedAll(todo, QUOTE_PARALLEL, QUOTE_TIMEOUT_MS, { AddressResult.Failed(it) }, { it.ensureAddress(context, address) }) { provider, result ->
                record(address, provider.id, result)
            }
        }
    }
}
