package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NilaEngineTest {
    private val engine = NilaEngine()

    @Test
    fun calculatesPercentage() {
        assertEquals(
            NilaResponse.Text("18% of 4250 is 765"),
            engine.respond("What is 18% of 4250?")
        )
    }

    @Test
    fun parsesAlarm() {
        assertEquals(
            NilaResponse.Alarm(6, 30),
            engine.respond("Set an alarm for 6:30 am")
        )
    }

    @Test
    fun routesAlarmToToolCall() {
        assertEquals(
            NilaIntent.Call(ToolCall("create_alarm", mapOf("hour" to 6, "minute" to 30, "meridiem" to "pm"))),
            engine.route("Set an alarm for 6:30 pm")
        )
    }

    @Test
    fun rejectsHoursOutsideOneToTwelveWithMeridiem() {
        val invalid = NilaResponse.Text("That alarm time is not valid.")
        assertEquals(invalid, engine.respond("Set an alarm for 13 pm"))
        assertEquals(invalid, engine.respond("Set an alarm for 13 am"))
        assertEquals(invalid, engine.respond("Set an alarm for 0 am"))
    }

    @Test
    fun convertsTwelveAmAndPm() {
        assertEquals(NilaResponse.Alarm(0, 15), engine.respond("Set an alarm for 12:15 am"))
        assertEquals(NilaResponse.Alarm(12, 15), engine.respond("Set an alarm for 12:15 pm"))
        assertEquals(NilaResponse.Alarm(18, 30), engine.respond("Set an alarm for 6:30 pm"))
    }

    @Test
    fun commitCallWaitsForConfirmation() {
        val order = CapabilityDescriptor(
            id = "place_order",
            description = "Place the order",
            params = listOf(
                Param("merchant", ParamType.STRING, label = "Merchant"),
                Param("items", ParamType.STRING, label = "Items"),
                Param("total_paise", ParamType.PAISE, label = "Total"),
            ),
            permission = PermissionClass.COMMIT,
        )
        val engine = NilaEngine(listOf(order))
        val call = ToolCall("place_order", mapOf("total_paise" to 12345600, "merchant" to "Meghana Foods", "items" to "1 chicken biryani"))

        val pending = engine.execute(NilaIntent.Call(call)) as NilaResponse.NeedsConfirmation
        assertEquals(call, pending.call)
        assertEquals(order, pending.capability)
        assertEquals(listOf("Merchant" to "Meghana Foods", "Items" to "1 chicken biryani", "Total" to "₹1,23,456.00"), pending.lines)

        // Confirmation keeps the engine's registry, so the tool is found; it has no executor and fails safely.
        assertEquals(NilaResponse.Text("I can't do that yet. Nothing was done."), engine.confirm(pending.token))
        // The token is single-use: a second tap runs nothing.
        assertEquals(NilaResponse.Text("This confirmation was already used. Nothing was done."), engine.confirm(pending.token))
    }

    @Test
    fun confirmationKeepsTheValuesTheUserSaw() {
        val order = CapabilityDescriptor(
            id = "place_order",
            description = "Place the order",
            params = listOf(Param("merchant", ParamType.STRING, label = "Merchant"), Param("total_paise", ParamType.PAISE, label = "Total")),
            permission = PermissionClass.COMMIT,
        )
        val engine = NilaEngine(listOf(order))
        val args = mutableMapOf<String, Any>("merchant" to "Meghana Foods", "total_paise" to 23900)

        val pending = engine.execute(NilaIntent.Call(ToolCall("place_order", args))) as NilaResponse.NeedsConfirmation
        // The caller changes its map after the card is shown.
        args["merchant"] = "Someone Else"
        args["total_paise"] = 9_999_900

        assertEquals(mapOf("merchant" to "Meghana Foods", "total_paise" to 23900), pending.call.args)
        assertEquals(listOf("Merchant" to "Meghana Foods", "Total" to "₹239.00"), pending.lines)
    }

    @Test
    fun rejectsInvalidAlarmTime() {
        assertEquals(NilaResponse.Text("That alarm time is not valid."), engine.respond("Set an alarm for 25:00"))
    }

    @Test
    fun rejectsUnregisteredToolCall() {
        val response = engine.execute(NilaIntent.Call(ToolCall("place_order", mapOf("cart" to "1"))))
        assertEquals(NilaResponse.Text("I can't do that. Unknown tool place_order."), response)
    }

    @Test
    fun remembersFactContainingDottedTime() {
        assertEquals(NilaResponse.Remember("Dinner is at 7 p.m."), engine.respond("Remember that Dinner is at 7 p.m."))
    }

    @Test
    fun parsesDottedMeridiemFromSpeech() {
        // Android's recognizer transcribed "six thirty PM" as "6:30 p.m." on the iQOO 15.
        assertEquals(NilaResponse.Alarm(18, 30), engine.respond("set an alarm for 6:30 p.m."))
        assertEquals(NilaResponse.Alarm(6, 30), engine.respond("set an alarm for 6:30 a.m."))
        assertEquals(NilaResponse.Alarm(19, 0), engine.respond("Set an alarm for 7p.m."))
    }

    @Test
    fun foodSearchHandsTheModelsQueryToTheLiveFlow() {
        val call = ToolCall("search_food", mapOf("query" to "chicken biryani"))
        assertEquals(NilaResponse.FoodSearch("chicken biryani"), engine.execute(NilaIntent.Call(call)))
    }

    @Test
    fun directionsHandTheModelsDestinationToMaps() {
        val call = ToolCall("get_directions", mapOf("destination" to "Indiranagar"))
        assertEquals(NilaResponse.Navigate("Indiranagar"), engine.execute(NilaIntent.Call(call)))
    }

    @Test
    fun readsPartySizeAndBudgetFromTheUsersWords() {
        val search = NilaResponse.FoodSearch("dinner", preference = "light")
        assertEquals(
            search.copy(people = 3, budgetRupees = 800),
            engine.foodConstraints("Find a light dinner for three under ₹800", search),
        )
        assertEquals(search.copy(people = 2), engine.foodConstraints("biryani for 2 people", search))
        // The dish name keeps only the dish when the model leaves the party size in it.
        assertEquals(
            NilaResponse.FoodSearch("dinner", people = 3, budgetRupees = 800),
            engine.foodConstraints("dinner for three under 800", NilaResponse.FoodSearch("dinner for three")),
        )
        // A time is not a party size.
        assertEquals(search, engine.foodConstraints("order dinner for 8 pm", search))
    }

    @Test
    fun bestFitPutsPlacesInsideTheBudgetFirst() {
        fun place(name: String, rating: String, cost: String, eta: String) =
            FoodRestaurant(name, name, "", rating, eta, "", cost, null, "", open = true)
        val pricey = place("Social", "4.6", "₹1200 for two", "40-50 mins")
        val cheap = place("Malgudi", "4.4", "₹250 for two", "35-40 mins")
        val fast = place("Blues", "4.1", "₹400 for two", "15-20 mins")
        val picks = bestFits(listOf(pricey, cheap, fast), NilaResponse.FoodSearch("biryani", people = 3, budgetRupees = 800))
        assertEquals(listOf(cheap, fast, pricey), picks.map { it.first })
        assertEquals("About ₹375 for 3, inside ₹800 · 35-40 mins · ★ 4.4", picks.first().second)
        assertTrue(picks.last().second.startsWith("Over your ₹800 budget"))
    }

    @Test
    fun foodIsLeftToTheModelNotKeywords() {
        assertEquals(NilaIntent.Converse("I'm starving, get me biryani"), engine.route("I'm starving, get me biryani"))
    }

    @Test
    fun preparesFoodChoiceWithoutOrdering() {
        val choices = engine.mockFood()

        assertEquals(
            NilaResponse.Confirmation(
                title = "Review Meghana Foods",
                detail = "Chicken boneless biryani · 4.6★ · 24 min",
                price = "₹239",
            ),
            engine.prepare(choices.items.first()),
        )
    }

    @Test
    fun remembersExplicitFact() {
        assertEquals(
            NilaResponse.Remember("I prefer vegetarian food on Fridays"),
            engine.respond("Remember that I prefer vegetarian food on Fridays")
        )
    }
}

