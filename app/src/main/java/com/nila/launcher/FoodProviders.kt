package com.nila.launcher

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/** Where the order goes, as every app needs it: NILA's chosen address and its pincode. */
data class DeliveryAddress(val label: String, val line: String, val pincode: String?)

/** One dish in the cart. [variant] is the size the user picked ("Half"), when the dish has one. */
data class CartLine(val dish: String, val variant: String?, val quantity: Int)

/** The cart the ordering app already priced, so that app is never asked twice for the same numbers. */
data class PricedCart(val providerId: String, val cart: FoodCartView, val eta: String, val coupon: String?)

/** The same order, described once, for every delivery app to price. */
data class CartRequest(
    val restaurant: String,
    val lines: List<CartLine>,
    val address: DeliveryAddress,
    val priced: PricedCart? = null,
)

/** One app's bill in paise. [platformFee] also holds packing and any charge NILA can't name. */
data class Bill(
    val itemTotal: Long,
    val delivery: Long,
    val platformFee: Long,
    val taxes: Long,
    val discount: Long,
    val total: Long,
)

/** What one app said about the cart: a priced bill, or why there is none. */
sealed interface ProviderQuote {
    data class Priced(val bill: Bill, val etaMinutes: Int?, val coupon: String?) : ProviderQuote
    data object Checking : ProviderQuote
    data object SignIn : ProviderQuote
    data class NotAvailable(val reason: String) : ProviderQuote
    /** The app doesn't have NILA's address and can't be given it from here; [fix] is what the user can do. */
    data class NoAddress(val reason: String, val fix: AddressFix?) : ProviderQuote
    data class Failed(val reason: String) : ProviderQuote
}

/** One dish at one restaurant, for the price line under a search result. [known] is the price the results already show. */
data class DishRequest(val restaurant: String, val line: CartLine, val address: DeliveryAddress, val known: Pair<String, Long>? = null)

/** One app's price for a single dish, in paise, or why there is none. */
sealed interface DishPrice {
    data class Found(val paise: Long) : DishPrice
    data object Missing : DishPrice
    /** The app couldn't price it here at all: the shop isn't on it. */
    data class Gap(val reason: String) : DishPrice
    /** The app doesn't have NILA's address; the comparison says so once, so the dish line leaves the app out. */
    data object NoAddress : DishPrice
    data class Failed(val reason: String) : DishPrice
}

/** A delivery app NILA can price a cart on. Adding an app is one class and one line in [foodProviders]. */
internal interface FoodProvider {
    val id: String
    val name: String
    val initial: Char
    val tint: Color
    /** True only when NILA can place the order itself; otherwise the app opens at [appPackage]. */
    val canOrder: Boolean
    val appPackage: String?
    val auth: McpAuth

    fun connected(context: Context): Boolean = auth.currentSession(context) != null

    suspend fun quoteCart(context: Context, cart: CartRequest): ProviderQuote

    suspend fun quoteDish(context: Context, dish: DishRequest): DishPrice

    /** Makes sure the app has NILA's address: finds it among the saved ones, or saves it when the app allows. */
    suspend fun ensureAddress(context: Context, address: NilaAddress): AddressResult

    /** The addresses saved in this app, for NILA's address picker. */
    suspend fun savedAddresses(context: Context): List<ProviderAddress> = emptyList()
}

/** The comparison row for an app without NILA's address; null when the app has it and pricing can go on. */
internal fun addressQuote(result: AddressResult): ProviderQuote? = when (result) {
    is AddressResult.Ready, is AddressResult.Added -> null
    is AddressResult.CantAdd -> ProviderQuote.NoAddress(result.reason, result.fix)
    is AddressResult.Failed -> ProviderQuote.Failed(result.reason)
}

/** The same for a dish line: an app without the address is left out of it. */
internal fun addressDishPrice(result: AddressResult): DishPrice? = when (result) {
    is AddressResult.Ready, is AddressResult.Added -> null
    is AddressResult.CantAdd -> DishPrice.NoAddress
    is AddressResult.Failed -> DishPrice.Failed(result.reason)
}

