package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreActionsTest {
    private val engine = NilaEngine()
    private fun respond(text: String) = engine.respond(text)
    private fun tool(text: String) = (engine.route(text) as? NilaIntent.Call)?.call?.capabilityId

    @Test
    fun messagesDraftForAPersonButNeverForMe() {
        assertEquals(Message("Rahul", "I'll be late"), respond("tell Rahul I'll be late"))
        assertEquals(Message("Amma", "Reached home"), respond("text Amma saying reached home"))
        // "tell me a joke" asks NILA, it is not a message to someone called "me".
        assertNull(messageParts("tell me a joke"))
        assertTrue(tool("tell me a joke") != "send_message")
    }

    @Test
    fun phrasebookShoppingCommandsRouteDeterministically() {
        assertEquals(NilaResponse.GrocerySearch("bananas"), respond("shop groceries bananas"))
        assertEquals(NilaResponse.FoodSearch("dinner"), respond("order food dinner"))
        // A person's own "buy dinner" is not routed by its verb; Gemma's rewrite decides the category.
        assertTrue(respond("buy dinner") !is NilaResponse.GrocerySearch)
    }

    @Test
    fun mapsAndSearch() {
        assertEquals(NilaResponse.Navigate("Indiranagar"), respond("take me to Indiranagar"))
        assertEquals(MapSearch("petrol pumps"), respond("petrol pumps near me"))
        assertEquals(WebSearch("IPL score"), respond("search for IPL score"))
    }

    @Test
    fun musicAndMediaControls() {
        assertEquals(PlayMusic("arijit singh"), respond("play arijit singh"))
        // "play music" resumes what was playing; "play some music" with no song named does the same through the player.
        assertEquals(MediaKey("play"), respond("play music"))
        assertEquals(PlayMusic(null), respond("play some music"))
        assertEquals(MediaKey("pause"), respond("pause the music"))
        assertEquals(MediaKey("next"), respond("next song"))
        assertEquals(MediaKey("previous"), respond("previous track"))
    }

    @Test
    fun soundModes() {
        assertEquals(SoundChange("up"), respond("volume up"))
        assertEquals(SoundChange("down"), respond("turn down the volume"))
        assertEquals(SoundChange("silent"), respond("put my phone on silent"))
        assertEquals(SoundChange("vibrate"), respond("vibrate mode"))
        assertEquals(SoundChange("mute"), respond("mute"))
        // "ring" also means call; "ring mode" made a "Call mode" card on the iQOO 15.
        assertEquals(SoundChange("ring"), respond("ring mode"))
        assertNull(callTarget("ring mode"))
        assertEquals(Call("Amma"), respond("ring Amma"))
    }

    @Test
    fun settingsAlarmsBatteryAndApps() {
        assertEquals(SettingsPage("wifi"), respond("open wi-fi settings"))
        assertEquals(SettingsPage("display"), respond("brightness settings"))
        assertEquals(SettingsPage("main"), respond("open settings"))
        assertEquals(ShowAlarms, respond("show my alarms"))
        assertEquals(BatteryLevel, respond("how much battery is left"))
        // Battery settings is a settings page, not the battery level.
        assertEquals(SettingsPage("battery"), respond("battery settings"))
        assertEquals(LaunchApp("whatsapp"), respond("open whatsapp"))
        assertEquals(LaunchApp("google pay"), respond("launch google pay app"))
    }

    @Test
    fun earlierRoutesStillWin() {
        assertEquals("create_alarm", tool("set an alarm for 7 am"))
        assertEquals("remove_alarm", tool("cancel my alarm"))
        assertEquals("open_camera", tool("open the camera"))
        assertEquals("open_app", tool("open youtube"))
        assertEquals("call_contact", tool("call Mom"))
        assertEquals("set_timer", tool("start a timer for 5 minutes"))
    }

    @Test
    fun anAppNameAloneIsNotAnOpenCommand() {
        // The router eval caught this: "what is YouTube" is a question, not a launch command.
        assertNull(tool("what is YouTube"))
        assertNull(tool("what is youtube"))
        assertEquals("open_app", tool("youtube kholo"))
        assertEquals("open_app", tool("I want to watch something on netflix"))
        assertEquals("open_app", tool("take me to bgmi app"))
    }

    @Test
    fun phrasingsTheModelTierUsedToMissNowRouteByRule() {
        // Each line was a wrong route in the 78% Pixel 4a eval run; all are unambiguous phrasings.
        assertEquals("Whitefield", directionsTo("how much time to Whitefield"))
        assertEquals("Bangalore Palace", directionsTo("how much time does it take to Bangalore Palace"))
        assertEquals("create_alarm", tool("12:15 pm alarm"))
        assertEquals("create_alarm", tool("5:30 pm alarm"))
        assertEquals("remember_fact", tool("don't forget I am allergic to peanuts"))
        assertEquals("remember_fact", tool("don't forget my office is in Whitefield"))
        assertEquals("create_event", tool("schedule demo review monday at 10am"))
        assertEquals("create_event", tool("schedule hackathon finale saturday at 9am"))
    }

    @Test
    fun mealWordsAreRestaurantFoodNotGroceries() {
        // Measured retreats-to-chat on the Pixel 4a eval run.
        assertEquals("search_food", tool("get me lunch"))
        assertEquals("search_food", tool("get me dessert"))
        assertEquals("search_food", tool("im starving"))
        assertEquals("search_food", tool("bhook lagi hai"))
        // Item asks stay with the model tier: bread is genuinely ambiguous, eggs reached the
        // groceries tool through the shortlist in the on-device run.
        assertNull(tool("get me bread"))
        assertNull(tool("get me eggs"))
    }

    @Test
    fun casualItemAsksAskFoodOrGroceriesThenRouteTheAnswer() {
        // Measured 0/5 on the fine-tuned router: casual item asks are asked, never guessed.
        assertNull(tool("get me eggs"))
        val ask = engine.respond("get me eggs")
        assertTrue(ask is NilaResponse.Text && "eggs" in (ask as NilaResponse.Text).value)

        assertEquals(ToolCall("search_groceries", mapOf("query" to "eggs")), engine.route("groceries").let { (it as NilaIntent.Call).call })
        assertNull(engine.route("get me dishwash liquid").let { it as? NilaIntent.Call })

        assertTrue(engine.respond("I need dishwash liquid") is NilaResponse.Text)
        assertEquals(
            ToolCall("search_food", mapOf("query" to "dishwash liquid")),
            (engine.route("restaurant food") as NilaIntent.Call).call,
        )
    }

    @Test
    fun mealWordItemAsksStillBypassTheBasketAsk() {
        assertEquals("search_food", tool("get me lunch"))
    }

    @Test
    fun whereIsAPlaceShowsTheRouteNotASearch() {
        assertEquals("get_directions", tool("where is meghana biriyani"))
        assertEquals("get_directions", tool("Where's Truffles?"))
        assertEquals("get_directions", tool("how far is the airport"))
        assertEquals("Meghana Biriyani", directionsTo("where is Meghana Biriyani?"))
    }

    @Test
    fun whereIsMyThingIsNotAPlace() {
        assertNull(directionsTo("where is my phone"))
        assertNull(directionsTo("where is my order"))
    }
}
