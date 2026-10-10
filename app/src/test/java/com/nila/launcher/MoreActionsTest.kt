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
}
