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
        // Every word of the dish: butter chicken is not chicken biryani.
        assertTrue(mentionsDish("Butter Chicken Boneless", "butter chicken"))
        assertFalse(mentionsDish("Chicken Biryani", "butter chicken"))
        // Seen on the iQOO 15: sauces and fried rice with the same words are not butter chicken.
        assertFalse(mentionsDish("Stir Fried Chicken In A Garlic Butter Sauce", "butter chicken"))
        assertFalse(mentionsDish("Burnt Butter Garlic Fried Rice Chicken", "butter chicken"))
        assertTrue(mentionsDish("Malabar Parotta (2 pcs) with Butter Chicken", "butter chicken"))
        assertTrue(mentionsDish("Hyderabadi Chicken Dum Biryani", "chicken biryani"))
        assertTrue(mentionsDish("Butter Paneer Chicken", "butter chicken"))
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
    fun theMenuLineUsesTheSharedPriceLine() {
        val shop = ZomatoShop("Meghana Foods", listOf(ZomatoDish("Butter Chicken", 280)))
        assertEquals(DishPrice.Found(28_000), shopDishPrice(ZomatoLookup(loading = false, shop = shop), "Butter Chicken · Regular"))
        assertEquals(DishPrice.Missing, shopDishPrice(ZomatoLookup(loading = false, shop = shop), "Paneer Butter Masala"))
        assertNull(shopDishPrice(ZomatoLookup(loading = true, shop = null), "Butter Chicken"))
        assertEquals(DishPrice.Failed(NO_ANSWER), shopDishPrice(ZomatoLookup(false, ZomatoShop("X", emptyList(), NO_ANSWER)), "Butter Chicken"))
        assertEquals(199.5, priceRupees("₹199.50")!!, 0.0)
    }
}
