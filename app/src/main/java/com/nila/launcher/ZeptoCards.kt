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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Zepto as the same step-by-step sheet as Swiggy food: address, products (Zepto beside Instamart), cart,
// review with the real Cash on Delivery preview, then the placed order. One card is updated in place.

sealed interface GroceryCard {
    /** [item] is null when the address is picked on the way to checkout rather than for a search. */
    data class Addresses(val item: String?, val addresses: List<SavedAddress>) : GroceryCard
    data class Products(val address: SavedAddress, val item: String, val groups: List<ProductProviderGroup>, val cheapest: String?) : GroceryCard
    data class Cart(val address: SavedAddress?, val items: List<CartItem>) : GroceryCard
    /** [preview] is Zepto's own order preview text: items, fees and the final total. */
    data class Review(val address: SavedAddress, val items: List<CartItem>, val preview: String) : GroceryCard
    data class Placed(val address: SavedAddress, val result: String) : GroceryCard
    data class Problem(val message: String) : GroceryCard
}

/** What the Zepto cards can ask NILA to do next. Implemented in MainActivity. */
internal interface GroceryActions {
    fun pickAddress(address: SavedAddress, item: String?)
    fun changeAddress(item: String?)
    fun openCart()
    fun changeLine(item: CartItem, quantity: Int)
    fun review()
    fun place()
    fun back(card: GroceryCard)
    fun lastProducts(): GroceryCard.Products?
    fun lastCart(): GroceryCard.Cart?
}

/** "₹42" or "₹42.50" from paise. */
internal fun paiseText(paise: Long) = if (paise % 100 == 0L) "₹${paise / 100}" else "₹%.2f".format(paise / 100.0)

/** Sum of a Zepto cart in paise. */
internal fun cartTotalPaise(items: List<CartItem>) = items.sumOf { it.priceInPaisa.toLong() * it.quantity }

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

    /** The saved address whose store holds the cart. Zepto is hyperlocal, so it is chosen once per session. */
    var address by mutableStateOf<SavedAddress?>(null)
        private set
    var lastProducts: GroceryCard.Products? = null
        private set
    var lastCart: GroceryCard.Cart? = null
        private set

    private suspend fun <T> zepto(block: (ZeptoMcpClient) -> T): T =
        withContext(Dispatchers.IO) { block(client ?: ZeptoMcpClient(zeptoAuth).also { client = it }) }

    private suspend fun step(block: suspend () -> GroceryCard): NilaResponse =
        NilaResponse.Grocery(runCatching { block() }.getOrElse { GroceryCard.Problem("Zepto: ${it.message ?: "something went wrong"}") })

    /** A search: straight to products when the address is known or there is only one, else ask where to deliver. */
    suspend fun start(item: String): NilaResponse = step {
        address?.let { return@step products(it, item) }
        val saved = zepto { it.listSavedAddresses() }
        when (saved.size) {
            0 -> GroceryCard.Problem("No saved Zepto address yet. Add one in the Zepto app, then ask again.")
            1 -> select(saved.single(), item)
            else -> GroceryCard.Addresses(item, saved)
        }
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
                        runCatching { zepto { it.setCartQuantity(deviceId, product.productVariantId, product.storeProductId, product.name, quantity) } }
                            .onFailure { onProblem("Zepto couldn't update the cart right now.") }
                    }
                }
            },
            if (picks.isEmpty()) "No Zepto results for \"$item\"." else null,
        )
        val instamartGroup = instamart.group(item, instamart.search(parsed.searchQuery, parsed.desiredSize), scope, onProblem)
        val cheapest = listOf(zeptoGroup, instamartGroup)
            .flatMap { group -> group.items.map { group.providerName to it } }
            .minByOrNull { (_, product) -> product.price.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: Double.MAX_VALUE }
            ?.let { (provider, product) -> "Cheapest: $provider · ${product.price}" }
        return GroceryCard.Products(at, item, listOf(zeptoGroup, instamartGroup), cheapest).also { lastProducts = it }
    }

    suspend fun cart(): NilaResponse = step { loadCart() }

    private suspend fun loadCart(): GroceryCard = GroceryCard.Cart(address, zepto { it.viewCartItems() }).also { lastCart = it }

    suspend fun changeLine(line: CartItem, quantity: Int): NilaResponse = step {
        zepto { it.setCartQuantity(deviceId, line.productVariantId, line.storeProductId, line.name, quantity) }
        loadCart()
    }

    /** Zepto's real preview: no order exists yet. Without a chosen address the store is unknown, so ask first. */
    suspend fun review(): NilaResponse = step {
        val at = address ?: return@step GroceryCard.Addresses(null, zepto { it.listSavedAddresses() })
        val items = zepto { it.viewCartItems() }
        if (items.isEmpty()) return@step GroceryCard.Problem("Your Zepto cart is empty. Add something first.")
        GroceryCard.Review(at, items, zepto { it.previewCodOrder(at.id) })
    }

    /** Places a real Cash on Delivery order. Only reached from the Review card's confirm. */
    suspend fun place(): NilaResponse = step {
        val at = address ?: error("no delivery address chosen")
        GroceryCard.Placed(at, zepto { it.placeCodOrder(at.id) }).also { lastCart = null }
    }

    private companion object {
        const val RESULTS = 6
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
        is GroceryCard.Placed -> GroceryPlacedCard(card)
        is GroceryCard.Problem -> NilaCard {
            SectionLabel("Zepto", color = Orange)
            Text(card.message, color = Paper)
        }
    }
}

