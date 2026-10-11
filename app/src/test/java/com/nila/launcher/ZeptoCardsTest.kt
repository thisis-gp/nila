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
        // A Zepto address without a pincode and only half the words is a neighbour, not the same place.
        assertNull(matchAddress(nila, listOf(SavedAddress("z3", "other", "Kondapur, Hyderabad, Telangana"))))
        // A 6-digit flat number starting with 0 is not a pincode.
        val flat = SavedAddress("z4", "home", "Flat 012345, Vishnu Homes, Kondapur, Hyderabad 500084")
        assertEquals(flat, matchAddress(FoodAddress("s9", "Home", "Flat 012345, Vishnu Homes, Kondapur, Hyderabad 500084"), listOf(flat)))
        // A neighbouring flat can share every other word and the pincode, but is still a different address.
        assertNull(
            matchAddress(
                FoodAddress("s9", "Home", "Flat 503, Vishnu Homes, Kondapur, Hyderabad 500084"),
                listOf(SavedAddress("z5", "home", "Flat 504, Vishnu Homes, Kondapur, Hyderabad 500084")),
            ),
        )
    }

    @Test
    fun zeptoAddressIsFilledOnlyFromRealFields() {
        val schema = org.json.JSONObject(
            """{"properties":{"addressLine":{"type":"string"},"latitude":{"type":"number"},"longitude":{"type":"number"},"landmark":{"type":"string"}},"required":["addressLine","latitude","longitude"]}""",
        )
        val swiggy = org.json.JSONObject("""{"id":"s9","address":"Vishnu Homes, Kondapur 500084","lat":"17.46","lng":"78.36"}""")
        val args = addressArguments(schema, swiggy)!!
        assertEquals("Vishnu Homes, Kondapur 500084", args.getString("addressLine"))
        assertEquals(17.46, args.getDouble("latitude"), 0.0001)
        assertFalse(args.has("landmark"))
        // No coordinates: nothing is saved rather than a half-filled address.
        assertNull(addressArguments(schema, org.json.JSONObject("""{"address":"Vishnu Homes"}""")))
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

    @Test
    fun amountDueIsPulledFromZeptosRawPreviewNotShown() {
        // Real text seen on-device: raw product ids, markdown, and an instruction meant for an LLM agent.
        val preview = """
            🛒 **Order Preview**
            1. 17ce1910-171f-4aac-8fcb-86d96d679487 (Qty: 1)
            Payment Method: COD
            **Bill Summary:**
              Delivery Fee: ₹30
            **Amount To Pay: ₹69**
            ⚠️ **Confirmation Required**: call create_order again with confirmOrder: true.
        """.trimIndent()
        assertEquals("₹69", zeptoAmountDue(preview))
        assertNull(zeptoAmountDue("Zepto: something went wrong"))
    }

    @Test
    fun amountDueAcceptsRsAndInrAndTakesTheLastFigure() {
        assertEquals("₹69", zeptoAmountDue("Amount To Pay: Rs 69"))
        assertEquals("₹69", zeptoAmountDue("Amount To Pay: INR 69"))
        // Zepto repeats the preview with an updated total on the confirmation step; the later one is real.
        assertEquals("₹99", zeptoAmountDue("Amount To Pay: ₹69 ... Amount To Pay: ₹99"))
    }
}
