package com.nila.launcher

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** One app with a real bill. */
internal data class PricedApp(val provider: FoodProvider, val quote: ProviderQuote.Priced) {
    val total: Long get() = quote.bill.total
}

/**
 * The comparison as the card shows it: the cheapest app, the other priced apps by total, then the apps
 * without a price, each with its reason.
 */
internal data class RankedComparison(
    val winner: PricedApp?,
    val others: List<PricedApp>,
    val unpriced: List<Pair<FoodProvider, ProviderQuote>>,
) {
    /** How much the winner saves against the next cheapest app; null with nothing to compare against. */
    val savings: Long? get() = winner?.let { w -> others.firstOrNull()?.let { it.total - w.total } }
    val runnerUp: FoodProvider? get() = others.firstOrNull()?.provider
}

/**
 * Cheapest total first. A tie goes to the app NILA can order on, then to the order the apps were given in.
 * Unpriced apps keep that order within their reason: still checking, sign in, couldn't reach, not there.
 */
internal fun rank(quotes: Map<FoodProvider, ProviderQuote>): RankedComparison {
    val position = quotes.keys.withIndex().associate { it.value to it.index }
    val priced = quotes.mapNotNull { (provider, quote) -> (quote as? ProviderQuote.Priced)?.let { PricedApp(provider, it) } }
        .sortedWith(compareBy<PricedApp> { it.total }.thenByDescending { it.provider.canOrder }.thenBy { position[it.provider] })
    val unpriced = quotes.toList().filter { it.second !is ProviderQuote.Priced }.sortedBy { unpricedOrder(it.second) }
    return RankedComparison(priced.firstOrNull(), priced.drop(1), unpriced)
}

private fun unpricedOrder(quote: ProviderQuote) = when (quote) {
    ProviderQuote.Checking -> 0
    ProviderQuote.SignIn -> 1
    is ProviderQuote.Failed -> 2
    is ProviderQuote.NotAvailable -> 3
    is ProviderQuote.Priced -> 4
}

/** "Cheapest on Swiggy · ₹312 with SWIGGYIT · 28 min"; null until some app has a price. */
internal fun summaryLine(ranked: RankedComparison): String? {
    val winner = ranked.winner ?: return null
    val lead = when {
        ranked.savings == 0L -> "Same total on ${winner.provider.name} and ${ranked.runnerUp?.name}"
        ranked.others.isEmpty() -> winner.provider.name
        else -> "Cheapest on ${winner.provider.name}"
    }
    val price = paiseText(winner.total) + winner.quote.coupon?.let { " with $it" }.orEmpty()
    return listOfNotNull(lead, price, winner.quote.etaMinutes?.let { "$it min" }).joinToString(" · ")
}

/**
 * Asks every provider at once, at most [permits] at a time. A provider that throws or runs past
 * [timeoutMillis] is a failure for that provider only; cancelling the caller cancels them all.
 */
internal suspend fun quoteAll(
    providers: List<FoodProvider>,
    permits: Int = QUOTE_PARALLEL,
    timeoutMillis: Long = QUOTE_TIMEOUT_MS,
    quote: suspend (FoodProvider) -> ProviderQuote,
    onResult: (FoodProvider, ProviderQuote) -> Unit,
) = boundedAll(providers, permits, timeoutMillis, { ProviderQuote.Failed(it) }, quote, onResult)

/** Runs [work] for every item, [permits] at a time, each bounded by [timeoutMillis]; failures become [failed]. */
internal suspend fun <K, T> boundedAll(
    items: List<K>,
    permits: Int,
    timeoutMillis: Long,
    failed: (String) -> T,
    work: suspend (K) -> T,
    onResult: (K, T) -> Unit,
) = coroutineScope {
    val gate = Semaphore(permits)
    items.forEach { item ->
        launch { onResult(item, gate.withPermit { attempt(item, timeoutMillis, failed, work) }) }
    }
}

private suspend fun <K, T> attempt(item: K, timeoutMillis: Long, failed: (String) -> T, work: suspend (K) -> T): T =
    try {
        withTimeoutOrNull(timeoutMillis) { work(item) } ?: failed("took too long")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(e.message ?: "something went wrong")
    }

/** Every app's quote for one cart, by provider id. [key] names the cart, so a card never shows another cart's prices. */
internal data class QuoteBoard(val key: String = "", val quotes: Map<String, ProviderQuote> = emptyMap())

/** The board as a ranking, apps in registry order. */
internal fun QuoteBoard.ranked(providers: List<FoodProvider> = foodProviders): RankedComparison =
    rank(providers.mapNotNull { p -> quotes[p.id]?.let { p to it } }.toMap())

internal fun cartKey(cart: CartRequest): String =
    listOf(cart.restaurant, cart.address.line, cart.lines.joinToString(";") { "${it.dish}|${it.variant}|${it.quantity}" }).joinToString("#")

/**
 * Prices one cart on every app and publishes results as they arrive. Apps the user hasn't signed in to
 * show "Sign in" without a call. Results land by provider id, so a cancelled run can't leave a row on
 * "Checking": its unfinished rows become failures.
 */
