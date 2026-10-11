package com.nila.launcher

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class FakeProvider(override val id: String, override val canOrder: Boolean = false) : FoodProvider {
    override val name = id.replaceFirstChar(Char::uppercase)
    override val initial = name.first()
    override val tint = Color.Gray
    override val appPackage: String? = null
    override val auth: McpAuth get() = error("not used in tests")
    override fun connected(context: Context) = true
    override suspend fun quoteCart(context: Context, cart: CartRequest): ProviderQuote = error("not used in tests")
    override suspend fun quoteDish(context: Context, dish: DishRequest): DishPrice = error("not used in tests")
}

class FoodComparisonTest {
    private val swiggy = FakeProvider("swiggy", canOrder = true)
    private val zomato = FakeProvider("zomato")
    private val third = FakeProvider("third")
    private val fourth = FakeProvider("fourth")

    private fun priced(totalRupees: Long, eta: Int? = null, coupon: String? = null) =
        ProviderQuote.Priced(Bill(totalRupees * 100, 0, 0, 0, 0, totalRupees * 100), eta, coupon)

    @Test
    fun cheapestWinsAndOthersAreSortedByTotal() {
        val ranked = rank(linkedMapOf(swiggy to priced(330), zomato to priced(312), third to priced(320)))
        assertEquals(zomato, ranked.winner?.provider)
        assertEquals(listOf(third, swiggy), ranked.others.map { it.provider })
        assertEquals(800L, ranked.savings)
        assertEquals(third, ranked.runnerUp)
    }

    @Test
    fun aTieGoesToTheAppNilaCanOrderOn() {
        val ranked = rank(linkedMapOf(zomato to priced(312), swiggy to priced(312)))
        assertEquals(swiggy, ranked.winner?.provider)
        assertEquals(0L, ranked.savings)
        assertEquals("Same total on Swiggy and Zomato · ₹312", summaryLine(ranked))
    }

    @Test
    fun aTieBetweenReadOnlyAppsKeepsTheGivenOrder() {
        val ranked = rank(linkedMapOf(third to priced(300), zomato to priced(300)))
        assertEquals(third, ranked.winner?.provider)
        assertEquals(listOf(zomato), ranked.others.map { it.provider })
    }

    @Test
    fun nothingPricedMeansNoWinnerAndNoSummary() {
        val ranked = rank(linkedMapOf(swiggy to ProviderQuote.Failed("took too long"), zomato to ProviderQuote.SignIn))
        assertNull(ranked.winner)
        assertNull(ranked.savings)
        assertNull(summaryLine(ranked))
        assertEquals(2, ranked.unpriced.size)
    }

    @Test
    fun onePricedAppHasNoSavingsAndDoesNotClaimCheapest() {
        val ranked = rank(linkedMapOf(swiggy to priced(312, eta = 28, coupon = "SWIGGYIT"), zomato to ProviderQuote.NotAvailable("Not on Zomato")))
        assertEquals(swiggy, ranked.winner?.provider)
        assertTrue(ranked.others.isEmpty())
        assertNull(ranked.savings)
        assertEquals("Swiggy · ₹312 with SWIGGYIT · 28 min", summaryLine(ranked))
    }

    @Test
    fun summaryNamesTheCheapestWithCouponAndEta() {
        val ranked = rank(linkedMapOf(swiggy to priced(312, eta = 28, coupon = "SWIGGYIT"), zomato to priced(330)))
        assertEquals("Cheapest on Swiggy · ₹312 with SWIGGYIT · 28 min", summaryLine(ranked))
        assertEquals(1800L, ranked.savings)
    }

    @Test
    fun unpricedAppsAreOrderedByReasonThenGivenOrder() {
        val ranked = rank(
            linkedMapOf(
                swiggy to ProviderQuote.NotAvailable("Not on Swiggy"),
                zomato to ProviderQuote.Failed("took too long"),
                third to ProviderQuote.SignIn,
                fourth to ProviderQuote.Checking,
                FakeProvider("fifth") to ProviderQuote.Failed("Zomato didn't answer"),
            ),
        )
        assertEquals(listOf("fourth", "third", "zomato", "fifth", "swiggy"), ranked.unpriced.map { it.first.id })
    }

    @Test
    fun boardRanksInRegistryOrder() {
        val board = QuoteBoard("k", mapOf(zomato.id to priced(300), swiggy.id to priced(300)))
        assertEquals(swiggy, board.ranked(listOf(swiggy, zomato)).winner?.provider)
    }

