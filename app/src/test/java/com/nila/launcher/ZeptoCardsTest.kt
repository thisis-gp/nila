package com.nila.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZeptoCardsTest {
    private val milk = CartItem("v1", "s1", "Milk 500 ml", priceInPaisa = 3_400, quantity = 2, imageUrl = null)
    private val bread = CartItem("v2", "s2", "Bread", priceInPaisa = 4_550, quantity = 1, imageUrl = null)

    @Test
    fun cartTotalCountsEveryUnit() {
        assertEquals(11_350L, cartTotalPaise(listOf(milk, bread)))
        assertEquals(0L, cartTotalPaise(emptyList()))
        assertEquals("₹113.50", paiseText(11_350))
        assertEquals("₹34", paiseText(3_400))
    }

    @Test
    fun theGatePlacesOnlyOnTheSecondTapAndOnlyOnce() {
        var sent = 0
        val place = { sent++; true }
        val armed = GateState().tap(place)
        assertTrue(armed.armed)
        assertEquals(0, sent)
        val placed = armed.tap(place)
        assertTrue(placed.placed)
        assertEquals(1, sent)
        // A third tap never sends a second order.
        assertEquals(placed, placed.tap(place))
        assertEquals(1, sent)
    }

    @Test
    fun aBusyStepLeavesTheGateReadyInsteadOfStuck() {
        // runFood skipped the call because another step was running: nothing was sent.
        val after = GateState(armed = true).tap { false }
        assertFalse(after.placed)
        assertFalse(after.armed)
    }

    @Test
    fun zeptoDeliversToTheAddressChosenInNila() {
        val home = SavedAddress("z1", "home", "503, 5th floor, Vishnu Homes, Kondapur, Hyderabad 500084")
        val work = SavedAddress("z2", "work", "Tower B, Hitech City, Hyderabad 500081")
        val nila = FoodAddress("s9", "Home", "Vishnu Homes, 503, Kondapur, Hyderabad, Telangana 500084")
        assertEquals(home, matchAddress(nila, listOf(work, home)))
        // A different pincode is a different place, however many words match.
        assertNull(matchAddress(FoodAddress("s9", "Home", "Vishnu Homes, 503, Kondapur, Hyderabad 500032"), listOf(home)))
        // Nothing alike: NILA asks instead of guessing.
        assertNull(matchAddress(FoodAddress("s7", "Room", "Room Ballal, Koramangala, Bengaluru 560034"), listOf(home, work)))
    }

    @Test
    fun aChangedZeptoCartIsNotTheOrderReviewed() {
        assertTrue(sameZeptoCart(listOf(milk, bread), listOf(bread, milk)))
        assertFalse(sameZeptoCart(listOf(milk), listOf(milk.copy(quantity = 3))))
        assertFalse(sameZeptoCart(listOf(milk), listOf(milk, bread)))
    }

    @Test
    fun instamartCartIsReadFromGetCart() {
        val payload = JSONObject(
            """
            {"data": {
              "cartTotalAmount": "128",
              "selectedAddressDetails": {"id": "addr_1", "annotation": "Room Ballal", "address": "Koramangala"},
              "items": [{"spinId": "sp1", "skuId": "sk1", "itemName": "Nandini Cow Milk", "itemVariant": "500 ml",
                         "quantity": 2, "mrp": 26.0, "discountedFinalPrice": 25.995}],
              "billBreakdown": {"lineItems": [{"label": "Item Total", "value": "₹52.00"}], "toPay": {"label": "To pay", "value": "₹128"}},
              "availablePaymentMethods": ["UPI", "Cash on Delivery"]
            }}
            """,
        )
        val cart = parseInstamartCart(payload, fallbackAddressId = "fallback")
        assertEquals(1, cart.lines.size)
        val (item, quantity) = cart.lines.single()
        assertEquals("sp1", item.spinId)
        assertEquals(2, quantity)
        // Rounded, not truncated: ₹25.995 is 2600 paise.
        assertEquals(2_600L, item.pricePaise)
        assertEquals("₹128", cart.toPay)
        assertEquals("addr_1", cart.addressId)
        assertEquals("Room Ballal", cart.addressLabel)
        assertEquals(listOf("Item Total" to "₹52.00"), cart.bill)
        assertEquals("Cash on Delivery", cart.cashMethod)

        // Checkout sends Swiggy's current cart, so any change sends the user back to review.
        assertTrue(cart.sameOrderAs(parseInstamartCart(payload, null)))
        val more = JSONObject(payload.toString().replace("\"quantity\":2", "\"quantity\":3"))
        assertFalse(cart.sameOrderAs(parseInstamartCart(more, null)))
    }

    @Test
    fun anEmptyInstamartCartFallsBackToNilasAddress() {
        val cart = parseInstamartCart(JSONObject("""{"data": {"cartAbsent": true, "cartAbsentReason": "No cart"}}"""), "addr_9")
        assertTrue(cart.lines.isEmpty())
        assertEquals("addr_9", cart.addressId)
        assertEquals("No cart", cart.warning)
        assertNull(cart.cashMethod)
    }
}