internal class FoodQuotes(
    private val context: Context,
    private val scope: CoroutineScope,
    private val providers: List<FoodProvider> = foodProviders,
) {
    private val board = MutableStateFlow(QuoteBoard())
    val state: StateFlow<QuoteBoard> = board
    private val jobs = mutableListOf<Job>()
    private var request: CartRequest? = null

    /**
     * Starts pricing [cart] and returns its key. The same cart again (a coupon changed its bill) re-prices
     * only the app that already priced it; the other apps' numbers still hold.
     */
    fun start(cart: CartRequest): String {
        val key = cartKey(cart)
        request = cart
        if (key == board.value.key) {
            run(key, cart, providers.filter { it.id == cart.priced?.providerId })
            return key
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        val signedIn = providers.filter { it.connected(context) }
        board.value = QuoteBoard(key, providers.associate { it.id to if (it in signedIn) ProviderQuote.Checking else ProviderQuote.SignIn })
        run(key, cart, signedIn)
        return key
    }

    /** Asks one app again, after a failure or a new sign-in. */
    fun retry(providerId: String) {
        val cart = request ?: return
        val provider = providers.firstOrNull { it.id == providerId && it.connected(context) } ?: return
        val key = board.value.key
        put(key, provider.id, ProviderQuote.Checking)
        run(key, cart, listOf(provider))
    }

    /** Prices the apps the user just signed in to. */
    fun refresh() {
        board.value.quotes.filterValues { it == ProviderQuote.SignIn }.keys.forEach(::retry)
    }

    private fun run(key: String, cart: CartRequest, list: List<FoodProvider>) = scope.launch {
        try {
            quoteAll(list, quote = { it.quoteCart(context, cart) }) { provider, quote -> put(key, provider.id, quote) }
        } finally {
            list.forEach { provider ->
                board.update { b ->
                    if (b.key == key && b.quotes[provider.id] == ProviderQuote.Checking) b.copy(quotes = b.quotes + (provider.id to ProviderQuote.Failed("stopped")))
                    else b
                }
            }
        }
    }.also { job ->
        jobs.removeAll { it.isCompleted }
        jobs += job
    }

    private fun put(key: String, id: String, quote: ProviderQuote) =
        board.update { if (it.key == key) it.copy(quotes = it.quotes + (id to quote)) else it }
}

internal fun dishKey(dish: DishRequest): String =
    listOf(dish.restaurant, dish.line.dish, dish.line.variant, dish.address.pincode ?: dish.address.line).joinToString("|")

/**
 * "S ₹249 · Z ₹259" under a search result: each app's initial and its price for the same dish and size.
 * A missing dish says so; a price still loading shows "…"; a failed lookup is left out. Null with fewer than two apps.
 */
internal fun dishPriceLine(prices: List<Pair<FoodProvider, DishPrice?>>): String? {
    val parts = prices.mapNotNull { (provider, price) ->
        when (price) {
            is DishPrice.Found -> "${provider.initial} ${paiseText(price.paise)}"
            DishPrice.Missing -> "${provider.initial} not listed"
            null -> "${provider.initial} …"
            is DishPrice.Failed -> null
        }
    }
    return parts.takeIf { it.size > 1 }?.joinToString(" · ")
}

/**
 * Prices the top search results on every signed-in app, cached for the session by dish, size and pincode.
 * A missing entry means "still checking"; a cancelled run marks its unfinished entries failed, and the next
 * run asks again.
 */
internal class DishQuotes(
    private val context: Context,
    private val scope: CoroutineScope,
    private val providers: List<FoodProvider> = foodProviders,
) {
    private val prices = MutableStateFlow<Map<String, Map<String, DishPrice>>>(emptyMap())
    val state: StateFlow<Map<String, Map<String, DishPrice>>> = prices
    private var job: Job? = null

    fun start(dishes: List<DishRequest>) {
        job?.cancel()
        val signedIn = providers.filter { it.connected(context) }
        if (signedIn.size < 2) return
        val todo = dishes.flatMap { dish ->
            signedIn.filter { p -> prices.value[dishKey(dish)]?.get(p.id).let { it == null || it is DishPrice.Failed } }.map { dish to it }
        }
        prices.update { all -> all + todo.groupBy { dishKey(it.first) }.mapValues { (key, _) -> all[key].orEmpty().filterValues { it !is DishPrice.Failed } } }
        job = scope.launch {
            try {
                boundedAll(todo, QUOTE_PARALLEL, QUOTE_TIMEOUT_MS, { DishPrice.Failed(it) }, { (dish, provider) -> provider.quoteDish(context, dish) }) { (dish, provider), price ->
                    put(dishKey(dish), provider.id, price)
                }
            } finally {
                todo.forEach { (dish, provider) ->
                    if (prices.value[dishKey(dish)]?.get(provider.id) == null) put(dishKey(dish), provider.id, DishPrice.Failed("stopped"))
                }
            }
        }
    }

    private fun put(key: String, providerId: String, price: DishPrice) =
        prices.update { all -> all + (key to (all[key].orEmpty() + (providerId to price))) }
}

/** How many of the top dish results get a price check on every app. */
internal const val COMPARED_DISHES = 6
internal const val QUOTE_PARALLEL = 3
/** Zomato's cart quote is five MCP calls in a row; 12s cut healthy answers off. */
internal const val QUOTE_TIMEOUT_MS = 20_000L
