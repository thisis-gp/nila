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
}
