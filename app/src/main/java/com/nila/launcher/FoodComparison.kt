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
) = coroutineScope {
    val gate = Semaphore(permits)
    providers.forEach { provider ->
        launch { onResult(provider, gate.withPermit { quoteOne(provider, timeoutMillis, quote) }) }
    }
}

private suspend fun quoteOne(provider: FoodProvider, timeoutMillis: Long, quote: suspend (FoodProvider) -> ProviderQuote): ProviderQuote =
    try {
        withTimeoutOrNull(timeoutMillis) { quote(provider) } ?: ProviderQuote.Failed("took too long")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ProviderQuote.Failed(e.message ?: "something went wrong")
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
    private var job: Job? = null
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
        job?.cancel()
        val signedIn = providers.filter { it.connected(context) }
        board.value = QuoteBoard(key, providers.associate { it.id to if (it in signedIn) ProviderQuote.Checking else ProviderQuote.SignIn })
        job = run(key, cart, signedIn)
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

    private fun run(key: String, cart: CartRequest, list: List<FoodProvider>): Job = scope.launch {
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
    }

    private fun put(key: String, id: String, quote: ProviderQuote) =
        board.update { if (it.key == key) it.copy(quotes = it.quotes + (id to quote)) else it }
}

internal const val QUOTE_PARALLEL = 3
internal const val QUOTE_TIMEOUT_MS = 12_000L