private fun SavedAddress.asFood() = FoodAddress(id, label.ifBlank { "Saved address" }, addressLine)

@Composable
private fun DeliverTo(address: SavedAddress?, onChange: () -> Unit) {
    Row(Modifier.clickable(onClick = onChange).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            address?.let { "To ${it.label.ifBlank { "saved address" }} · ${it.addressLine}" } ?: "Delivery address: choose at checkout",
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
        StepHeader(1, "Where should Zepto deliver?", ZEPTO_SOURCE)
        Text("Zepto prices and stock depend on the nearest store, so the cart stays with this address.", color = Muted, fontSize = 13.sp)
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
        PrimaryAction("View Zepto cart", onClick = actions::openCart)
        Text("Instamart items go to your Instamart cart in the Swiggy app.", color = Muted, fontSize = 11.sp)
    }
}

// ---- Step 3: cart ------------------------------------------------------------

@Composable
private fun GroceryCartCard(card: GroceryCard.Cart, actions: GroceryActions) {
    val total = cartTotalPaise(card.items)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.lastProducts()?.let { products -> BackLink("Add more") { actions.back(products) } }
        StepHeader(3, "Your Zepto cart", ZEPTO_SOURCE)
        Surface(color = CardInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (card.items.isEmpty()) Text("Your cart is empty. Ask NILA for something to add.", color = Muted, fontSize = 13.sp)
                card.items.forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FoodImage(line.imageUrl, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                        Text(line.name, color = Paper, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        QuantityStepper(
                            quantity = line.quantity,
                            onLess = { actions.changeLine(line, line.quantity - 1) },
                            onMore = { actions.changeLine(line, line.quantity + 1) },
                        )
                        Text(paiseText(line.priceInPaisa.toLong() * line.quantity), color = Paper, fontSize = 14.sp, modifier = Modifier.width(64.dp).padding(start = 8.dp))
                    }
                }
                HorizontalDivider(color = Panel)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Items total", color = Paper, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(paiseText(total), color = Paper, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
                Text("Delivery fee and taxes come from Zepto on the next step.", color = Muted, fontSize = 11.sp)
                DeliverTo(card.address) { actions.changeAddress(null) }
            }
        }
        PrimaryAction("Review order · ${paiseText(total)}", onClick = actions::review, enabled = card.items.isNotEmpty())
    }
}

// ---- Step 4: review and the Commit gate --------------------------------------

@Composable
private fun GroceryReviewCard(card: GroceryCard.Review, actions: GroceryActions) {
    var armed by remember { mutableStateOf(false) }
    var placed by remember { mutableStateOf(false) }
    // The second tap must come soon after the first; otherwise the gate closes again.
    LaunchedEffect(armed) {
        if (armed) {
            delay(5_000)
            armed = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.lastCart()?.let { cart -> BackLink("Edit cart") { actions.back(cart) } }
        StepHeader(4, "Review and order", ZEPTO_SOURCE)
        NilaCard {
            SectionLabel("Zepto's bill")
            Text(card.preview, color = Paper, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Surface(color = CommitInk, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("REAL ORDER · CASH ON DELIVERY", color = Orange, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                card.items.forEach { Text("${it.quantity} × ${it.name}", color = Paper, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                Text("To ${card.address.label.ifBlank { "saved address" }} · ${card.address.addressLine}", color = Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        if (!armed) armed = true else {
                            placed = true
                            actions.place()
                        }
                    },
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
                Text("This places a real Zepto order. You pay the rider in cash when it arrives.", color = Muted, fontSize = 11.sp)
            }
        }
    }
}

// ---- Order result ------------------------------------------------------------

@Composable
private fun GroceryPlacedCard(card: GroceryCard.Placed) {
    Surface(color = Lime, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ordered on Zepto.\nPay the rider on delivery.", color = Ink, fontSize = 28.sp, fontFamily = FontFamily.Serif, lineHeight = 32.sp)
            Text(card.result, color = Ink.copy(alpha = 0.75f), fontSize = 13.sp)
            Text("To ${card.address.label.ifBlank { "saved address" }}", color = Ink.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}
