package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZomatoDishesTest {
    @Test
    fun everySpellingOfTheDishFindsItOnZomato() {
        // People type "biriyani"; menus say "Biryani".
        assertTrue(mentionsDish("Hyderabadi Chicken Biryani", "biriyani"))
        assertTrue(mentionsDish("Mutton Dum Biryani", "biryani"))
        assertTrue(mentionsDish("Egg Briyani", "order biryani"))
        assertFalse(mentionsDish("Chicken 65", "biryani"))
        assertFalse(mentionsDish("Biryani Rice Bowl Combo", "order food"))
    }

    @Test
    fun theCheaperAppIsSaidInWords() {
        assertEquals("₹20 cheaper on Zomato", priceGap(swiggy = 299, zomato = 279))
        assertEquals("₹15 cheaper on Swiggy", priceGap(swiggy = 280, zomato = 295))
        assertEquals("Same price on Zomato", priceGap(swiggy = 250, zomato = 250))
    }

    @Test
    fun theSameDishAtTheSameShopIsMatched() {
        val shop = ZomatoShop("Meghana Foods", listOf(ZomatoDish("Chicken Boneless Biryani", 330), ZomatoDish("Mutton Biryani", 380)))
        assertEquals(330, shop.sameAs("Chicken Boneless Biryani · Regular")?.price)
        assertNull(shop.sameAs("Paneer Biryani"))
    }

    @Test
    fun aPriceVerdictNeedsTheExactSameDish() {
        val shop = ZomatoShop("Meghana Foods", listOf(ZomatoDish("Chicken Biryani", 300), ZomatoDish("Chicken Biryani", 420)))
        // Review on #31: "Biryani" is not "Chicken Biryani", so no "₹X cheaper" claim.
        assertNull(shop.sameAs("Biryani"))
        assertEquals(300, shop.sameAs("Chicken Biryani · Regular")?.price)
        // Size variants with the same name are kept.
        assertEquals(2, shop.dishes.size)
    }

    @Test
    fun thePriceLineIsTyped() {
        val shop = ZomatoShop("Meghana Foods", listOf(ZomatoDish("Butter Chicken", 280)))
        val cheaper = zomatoLine(ZomatoLookup(loading = false, shop = shop), "Butter Chicken", "₹299.50")
        assertEquals("Zomato ₹280 · ₹19 cheaper on Zomato", cheaper.text)
        assertTrue(cheaper.cheaperOnZomato)
        assertFalse(zomatoLine(ZomatoLookup(loading = false, shop = shop), "Paneer Butter Masala", "₹250").cheaperOnZomato)
        assertEquals("Zomato: $NO_ANSWER", zomatoLine(ZomatoLookup(false, ZomatoShop("X", emptyList(), NO_ANSWER)), "Butter Chicken", "₹1").text)
        assertEquals(199.5, priceRupees("₹199.50")!!, 0.0)
    }

    @Test
    fun addressFailureIsDifferentFromNoMatchingAddress() {
        assertEquals(ZomatoAddress.Failed, addressResult(answered = false, id = null))
        assertEquals(ZomatoAddress.Empty, addressResult(answered = true, id = null))
        assertEquals(ZomatoAddress.Found("42"), addressResult(answered = true, id = "42"))
    }
}