internal object SwiggyProvider : FoodProvider {
    override val id = "swiggy"
    override val name = "Swiggy"
    override val initial = 'S'
    override val tint = Color(0xFFFC8019)
    override val canOrder = true
    override val appPackage = "in.swiggy.android"
    override val auth: McpAuth get() = SwiggyAuth

    private val lock = Any()
    private var client: McpClient? = null
    private var clientToken: String? = null

    /** One Swiggy food client per access token, shared with the order flow. */
    fun client(token: String): McpClient = synchronized(lock) {
        client?.takeIf { clientToken == token } ?: McpClient(SWIGGY_FOOD_ENDPOINT, token).also {
            client = it
            clientToken = token
        }
    }

    private suspend fun call(context: Context, tool: String, args: JSONObject): JSONObject? = runInterruptible(Dispatchers.IO) {
        val session = SwiggyAuth.currentSession(context) ?: return@runInterruptible null
        client(session.accessToken).callTool(tool, args)
    }

    override suspend fun ensureAddress(context: Context, address: NilaAddress): AddressResult =
        ensureSwiggyAddress(address, NilaAddressBook(context).contact) { tool, args -> call(context, tool, args) }

    override suspend fun savedAddresses(context: Context): List<ProviderAddress> =
        call(context, "get_addresses", JSONObject())?.let(::parseProviderAddresses).orEmpty()

    /** Swiggy's numbers come from the live cart FoodFlow already holds; nothing is fetched again. */
    override suspend fun quoteCart(context: Context, cart: CartRequest): ProviderQuote =
        cart.priced?.takeIf { it.providerId == id }?.let { swiggyQuote(it.cart, it.eta, it.coupon) }
            ?: ProviderQuote.Failed("no Swiggy cart to price")

    /** The search results came from Swiggy, so its dish price is already known. */
    override suspend fun quoteDish(context: Context, dish: DishRequest): DishPrice =
        dish.known?.takeIf { it.first == id }?.let { DishPrice.Found(it.second) } ?: DishPrice.Missing
}

/** Read-only: Zomato's MCP prices a cart but NILA never orders there. */
internal object ZomatoProvider : FoodProvider {
    override val id = "zomato"
    override val name = "Zomato"
    override val initial = 'Z'
    override val tint = Color(0xFFE23744)
    override val canOrder = false
    override val appPackage = ZOMATO_PACKAGE
    override val auth: McpAuth get() = ZomatoAuth

    private val lock = Any()
    @Volatile private var compare: ZomatoCompare? = null

    /** The one Zomato client, shared by cart quotes and the dish lines on the Swiggy cards. */
    fun compare(context: Context): ZomatoCompare =
        compare ?: synchronized(lock) { compare ?: ZomatoCompare(context.applicationContext).also { compare = it } }

    override suspend fun ensureAddress(context: Context, address: NilaAddress): AddressResult = compare(context).ensure(address)

    override suspend fun savedAddresses(context: Context): List<ProviderAddress> = compare(context).saved().orEmpty()

    override suspend fun quoteCart(context: Context, cart: CartRequest): ProviderQuote {
        addressQuote(ensureAddress(context, cart.address.toNila()))?.let { return it }
        val zomato = compare(context)
        val quote = when (val matched = zomato.quote(cart)) {
            is ZomatoGap -> if (matched.failed) ProviderQuote.Failed(matched.reason) else ProviderQuote.NotAvailable(matched.reason)
            is ZomatoQuote -> zomato.total(matched)
        }
        // An expired sign-in surfaces as "Sign in to compare", with the button, rather than as a failure.
        return if (connected(context)) quote else ProviderQuote.SignIn
    }

    override suspend fun quoteDish(context: Context, dish: DishRequest): DishPrice =
        addressDishPrice(ensureAddress(context, dish.address.toNila())) ?: compare(context).dishPrice(dish.restaurant, dish.line, dish.address)
}

/**
 * Zomato's MCP has no tool to save an address (only get_saved_addresses_for_user), so the user adds it in
 * the Zomato app once.
 */
internal fun zomatoCantAdd(label: String) = AddressResult.CantAdd(
    "Zomato can't save new addresses for other apps yet. Add $label in the Zomato app once, then NILA compares there too.",
    AddressFix.OpenApp(ZOMATO_PACKAGE),
)

