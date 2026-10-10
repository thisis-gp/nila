package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class ZeptoCardsTest {
    @Test
    fun cartTotalCountsEveryUnit() {
        val milk = CartItem("v1", "s1", "Milk 500 ml", priceInPaisa = 3_400, quantity = 2, imageUrl = null)
        val bread = CartItem("v2", "s2", "Bread", priceInPaisa = 4_550, quantity = 1, imageUrl = null)
        assertEquals(11_350L, cartTotalPaise(listOf(milk, bread)))
        assertEquals(0L, cartTotalPaise(emptyList()))
        assertEquals("₹113.50", paiseText(11_350))
        assertEquals("₹34", paiseText(3_400))
    }
}