    @Test
    fun quotesRunAtMostThreeAtATime() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val results = mutableMapOf<String, ProviderQuote>()
        val providers = (1..7).map { FakeProvider("p$it") }
        quoteAll(providers, quote = {
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(20)
            running.decrementAndGet()
            priced(100)
        }) { p, q -> results[p.id] = q }
        assertEquals(QUOTE_PARALLEL, peak.get())
        assertEquals(7, results.size)
    }

    @Test
    fun aSlowOrBrokenProviderFailsAloneAndPlainly() = runBlocking {
        val results = mutableMapOf<String, ProviderQuote>()
        quoteAll(listOf(swiggy, zomato, third), timeoutMillis = 50, quote = { p ->
            when (p) {
                zomato -> awaitCancellation()
                third -> throw IllegalStateException("boom")
                else -> priced(312)
            }
        }) { p, q -> results[p.id] = q }
        assertEquals(priced(312), results["swiggy"])
        assertEquals(ProviderQuote.Failed("took too long"), results["zomato"])
        assertEquals(ProviderQuote.Failed("boom"), results["third"])
    }

    @Test
    fun cancellingTheCallerIsNotTurnedIntoAFailure() = runBlocking {
        val results = mutableListOf<ProviderQuote>()
        val run = async { quoteAll(listOf(zomato), quote = { awaitCancellation() }) { _, q -> results += q } }
        yield()
        run.cancel()
        var cancelled = false
        try { run.await() } catch (e: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(results.isEmpty())
    }

    @Test
    fun amountsBecomePaise() {
        assertEquals(31_200L, paiseOf("₹312"))
        assertEquals(1_250L, paiseOf("₹12.50"))
        assertEquals(1_205L, paiseOf("12.05"))
        assertEquals(124_900L, paiseOf("1,249"))
        assertEquals(6_600L, paiseOf("-₹66"))
        assertEquals(0L, paiseOf("FREE"))
        assertNull(paiseOf("soon"))
        assertNull(paiseOf(null))
    }

    @Test
    fun swiggyBillLabelsMapLooselyAndUnknownsAreFees() {
        val cart = FoodCartView(
            restaurantName = "Meghana Foods",
            lines = listOf(FoodCartLine("1", "Paneer Butter Masala", 1, "₹249"), FoodCartLine("2", "Butter Naan", 2, "₹80")),
            bill = listOf(
                "Item total" to "₹329",
                "Delivery fee" to "FREE",
                "Packing" to "₹10",
                "Platform fee" to "₹2",
                "Taxes and charges" to "₹37",
                "Discount" to "₹66",
            ),
            total = "₹312",
        )
        val quote = swiggyQuote(cart, "25-30 mins", "SWIGGYIT") as ProviderQuote.Priced
        assertEquals(Bill(32_900, 0, 1_200, 3_700, 6_600, 31_200), quote.bill)
        assertEquals(25, quote.etaMinutes)
        assertEquals("SWIGGYIT", quote.coupon)
    }

    @Test
    fun swiggyWithoutATotalIsAFailureNotAGuess() {
        val cart = FoodCartView("X", emptyList(), listOf("Item total" to "₹100"), total = "")
        assertTrue(swiggyQuote(cart, "", null) is ProviderQuote.Failed)
    }

    @Test
    fun billLinesAlwaysAddUpToTheAppsTotal() {
        assertEquals(Bill(30_000, 0, 3_000, 0, 0, 33_000), Bill(30_000, 0, 0, 0, 0, 33_000).reconciled())
        assertEquals(Bill(30_000, 0, 0, 0, 2_000, 28_000), Bill(30_000, 0, 0, 0, 0, 28_000).reconciled())
    }

    @Test
    fun zomatoBillUsesItsTotalAndNamesTheRestAsFees() {
        val result = JSONObject().put("cart", JSONObject().put("total_amount", "352.5").put("delivery_charge", "25"))
        val quote = zomatoBill(result, itemTotal = 30_000, eta = "33 min") as ProviderQuote.Priced
        assertEquals(Bill(30_000, 2_500, 2_750, 0, 0, 35_250), quote.bill)
        assertEquals(33, quote.etaMinutes)
        assertTrue(zomatoBill(JSONObject(), 30_000, "") is ProviderQuote.Failed)
    }

    @Test
    fun dishesMatchOnlyByTheirWholeName() {
        assertTrue(sameDish("Chicken Biryani · Regular", "Chicken Biryani"))
        assertTrue(sameDish("Chicken Biryani (Serves 1)", "chicken biryani - half"))
        assertFalse(sameDish("Chicken Biryani", "Biryani"))
        assertFalse(sameDish("Biryani", "Chicken Biryani"))
        assertFalse(sameDish("Paneer Tikka", "Paneer Tikka Roll"))
        assertFalse(sameDish("Regular", "Regular"))
    }

    @Test
    fun aDishsSizeMustAgree() {
        val half = JSONObject().put("item_name", "Chicken Biryani").put("variant_name", "Half").put("variant_id", "h")
        val full = JSONObject().put("item_name", "Chicken Biryani").put("variant_name", "Full").put("variant_id", "f")
        assertEquals("f", pickVariant(CartLine("Chicken Biryani", "Full", 1), listOf(half, full))?.pick("variant_id"))
        assertNull(pickVariant(CartLine("Chicken Biryani", null, 1), listOf(half, full)))
        assertNull(pickVariant(CartLine("Chicken Biryani", "Full", 1), listOf(half)))
        assertEquals("h", pickVariant(CartLine("Chicken Biryani", null, 1), listOf(half))?.pick("variant_id"))
        assertNull(pickVariant(CartLine("Chicken Biryani", null, 1), emptyList()))
    }

    @Test
    fun zomatoAddressMustShareThePincode() {
        val saved = JSONArray()
            .put(JSONObject().put("address_id", 560001).put("location_name", "Indiranagar, Bengaluru"))
            .put(JSONObject().put("address_id", 7).put("location_name", "WeWork Galaxy, Residency Road, Bengaluru 560025"))
        val near = DeliveryAddress("WeWork", "43 Residency Road, Bengaluru 560025", "560025")
        assertEquals("7", closestAddress(near, saved)?.pick("address_id"))
        // The address id 560001 is not a pincode, and a city alone is never a match.
        assertNull(closestAddress(DeliveryAddress("Home", "Bengaluru 560001", "560001"), saved))
        assertNull(closestAddress(DeliveryAddress("Home", "Bengaluru", null), saved))
    }

    @Test
    fun theRequestCarriesSizesAndThePincode() {
        val cart = FoodCartView("Meghana", listOf(FoodCartLine("1", "Chicken Biryani · Half", 2, "₹400")), emptyList(), "₹420")
        val restaurant = FoodRestaurant("r", "Meghana Foods", "", "", "28 min", "", "", null, "", open = true)
        val request = cartRequest("swiggy", FoodAddress("a", "Work", "Work: 43 Residency Road, Bengaluru 560025"), restaurant, cart, "SWIGGYIT")
        assertEquals(listOf(CartLine("Chicken Biryani", "Half", 2)), request.lines)
        assertEquals(DeliveryAddress("Work", "43 Residency Road, Bengaluru 560025", "560025"), request.address)
        assertEquals("SWIGGYIT", request.priced?.coupon)
        assertEquals(cartKey(request), cartKey(request.copy(priced = null)))
    }

    @Test
    fun restaurantsMatchOnTheWholeBrandName() {
        assertTrue(sameRestaurant("Paradise Biryani", "Paradise Biryani - A Legend Since 1953"))
        assertTrue(sameRestaurant("Meghana Foods", "Meghana Foods (Residency Road)"))
        assertFalse(sameRestaurant("Biryani", "Biryani Blues"))
        assertFalse(sameRestaurant("Biryani Blues", "Biryani"))
        assertFalse(sameRestaurant("Biryani Blues", ""))
    }

    @Test
    fun aVariantThatIsNotASizeMustBeNamedOnZomato() {
        val plain = JSONObject().put("item_name", "Chicken Biryani").put("variant_id", "p")
        val boneless = JSONObject().put("item_name", "Chicken Biryani").put("variant_name", "Boneless").put("variant_id", "b")
        assertNull(pickVariant(CartLine("Chicken Biryani", "Boneless", 1), listOf(plain)))
        assertEquals("b", pickVariant(CartLine("Chicken Biryani", "Boneless", 1), listOf(plain, boneless))?.pick("variant_id"))
    }

    @Test
    fun aZomatoCartMissingADishIsNotPricedAsTheWholeOrder() {
        val lines = listOf(ZomatoLine("Biryani", "v1", 2, 30_000), ZomatoLine("Raita", "v2", 1, 5_000))
        val full = JSONObject().put("items", JSONArray().put(JSONObject().put("variant_id", "v1").put("quantity", 2)).put(JSONObject().put("variant_id", "v2").put("quantity", 1)))
        val short = JSONObject().put("items", JSONArray().put(JSONObject().put("variant_id", "v1").put("quantity", 2)))
        assertTrue(cartHolds(full, lines))
        assertFalse(cartHolds(short, lines))
        assertTrue(cartHolds(JSONObject().put("total_amount", "700"), lines))
    }

    @Test
    fun dishLineShowsEachAppsPriceOrWhyNot() {
        assertEquals("S ₹249 · Z ₹259", dishPriceLine(listOf(swiggy to DishPrice.Found(24_900), zomato to DishPrice.Found(25_900))))
        assertEquals("S ₹249 · Z not listed", dishPriceLine(listOf(swiggy to DishPrice.Found(24_900), zomato to DishPrice.Missing)))
        assertEquals("S ₹249 · Z …", dishPriceLine(listOf(swiggy to DishPrice.Found(24_900), zomato to null)))
        assertNull(dishPriceLine(listOf(swiggy to DishPrice.Found(24_900), zomato to DishPrice.Failed("took too long"))))
    }
}