internal const val ZOMATO_PACKAGE = "com.application.zomato"

internal val foodProviders: List<FoodProvider> = listOf(SwiggyProvider, ZomatoProvider)

/** The app NILA places orders on; its name labels the booking cards. */
internal val orderingProvider: FoodProvider get() = foodProviders.first { it.canOrder }

/** The dish exactly as the results show it: the size picked by default, priced by the app the results came from. */
internal fun dishRequest(providerId: String, address: FoodAddress, dish: FoodDish): DishRequest {
    val size = dish.item.chosenSize
    return DishRequest(
        restaurant = dish.restaurant.name,
        line = CartLine(dish.item.name, size?.name, 1),
        address = deliveryAddress(address),
        known = paiseOf(size?.price ?: dish.item.price)?.let { providerId to it },
    )
}

/** Builds the request every app prices from the ordering app's live cart. */
internal fun cartRequest(providerId: String, address: FoodAddress, restaurant: FoodRestaurant, cart: FoodCartView, coupon: String?): CartRequest {
    return CartRequest(
        restaurant = restaurant.name,
        lines = cart.lines.map { CartLine(it.name.substringBefore(" · "), it.name.substringAfter(" · ", "").ifBlank { null }, it.quantity) },
        address = deliveryAddress(address),
        priced = PricedCart(providerId, cart, restaurant.eta, coupon),
    )
}

/** NILA's chosen address as every app needs it: the line without its label, and its pincode. */
internal fun deliveryAddress(address: FoodAddress): DeliveryAddress {
    val line = address.line.substringAfter(": ")
    return DeliveryAddress(address.label, line, PINCODE.find(line)?.value)
}

/**
 * Swiggy's bill lines as numbers. Labels match loosely ("Taxes and charges", "GST"); a line NILA can't name
 * counts as a fee. Without a total there is nothing to compare, so that is a failure, not a guess.
 */
internal fun swiggyQuote(cart: FoodCartView, eta: String, coupon: String?): ProviderQuote {
    val total = paiseOf(cart.total) ?: return ProviderQuote.Failed("Swiggy sent no total")
    var items = 0L
    var delivery = 0L
    var fees = 0L
    var taxes = 0L
    var discount = 0L
    cart.bill.forEach { (label, value) ->
        val paise = paiseOf(value) ?: return@forEach
        val name = label.lowercase()
        when {
            "item" in name -> items += paise
            "deliver" in name -> delivery += paise
            "tax" in name || "gst" in name -> taxes += paise
            "discount" in name || "coupon" in name -> discount += paise
            else -> fees += paise
        }
    }
    if (items == 0L) items = cart.lines.sumOf { paiseOf(it.price) ?: 0L }
    return ProviderQuote.Priced(Bill(items, delivery, fees, taxes, discount, total).reconciled(), digitsOf(eta), coupon)
}

/**
 * Makes the lines add up to the app's own total: an unexplained remainder is a fee, a shortfall a discount.
 * The total is never changed; it is the number the app will charge.
 */
internal fun Bill.reconciled(): Bill {
    val rest = total - (itemTotal + delivery + platformFee + taxes - discount)
    return when {
        rest > 0 -> copy(platformFee = platformFee + rest)
        rest < 0 -> copy(discount = discount - rest)
        else -> this
    }
}

/** "₹312", "₹12.50", "1,249", "FREE" as paise; null when there is no amount. A sign is ignored. */
internal fun paiseOf(text: String?): Long? {
    val value = text?.trim().orEmpty()
    if (value.equals("free", ignoreCase = true)) return 0L
    val match = AMOUNT.find(value) ?: return null
    val rupees = match.groupValues[1].replace(",", "").toLongOrNull() ?: return null
    val fraction = match.groupValues[2].padEnd(2, '0').ifBlank { "00" }.toLong()
    return rupees * PAISE_PER_RUPEE + fraction
}

private const val PAISE_PER_RUPEE = 100L
private val AMOUNT = Regex("""(\d[\d,]*)(?:\.(\d{1,2}))?""")
private val PINCODE = Regex("""\b\d{6}\b""")
