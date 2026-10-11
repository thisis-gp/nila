package com.nila.launcher

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Zepto as the same step-by-step sheet as Swiggy food: address, products (Zepto beside Instamart), cart,
// review with the real Cash on Delivery preview, then the placed order. One card is updated in place.

sealed interface GroceryCard {
    /** [item] is null when the address is picked on the way to checkout. [note] says why NILA is asking. */
    data class Addresses(val item: String?, val addresses: List<SavedAddress>, val note: String? = null) : GroceryCard
    data class Products(val address: SavedAddress, val item: String, val groups: List<ProductProviderGroup>, val cheapest: String?) : GroceryCard
    /**
     * Both carts: Zepto's and, when Swiggy is connected, Instamart's. Each is ordered on its own. [sameAddress]
     * is true when both deliver to the address chosen in NILA.
     */
    data class Cart(val address: SavedAddress?, val items: List<CartItem>, val instamart: InstamartCart?, val sameAddress: Boolean) : GroceryCard
    /** [preview] is Zepto's own order preview text. [note] says the cart changed since the last review. */
    data class Review(val address: SavedAddress, val items: List<CartItem>, val preview: String, val note: String? = null) : GroceryCard
    /** Instamart's live bill and the cash method it offers, before checkout. */
    data class InstamartReview(val cart: InstamartCart, val method: String, val addressId: String, val note: String? = null) : GroceryCard
    data class Placed(val provider: String, val addressLabel: String, val result: String) : GroceryCard
    data class Problem(val message: String, val provider: String = "Zepto") : GroceryCard
}

/** What the Zepto cards can ask NILA to do next. Implemented in MainActivity. */
internal interface GroceryActions {
    fun pickAddress(address: SavedAddress, item: String?)
    fun changeAddress(item: String?)
    fun openCart()
    fun changeLine(item: CartItem, quantity: Int)
    fun changeInstamartLine(item: InstamartItem, quantity: Int)
    fun review()
    fun reviewInstamart()
    /** False when another step was running and nothing was sent. */
    fun place(review: GroceryCard.Review): Boolean
    fun placeInstamart(review: GroceryCard.InstamartReview): Boolean
    fun back(card: GroceryCard)
    fun lastProducts(): GroceryCard.Products?
    fun lastCart(): GroceryCard.Cart?
}

/** "₹42" or "₹42.50" from paise. */
internal fun paiseText(paise: Long) = if (paise % 100 == 0L) "₹${paise / 100}" else "₹%.2f".format(paise / 100.0)

/** Sum of a Zepto cart in paise. */
internal fun cartTotalPaise(items: List<CartItem>) = items.sumOf { it.priceInPaisa.toLong() * it.quantity }

/** The best value across providers, normalized per litre or kilo when pack sizes differ. */
internal fun cheapestLine(item: String, groups: List<ProductProviderGroup>): String {
    val offers = groups.flatMap { group -> group.items.map { group.providerName to it } }
    fun rupees(product: PickableProduct) = product.price.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
    val perUnit = offers.mapNotNull { (provider, product) ->
        val price = rupees(product) ?: return@mapNotNull null
        val (unit, amount) = normalizedSize(product.detail) ?: return@mapNotNull null
        Triple(provider, product, price / amount * 1_000 to unit)
    }
    perUnit.minByOrNull { it.third.first }?.let { (provider, product, value) ->
        val unit = if (value.second == "ml") "litre" else "kg"
        val perUnitPrice = "%.0f".format(value.first)
        return "Best value for \"$item\": $provider · ₹$perUnitPrice per $unit (${product.detail.substringBefore(" · ")} at ${product.price})"
    }
    val cheapest = offers.minByOrNull { (_, product) -> rupees(product) ?: Double.MAX_VALUE }
        ?: return "Options for \"$item\" — tap + to add:"
    return "Cheapest for \"$item\": ${cheapest.first} · ${cheapest.second.price}"
}

/** The same Zepto order: every line and quantity unchanged. */
internal fun sameZeptoCart(a: List<CartItem>, b: List<CartItem>) =
    a.associate { it.productVariantId to it.quantity } == b.associate { it.productVariantId to it.quantity }

private val amountDuePattern = Regex("""Amount To Pay:?\**\s*(?:₹|Rs\.?|INR)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

/**
 * The real amount due, including delivery fee, out of Zepto's own free-form order-preview text — the only
 * place that fee appears; the cart's own item total alone understates what's actually owed. The rest of that
 * text is written for an LLM agent (raw product ids, markdown, "call create_order again..."), never for a
 * person, so nothing else from it should ever reach the screen. The last match wins: Zepto's confirmation
 * step repeats the preview with an updated total below the first one. Null if Zepto's wording changes —
 * callers must treat that as a sign something is wrong with the order, not just a missing total.
 */
internal fun zeptoAmountDue(preview: String): String? =
    amountDuePattern.findAll(preview).lastOrNull()?.groupValues?.get(1)?.let { "₹$it" }

// An Indian pincode never starts with 0; the last one in a line is the pincode, not a flat or plot number.
private val pincode = Regex("""\b[1-9]\d{5}\b""")
private val addressNumber = Regex("""\b\d+[a-z]?\b""", RegexOption.IGNORE_CASE)
private fun pincodeOf(line: String) = pincode.findAll(line).lastOrNull()?.value
private fun addressNumberOf(line: String) = addressNumber.findAll(line).map { it.value.lowercase() }
    .firstOrNull { it != pincodeOf(line) }
private fun words(line: String) = line.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 }.toSet()

/**
 * The Zepto saved address that is the address chosen in NILA: both have the same pincode and share at least
 * half of NILA's words. Without a pincode on both sides, nearly every word must match (4 in 5), so a
 * neighbouring address is never picked. Null when none is, so NILA asks instead of delivering somewhere else.
 */
internal fun matchAddress(nila: FoodAddress, zepto: List<SavedAddress>): SavedAddress? {
    val pin = pincodeOf(nila.line)
    val nilaWords = words(nila.line)
    if (nilaWords.isEmpty()) return null
    return zepto
        .map { saved -> saved to (words(saved.addressLine) intersect nilaWords).size }
        .filter { (saved, shared) ->
            val savedPin = pincodeOf(saved.addressLine)
            val numbersMatch = addressNumberOf(nila.line)?.let { number ->
                addressNumberOf(saved.addressLine)?.let { it == number } ?: true
            } ?: true
            numbersMatch && if (pin != null && savedPin != null) pin == savedPin && shared * 2 >= nilaWords.size else shared * 5 >= nilaWords.size * 4
        }
        .maxByOrNull { (_, shared) -> shared }
        ?.first
}

/** The two-tap Commit gate: the first tap arms it, the second places, once. */
internal data class GateState(val armed: Boolean = false, val placed: Boolean = false)

/** [place] returns false when nothing was sent (another step was running): the gate closes, ready again. */
internal fun GateState.tap(place: () -> Boolean): GateState = when {
    placed -> this
    !armed -> copy(armed = true)
    place() -> GateState(placed = true)
    else -> GateState()
}

/**
 * Zepto steps. Every function returns the card to show; failures become a [GroceryCard.Problem] instead of
 * an exception. Changing quantity only edits the real Zepto cart; nothing is ordered before [place].
 */
internal class GroceryFlow(
    context: Context,
    private val zeptoAuth: ZeptoAuth,
    private val instamart: InstamartShop,
    private val scope: CoroutineScope,
    private val onProblem: (String) -> Unit,
) {
    private var client: ZeptoMcpClient? = null
    private val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "nila-device"
    // Quantities are absolute, so updates run one at a time, in the order they were tapped.
    private val zeptoCartLock = Mutex()

    /** The saved address whose store holds the cart. Zepto is hyperlocal, so it is chosen once per session. */
    var address by mutableStateOf<SavedAddress?>(null)
        private set
    /** The address chosen in NILA (Swiggy's), which Instamart delivers to. Null without Swiggy. */
    private var nilaAddress: FoodAddress? = null
    var lastProducts: GroceryCard.Products? = null
        private set
    var lastCart: GroceryCard.Cart? = null
        private set

    private suspend fun <T> zepto(block: (ZeptoMcpClient) -> T): T =
        withContext(Dispatchers.IO) { block(client ?: ZeptoMcpClient(zeptoAuth).also { client = it }) }

    private suspend fun step(provider: String = "Zepto", block: suspend () -> GroceryCard): NilaResponse =
        NilaResponse.Grocery(
            runCatching { block() }.getOrElse {
                if (it is CancellationException) throw it
                GroceryCard.Problem(it.message ?: "something went wrong", provider)
            },
        )

    private suspend fun setZeptoQuantity(variantId: String, storeProductId: String, name: String, quantity: Int) =
        zeptoCartLock.withLock { zepto { it.setCartQuantity(deviceId, variantId, storeProductId, name, quantity) } }

    /**
     * A search. Zepto delivers to the address chosen in NILA: NILA finds it among Zepto's saved addresses, or
     * saves it in Zepto, so both carts go to one place. Only when Zepto won't take it does NILA ask. Without
     * Swiggy, a single saved Zepto address is used as is. The address is chosen again when NILA's changes.
     */
    suspend fun start(item: String): NilaResponse = step {
        val nila = instamart.nilaAddress()
        address?.takeIf { nila == nilaAddress }?.let { return@step products(it, item) }
        nilaAddress = nila
        val saved = zepto { it.listSavedAddresses() }
        val match = nila?.let { matchAddress(it, saved) ?: addToZepto(it) }
        when {
            match != null -> select(match, item)
            nila == null && saved.size == 1 -> select(saved.single(), item)
            saved.isEmpty() -> GroceryCard.Problem(
                nila?.let { "Zepto couldn't save your NILA address (${it.label}). Add it in the Zepto app, then ask again." }
                    ?: "No saved Zepto address yet. Add one in the Zepto app, then ask again.",
            )
            else -> GroceryCard.Addresses(
                item,
                saved,
                nila?.let { "Zepto couldn't save your NILA address (${it.label}). Pick a Zepto address, or add it in the Zepto app so both carts go to one place." },
            )
        }
    }

    /** Saves NILA's address in Zepto with the fields Zepto asks for, then finds it. Null when Zepto won't take it. */
    private suspend fun addToZepto(nila: FoodAddress): SavedAddress? = try {
        instamart.nilaAddressJson()?.let { source -> zepto { it.addressArgumentsFrom(source) } }?.let { args ->
            zepto { it.addSavedAddress(args) }
            matchAddress(nila, zepto { it.listSavedAddresses() })
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun chooseAddress(item: String?): NilaResponse = step { GroceryCard.Addresses(item, zepto { it.listSavedAddresses() }) }

    suspend fun deliverTo(chosen: SavedAddress, item: String?): NilaResponse = step { select(chosen, item) }

    private suspend fun select(chosen: SavedAddress, item: String?): GroceryCard {
        zepto { it.selectSavedAddress(chosen.id) }
        address = chosen
        return if (item != null) products(chosen, item) else loadCart()
    }

    private suspend fun products(at: SavedAddress, item: String): GroceryCard {
        val parsed = parseOrderItem(item)
        val found = zepto { it.searchProducts(parsed.searchQuery) }
        // Steppers start from what is already in the Zepto cart, so + and − never fight the real cart.
        val inCart = zepto { it.viewCartItems() }.associate { it.productVariantId to it.quantity }
        val wanted = parsed.desiredSize?.let(::normalizedSize)
        val picks = found.sortedWith(
            compareByDescending<ZeptoProduct> { product ->
                wanted != null && normalizedSize(product.packSize)?.let { (unit, value) -> unit == wanted.first && kotlin.math.abs(value - wanted.second) < 1.0 } == true
            }.thenBy { it.priceInPaisa },
        ).take(RESULTS)
        val zeptoGroup = ProductProviderGroup(
            "Zepto",
            picks.minOfOrNull { it.priceInPaisa }?.let { "from ${paiseText(it.toLong())}" } ?: "no results",
            picks.map { product ->
                PickableProduct(product.name, product.packSize, paiseText(product.priceInPaisa.toLong()), product.imageUrl, inCart[product.productVariantId] ?: 0) { quantity ->
                    scope.launch {
                        try {
                            setZeptoQuantity(product.productVariantId, product.storeProductId, product.name, quantity)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onProblem("Zepto couldn't update the cart right now.")
                        }
                    }
                }
            },
            if (picks.isEmpty()) "No Zepto results for \"$item\"." else null,
        )
        instamart.cart() // syncs Instamart's steppers with its real cart
        val instamartGroup = instamart.group(item, instamart.search(parsed.searchQuery, parsed.desiredSize), scope, onProblem)
        val groups = listOf(zeptoGroup, instamartGroup)
        return GroceryCard.Products(at, item, groups, cheapestLine(item, groups)).also { lastProducts = it }
    }

    suspend fun cart(): NilaResponse = step { loadCart() }

    private suspend fun loadCart(): GroceryCard {
        val nila = nilaAddress ?: instamart.nilaAddress().also { nilaAddress = it }
        val at = address
        val same = nila != null && at != null && matchAddress(nila, listOf(at)) != null
        return GroceryCard.Cart(at, zepto { it.viewCartItems() }, instamart.cart(), same).also { lastCart = it }
    }

    /**
     * Back to an earlier card, read again from the live carts: a replayed card's steppers would hold old
     * quantities, and the next + would overwrite the real cart with a wrong absolute number.
     */
    suspend fun reload(card: GroceryCard): NilaResponse = when (card) {
        is GroceryCard.Products -> step { products(card.address, card.item) }
        is GroceryCard.Cart -> cart()
        else -> NilaResponse.Grocery(card)
    }

    suspend fun changeLine(line: CartItem, quantity: Int): NilaResponse = step {
        setZeptoQuantity(line.productVariantId, line.storeProductId, line.name, quantity)
        loadCart()
    }

    suspend fun changeInstamartLine(item: InstamartItem, quantity: Int): NilaResponse = step(INSTAMART) {
        if (!instamart.setLine(item, quantity)) onProblem("Instamart couldn't update the cart right now.")
        loadCart()
    }

    /** Instamart's live bill, read again right before ordering because the cart may have changed in the Swiggy app. */
    suspend fun reviewInstamart(note: String? = null): NilaResponse = step(INSTAMART) { instamartReview(note) }

    private suspend fun instamartReview(note: String?): GroceryCard {
        val cart = instamart.cart() ?: return GroceryCard.Problem("Connect Swiggy in Settings to order from Instamart.", INSTAMART)
        val addressId = cart.addressId
        val method = cart.cashMethod
        return when {
            cart.lines.isEmpty() -> GroceryCard.Problem("Your Instamart cart is empty. Add something first.", INSTAMART)
            addressId == null -> GroceryCard.Problem("Instamart has no delivery address. Choose one in the Swiggy app.", INSTAMART)
            method == null -> GroceryCard.Problem("Instamart isn't offering cash on delivery for this cart. Finish it in the Swiggy app.", INSTAMART)
            else -> GroceryCard.InstamartReview(cart, method, addressId, note)
        }
    }

    /**
     * Places a real Instamart Cash on Delivery order, but only the order the user confirmed: checkout sends
     * Swiggy's current cart, so it is read again first, and any change goes back to review.
     */
    suspend fun placeInstamart(review: GroceryCard.InstamartReview): NilaResponse = step(INSTAMART) {
        val now = instamart.cart()
        if (now == null || !now.sameOrderAs(review.cart) || now.cashMethod != review.method) {
            return@step instamartReview("Your Instamart cart changed since you reviewed it. Check it and confirm again.")
        }
        GroceryCard.Placed("Swiggy Instamart", review.cart.addressLabel ?: "your Swiggy address", instamart.checkout(review.addressId, review.method))
            .also { lastCart = null }
    }

    /** Zepto's real preview: no order exists yet. Without a chosen address the store is unknown, so ask first. */
    suspend fun review(): NilaResponse = step { zeptoReview(null) }

    private suspend fun zeptoReview(note: String?): GroceryCard {
        val at = address ?: return GroceryCard.Addresses(null, zepto { it.listSavedAddresses() })
        val items = zeptoCartLock.withLock { zepto { it.viewCartItems() } }
        if (items.isEmpty()) return GroceryCard.Problem("Your Zepto cart is empty. Add something first.")
        return GroceryCard.Review(at, items, zepto { it.previewCodOrder(at.id) }, note)
    }

    /** Places a real Cash on Delivery order, after checking the Zepto cart is still the one reviewed. */
    suspend fun place(review: GroceryCard.Review): NilaResponse = step {
        val at = address ?: error("no delivery address chosen")
        if (at.id != review.address.id || !sameZeptoCart(zeptoCartLock.withLock { zepto { it.viewCartItems() } }, review.items)) {
            return@step zeptoReview("Your Zepto cart changed since you reviewed it. Check it and confirm again.")
        }
        GroceryCard.Placed("Zepto", at.label.ifBlank { "saved address" }, zepto { it.placeCodOrder(at.id) }).also { lastCart = null }
    }

    private companion object {
        const val RESULTS = 6
        const val INSTAMART = "Swiggy Instamart"
    }
}

private const val ZEPTO_SOURCE = "Zepto · live"

@Composable
internal fun GroceryCardView(card: GroceryCard, actions: GroceryActions) {
    when (card) {
        is GroceryCard.Addresses -> GroceryAddressesCard(card, actions)
        is GroceryCard.Products -> GroceryProductsCard(card, actions)
        is GroceryCard.Cart -> GroceryCartCard(card, actions)
        is GroceryCard.Review -> GroceryReviewCard(card, actions)
        is GroceryCard.InstamartReview -> InstamartReviewCard(card, actions)
        is GroceryCard.Placed -> GroceryPlacedCard(card)
        is GroceryCard.Problem -> NilaCard {
            SectionLabel(card.provider, color = Orange)
            Text(card.message, color = Paper)
        }
    }
}

private fun SavedAddress.asFood() = FoodAddress(id, label.ifBlank { "Saved address" }, addressLine)

@Composable
private fun DeliverTo(address: SavedAddress?, prefix: String = "To", onChange: () -> Unit) {
    Row(Modifier.clickable(onClick = onChange).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            address?.let { "$prefix ${it.label.ifBlank { "saved address" }} · ${it.addressLine}" } ?: "Delivery address: choose at checkout",
            color = Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text("  Change", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---- Step 1: address ---------------------------------------------------------

@Composable
private fun GroceryAddressesCard(card: GroceryCard.Addresses, actions: GroceryActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(1, "Where should I deliver?", ZEPTO_SOURCE)
        Text(card.note ?: "Zepto prices and stock depend on the nearest store, so the cart stays with this address.", color = if (card.note != null) Orange else Muted, fontSize = 13.sp)
        if (card.addresses.isEmpty()) {
            NilaCard { Text("No saved Zepto address yet. Add one in the Zepto app, then ask again.", color = Paper) }
        } else Surface(color = CardInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column {
                card.addresses.forEachIndexed { index, address ->
                    if (index > 0) HorizontalDivider(color = Panel, modifier = Modifier.padding(start = 62.dp))
                    AddressRow(address.asFood()) { actions.pickAddress(address, card.item) }
                }
            }
        }
    }
}

// ---- Step 2: products, Zepto beside Instamart --------------------------------

@Composable
private fun GroceryProductsCard(card: GroceryCard.Products, actions: GroceryActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StepHeader(2, card.item.replaceFirstChar { it.uppercase() }, ZEPTO_SOURCE)
        card.cheapest?.let { Text(it, color = Lime, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        DeliverTo(card.address) { actions.changeAddress(card.item) }
        card.groups.forEach { ProductProviderGroupCard(it) }
        PrimaryAction("View carts", onClick = actions::openCart)
    }
}

// ---- Step 3: both carts ------------------------------------------------------

@Composable
private fun GroceryCartCard(card: GroceryCard.Cart, actions: GroceryActions) {
    val total = cartTotalPaise(card.items)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.lastProducts()?.let { products -> BackLink("Add more") { actions.back(products) } }
        StepHeader(3, "Your carts", "Zepto · Instamart · live")
        Text("Each app delivers its own cart, so each is ordered on its own.", color = Muted, fontSize = 13.sp)
        when {
            card.sameAddress -> DeliverTo(card.address, prefix = "Both deliver to") { actions.changeAddress(null) }
            card.instamart != null && card.instamart.lines.isNotEmpty() && card.address != null ->
                Text("Zepto and Instamart deliver to different addresses. Check each below.", color = Orange, fontSize = 13.sp)
        }

        SectionLabel("Zepto · ${if (card.items.isEmpty()) "empty" else paiseText(total)}", Modifier.padding(top = 6.dp), color = Paper)
        CartBox {
            if (card.items.isEmpty()) Text("Nothing in your Zepto cart.", color = Muted, fontSize = 13.sp)
            card.items.forEach { line ->
                CartLine(
                    line.imageUrl, line.name, line.quantity, paiseText(line.priceInPaisa.toLong() * line.quantity),
                    onLess = { actions.changeLine(line, line.quantity - 1) },
                    onMore = { actions.changeLine(line, line.quantity + 1) },
                )
            }
            if (card.items.isNotEmpty()) {
                HorizontalDivider(color = Panel)
                TotalRow("Items total", paiseText(total))
                Text("Delivery fee and taxes come from Zepto on the next step.", color = Muted, fontSize = 11.sp)
            }
            if (!card.sameAddress) DeliverTo(card.address) { actions.changeAddress(null) }
            if (card.items.isNotEmpty()) PrimaryAction("Review Zepto order · ${paiseText(total)}", onClick = actions::review)
        }

        val instamart = card.instamart
        val instamartSummary = when {
            instamart == null -> "not connected"
            instamart.lines.isEmpty() -> "empty"
            else -> instamart.toPay.orEmpty()
        }
        SectionLabel("Swiggy Instamart · $instamartSummary", Modifier.padding(top = 6.dp), color = Paper)
        CartBox {
            when {
                instamart == null -> Text("Connect Swiggy in Settings to see your Instamart cart.", color = Muted, fontSize = 13.sp)
                instamart.lines.isEmpty() -> Text("Nothing in your Instamart cart.", color = Muted, fontSize = 13.sp)
                else -> {
                    instamart.lines.forEach { (item, quantity) ->
                        CartLine(
                            item.imageUrl, item.name, quantity, paiseText(item.pricePaise * quantity),
                            onLess = { actions.changeInstamartLine(item, quantity - 1) },
                            onMore = { actions.changeInstamartLine(item, quantity + 1) },
                        )
                    }
                    HorizontalDivider(color = Panel)
                    instamart.bill.forEach { (label, value) -> BillRow(label, value) }
                    instamart.toPay?.let { TotalRow("To pay", it) }
                    if (!card.sameAddress) instamart.addressLabel?.let { Text("To $it", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    instamart.warning?.let { Text(it, color = Orange, fontSize = 12.sp) }
                    PrimaryAction("Review Instamart order${instamart.toPay?.let { " · $it" }.orEmpty()}", onClick = actions::reviewInstamart)
                }
            }
        }
    }
}

@Composable
private fun CartBox(content: @Composable () -> Unit) {
    Surface(color = CardInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun CartLine(imageUrl: String?, name: String, quantity: Int, price: String, onLess: () -> Unit, onMore: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FoodImage(imageUrl, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
        Text(name, color = Paper, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
        QuantityStepper(quantity = quantity, onLess = onLess, onMore = onMore)
        Text(price, color = Paper, fontSize = 14.sp, modifier = Modifier.width(64.dp).padding(start = 8.dp))
    }
}

@Composable
private fun BillRow(label: String, value: String) {
    Row {
        Text(label, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(value, color = Paper, fontWeight = FontWeight.Black, fontSize = 20.sp)
    }
}

// ---- Step 4: review and the Commit gate --------------------------------------

/**
 * The Commit gate both apps share: a real order, two taps within five seconds, then it locks.
 * [blocked] replaces the button with that message and refuses taps — used when NILA can't show
 * the real amount due, so a real-money order is never confirmed blind.
 */
@Composable
private fun CommitGate(key: Any, heading: String, lines: List<String>, address: String, warning: String, note: String?, blocked: String? = null, onPlace: () -> Boolean) {
    // Keyed on the review: a fresh review after a change starts with a fresh gate.
    var gate by remember(key) { mutableStateOf(GateState()) }
    LaunchedEffect(gate.armed) {
        if (gate.armed) {
            delay(5_000)
            if (!gate.placed) gate = GateState()
        }
    }
    val armed = gate.armed
    val placed = gate.placed
    Surface(color = CommitInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            note?.let { Text(it, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Text(heading, color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            lines.forEach { Text(it, color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Text(address, color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            if (blocked != null) {
                Text(blocked, color = Orange, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            } else {
                Button(
                    onClick = { gate = gate.tap(onPlace) },
                    enabled = !placed,
                    colors = ButtonDefaults.buttonColors(containerColor = if (armed) Orange else Lime, contentColor = Ink),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        when {
                            placed -> "Placing order…"
                            armed -> "Tap again to place the order"
                            else -> "Place order · pay on delivery"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(warning, color = Muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun GroceryReviewCard(card: GroceryCard.Review, actions: GroceryActions) {
    val amountDue = zeptoAmountDue(card.preview)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.lastCart()?.let { cart -> BackLink("Edit carts") { actions.back(cart) } }
        StepHeader(4, "Review your Zepto order", ZEPTO_SOURCE)
        CommitGate(
            key = card,
            note = card.note,
            heading = "REAL ZEPTO ORDER · CASH ON DELIVERY",
            lines = card.items.map { "${it.quantity} × ${it.name}" } +
                (amountDue?.let { "Total to pay: $it" } ?: "Total unavailable—review in Zepto before ordering"),
            address = "To ${card.address.label.ifBlank { "saved address" }} · ${card.address.addressLine}",
            warning = "This places a real Zepto order. You pay the rider in cash when it arrives.",
            // Zepto's preview normally always has a total; its absence usually means the preview is
            // carrying an unavailable-item or minimum-order warning instead, so block rather than guess.
            blocked = if (amountDue == null) "Zepto says there's a problem with this order. Open the Zepto app to check, then come back." else null,
            onPlace = { actions.place(card) },
        )
    }
}

@Composable
private fun InstamartReviewCard(card: GroceryCard.InstamartReview, actions: GroceryActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.lastCart()?.let { cart -> BackLink("Edit carts") { actions.back(cart) } }
        StepHeader(4, "Review your Instamart order", "Swiggy Instamart · live")
        CartBox {
            SectionLabel("Instamart's bill")
            card.cart.bill.forEach { (label, value) -> BillRow(label, value) }
            card.cart.toPay?.let { TotalRow("To pay", it) }
            card.cart.warning?.let { Text(it, color = Orange, fontSize = 12.sp) }
        }
        CommitGate(
            key = card,
            note = card.note,
            heading = "REAL INSTAMART ORDER · ${card.method.uppercase()}",
            lines = card.cart.lines.map { (item, quantity) -> "$quantity × ${item.name}" },
            address = "To ${card.cart.addressLabel ?: "your Swiggy address"}",
            warning = "This places a real Swiggy Instamart order. It can't be cancelled from NILA.",
            onPlace = { actions.placeInstamart(card) },
        )
    }
}

// ---- Order result ------------------------------------------------------------

@Composable
private fun GroceryPlacedCard(card: GroceryCard.Placed) {
    Surface(color = Lime, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ordered on ${card.provider}.\nPay the rider on delivery.", color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Serif, lineHeight = 32.sp)
            Text(card.result, color = Ink.copy(alpha = 0.75f), fontSize = 13.sp)
            Text("To ${card.addressLabel}", color = Ink.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}
