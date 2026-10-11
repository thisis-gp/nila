package com.nila.launcher

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MapStore(val values: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class NilaAddressesTest {
    private val wework = NilaAddress("WeWork Galaxy", "WeWork Galaxy, 43 Residency Road, Ashok Nagar, Bengaluru 560025", lat = 12.9716, lng = 77.6070)

    private fun saved(id: String, line: String, lat: Double? = null, lng: Double? = null) =
        ProviderAddress(id, NilaAddress("Work", line, pincode = pincodeIn(line), lat = lat, lng = lng))

    // ---- matcher ----

    @Test
    fun coordinatesDecideWhenBothSidesHaveThem() {
        // About 110 m north: the same building as far as a geocoded pin can tell.
        val near = saved("s1", "Galaxy Tower, Residency Rd, Bengaluru 560025", lat = 12.9726, lng = 77.6070)
        assertEquals(near, matchAddress(wework, listOf(near)))
        // About 1.1 km away on the same pincode: a different place, even sharing every street word.
        val far = saved("s2", "WeWork Galaxy, Residency Road, Bengaluru 560025", lat = 12.9816, lng = 77.6070)
        assertNull(matchAddress(wework, listOf(far)))
    }

    @Test
    fun withoutCoordinatesABuildingOrStreetWordMustMatch() {
        val building = saved("s1", "WeWork Galaxy, Residency Road, Bengaluru 560025")
        assertEquals(building, matchAddress(wework.copy(lat = null, lng = null), listOf(building)))
        // One side without coordinates falls back to the words too.
        assertEquals(building, matchAddress(wework, listOf(building)))
        val street = saved("s2", "Residency Road, Bengaluru 560025")
        assertEquals(street, matchAddress(NilaAddress("Office", "12 Residency Road, Bengaluru 560025"), listOf(street)))
    }

    @Test
    fun aCityOrAreaAloneNeverMatches() {
        // Same pincode, same area and city, nothing else shared.
        assertNull(matchAddress(NilaAddress("Home", "Koramangala, Bengaluru 560034"), listOf(saved("s1", "Room Ballal, Koramangala, Bengaluru 560034"))))
        assertNull(matchAddress(NilaAddress("Home", "Bengaluru 560025", city = "Bengaluru"), listOf(saved("s1", "WeWork Galaxy, Bengaluru 560025"))))
        // Explicit area and city fields never count as building words.
        val here = NilaAddress("Here", "Ashok Nagar, Bengaluru 560025", area = "Ashok Nagar", city = "Bengaluru")
        assertNull(matchAddress(here, listOf(saved("s1", "Prestige Tower, Ashok Nagar, Bengaluru, Karnataka 560025"))))
        // Generic words are not a building: "Road" alone.
        assertNull(matchAddress(NilaAddress("A", "MG Road, Bengaluru 560001"), listOf(saved("s1", "Brigade Road, Bengaluru 560001"))))
    }

    @Test
    fun aDifferentPincodeNeverMatches() {
        assertNull(matchAddress(wework, listOf(saved("s1", "WeWork Galaxy, 43 Residency Road, Bengaluru 560001", lat = 12.9716, lng = 77.6070))))
    }

    @Test
    fun withoutAPincodeTheBuildingAndStreetMustAgree() {
        // Zomato saves one name string, often with no pincode.
        assertNotNull(matchAddress(NilaAddress("A", "WeWork Galaxy, Residency Road, Bengaluru 560025"), listOf(saved("s1", "WeWork Galaxy, 43 Residency Road, Ashok Nagar, Bengaluru"))))
        // One shared word is not enough.
        assertNull(matchAddress(NilaAddress("A", "WeWork Galaxy, Residency Road, Bengaluru 560025"), listOf(saved("s1", "Galaxy Apartments, Koramangala, Bengaluru"))))
    }

    @Test
    fun differentHouseNumbersAreDifferentAddresses() {
        val flat504 = saved("s1", "Flat 504, Vishnu Homes, Kondapur, Hyderabad 500084", lat = 17.46, lng = 78.36)
        assertNull(matchAddress(NilaAddress("Home", "Flat 503, Vishnu Homes, Kondapur, Hyderabad 500084", lat = 17.46, lng = 78.36), listOf(flat504)))
    }

    @Test
    fun theVerySameLineAlwaysMatchesAndTheClosestWins() {
        val noPin = saved("s1", "Work: Hotel2, Indiranagar")
        assertEquals(noPin, matchAddress(NilaAddress("Hotel2", "Hotel2, Indiranagar"), listOf(noPin)))
        val closer = saved("c", "WeWork Galaxy, Residency Road, Bengaluru 560025", lat = 12.9717, lng = 77.6070)
        val further = saved("f", "WeWork Galaxy, Residency Road, Bengaluru 560025", lat = 12.9730, lng = 77.6070)
        assertEquals(closer, matchAddress(wework, listOf(further, closer)))
        assertNull(matchAddress(wework, emptyList()))
    }

    @Test
    fun appAddressesAreReadUnderTheirOwnFieldNames() {
        val swiggy = JSONObject().put(
            "addresses",
            JSONArray().put(JSONObject().put("id", "a1").put("annotation", "WORK").put("address", "WeWork Galaxy, Bengaluru 560025").put("lat", "12.97").put("lng", "77.60")),
        )
        val parsed = parseProviderAddresses(swiggy).single()
        assertEquals("a1", parsed.id)
        assertEquals("Work", parsed.address.label)
        assertEquals("560025", parsed.address.pin)
        assertEquals(12.97, parsed.address.lat!!, 0.0001)
    }

    // ---- address book and migration ----

    @Test
    fun theOldSwiggyChoiceMovesToNilaOnce() {
        val store = MapStore(mutableMapOf("last_address" to "a2"))
        val book = NilaAddressBook(store)
        assertTrue(book.needsMigration)
        val swiggy = listOf(saved("a1", "Hotel2, Indiranagar, Bengaluru 560038"), saved("a2", "WeWork Galaxy, Bengaluru 560025"))
        assertEquals("WeWork Galaxy, Bengaluru 560025", book.migrate(swiggy)?.line)
        assertEquals("WeWork Galaxy, Bengaluru 560025", book.chosen()?.line)
        assertNull(store.values["last_address"])
        assertFalse(book.needsMigration)
        // Done once: a later list doesn't change NILA's choice.
        assertEquals("WeWork Galaxy, Bengaluru 560025", NilaAddressBook(store).migrate(listOf(saved("a1", "Hotel2, Indiranagar, Bengaluru 560038")))?.line)
    }

    @Test
    fun anOldIdSwiggyNoLongerListsIsKept() {
        val store = MapStore(mutableMapOf("last_address" to "gone"))
        val book = NilaAddressBook(store)
        assertNull(book.migrate(listOf(saved("a1", "Hotel2, Bengaluru 560038"))))
        assertEquals("gone", store.values["last_address"])
        // Nothing to move and nothing chosen: Swiggy isn't asked at all.
        assertNull(runBlocking { NilaAddressBook(MapStore()).current { error("Swiggy asked without a reason") } })
    }

    @Test
    fun choosingKeepsOneAddressAndAShortRecentList() {
        val book = NilaAddressBook(MapStore())
        val home = NilaAddress("Home", "Vishnu Homes, 503, Kondapur, Hyderabad 500084", area = "Kondapur", city = "Hyderabad", pincode = "500084", lat = 17.46, lng = 78.36)
        book.choose(home)
        book.choose(wework)
        book.choose(home.copy(label = "Home again"))
        assertEquals("Home again", book.chosen()?.label)
        assertEquals(17.46, book.chosen()?.lat!!, 0.0001)
        assertEquals(listOf("Home again", "WeWork Galaxy"), book.recent().map { it.label })
        repeat(8) { book.choose(NilaAddress("P$it", "Plot $it, Some Layout, Bengaluru 5600${10 + it}")) }
        assertEquals(5, book.recent().size)
        book.contact = NilaContact("Guru", "9876543210")
        assertEquals(NilaContact("Guru", "9876543210"), book.contact)
    }

    // ---- picker ----

    @Test
    fun thePickerShowsEachPlaceOnceWithEveryAppThatHasIt() {
        val recent = listOf(wework)
        val swiggy = listOf(saved("s1", "WeWork Galaxy, Residency Road, Bengaluru 560025"), saved("s2", "Hotel2, 80 Feet Road, Indiranagar, Bengaluru 560038"))
        val zomato = listOf(saved("z1", "Hotel2, 80 Feet Road, Indiranagar, Bengaluru 560038"))
        val choices = addressChoices(recent, listOf("Swiggy" to swiggy, "Zomato" to zomato))
        assertEquals(2, choices.size)
        assertEquals("WeWork Galaxy", choices[0].address.label)
        assertEquals(listOf(RECENT_SOURCE, "Swiggy"), choices[0].sources)
        assertEquals(listOf("Swiggy", "Zomato"), choices[1].sources)
    }

    // ---- providers ----

    @Test
    fun swiggyReusesAMatchingAddressAndSavesAMissingOne() = runBlocking {
        val calls = mutableListOf<String>()
        val list = JSONObject().put("addresses", JSONArray().put(JSONObject().put("id", "s1").put("address", "WeWork Galaxy, Residency Road, Bengaluru 560025")))
        val fake: suspend (String, JSONObject) -> JSONObject? = { tool, _ ->
            calls += tool
            if (tool == "get_addresses") list else JSONObject().put("data", JSONObject().put("addressId", "new"))
        }
        assertEquals(AddressResult.Ready("s1"), ensureSwiggyAddress(wework, null, call = fake))
        assertEquals(listOf("get_addresses"), calls)
        val home = NilaAddress("Vishnu Homes", "Vishnu Homes, 503, Kondapur, Hyderabad 500084", city = "Hyderabad", pincode = "500084")
        assertEquals(AddressResult.Added("new"), ensureSwiggyAddress(home, NilaContact("Guru", "9876543210"), call = fake))
        assertEquals(listOf("get_addresses", "get_addresses", "create_address"), calls)
        assertTrue(ensureSwiggyAddress(home, null) { _, _ -> null } is AddressResult.Failed)
    }

    @Test
    fun swiggyGetsTheFieldsItsFormNeeded() {
        val args = swiggyAddressArgs(
            NilaAddress("WeWork Galaxy", "WeWork Galaxy, 43 Residency Road, Ashok Nagar, Bengaluru, 560025", area = "Ashok Nagar", city = "Bengaluru", pincode = "560025"),
            NilaContact("Guru", "9876543210"),
            "WORK",
        )
        assertEquals("WeWork Galaxy", args.getString("addressLine2"))
        assertEquals("43 Residency Road, Ashok Nagar", args.getString("addressLine"))
        assertEquals("560025", args.getString("postalCode"))
        assertEquals("Ashok Nagar", args.getString("locality"))
        assertEquals("WORK", args.getString("addressCategory"))
        assertEquals("9876543210", args.getString("userPhone"))
    }

    @Test
    fun zomatoWithoutTheAddressIsOneRowWithAWayToFixIt() {
        val cantAdd = zomatoCantAdd("WeWork Galaxy")
        assertEquals(AddressFix.OpenApp("com.application.zomato"), cantAdd.fix)
        assertTrue(cantAdd.reason.contains("Add WeWork Galaxy in the Zomato app once"))
        val quote = addressQuote(cantAdd)
        assertEquals(ProviderQuote.NoAddress(cantAdd.reason, cantAdd.fix), quote)
        assertEquals(DishPrice.NoAddress, addressDishPrice(cantAdd))
        assertNull(addressQuote(AddressResult.Ready("7")))
        assertNull(addressDishPrice(AddressResult.Added("7")))
        assertEquals(ProviderQuote.Failed("Zomato didn't answer"), addressQuote(AddressResult.Failed("Zomato didn't answer")))
    }

    @Test
    fun addedNoteNamesOnlyAppsThatGotTheAddressNow() {
        val state = AddressSyncState(wework, mapOf("swiggy" to AddressResult.Added("s9"), "zomato" to zomatoCantAdd("WeWork Galaxy")))
        assertEquals("Added WeWork Galaxy to Swiggy", addedNote(state))
        assertNull(addedNote(state.copy(results = mapOf("swiggy" to AddressResult.Ready("s9")))))
        assertNull(addedNote(AddressSyncState()))
    }

    // ---- nearest saved address ----

    private val farLine = "Some Tower, Indiranagar, Bengaluru 560038"

    @Test
    fun nearestPicksTheClosestWithinFiveKm() {
        val home = saved("z1", farLine, lat = 12.9986, lng = 77.6070)
        val closer = saved("z2", "Other Place, Bengaluru 560001", lat = 12.9836, lng = 77.6070)
        val tooFar = saved("z3", "Far Place, Bengaluru 560100", lat = 13.0436, lng = 77.6070)
        val nearby = nearestAddress(wework, listOf(home, tooFar, closer), 5.0)!!
        assertEquals("z2", nearby.providerAddressId)
        assertEquals(1.3, nearby.distanceKm!!, 0.1)
    }

    @Test
    fun nearestIsNullWhenEverythingIsOverFiveKm() {
        val far = saved("z1", farLine, lat = 13.0436, lng = 77.6070)
        assertNull(nearestAddress(wework, listOf(far), 5.0))
    }

    @Test
    fun withoutCoordinatesTheSamePincodeIsNearby() {
        val samePin = saved("z1", "Elsewhere Apartments, Bengaluru 560025")
        val nearby = nearestAddress(wework.copy(lat = null, lng = null), listOf(samePin), 5.0)!!
        assertEquals("z1", nearby.providerAddressId)
        assertNull(nearby.distanceKm)
        assertEquals("your Work address, same pincode", nearbyText(nearby))
    }

    @Test
    fun withoutCoordinatesAnotherPincodeIsNeverNearby() {
        val otherPin = saved("z1", farLine)
        assertNull(nearestAddress(wework.copy(lat = null, lng = null), listOf(otherPin), 5.0))
        // The same city alone is not enough either.
        assertNull(nearestAddress(wework.copy(lat = null, lng = null, city = "Bengaluru"), listOf(otherPin.copy(address = otherPin.address.copy(city = "Bengaluru"))), 5.0))
    }

    @Test
    fun nearbyTextRoundsToWholeKm() {
        assertEquals("your Home address, 3 km away", nearbyText(AddressResult.Nearby("z1", "Home", 2.6)))
        assertEquals("your Home address, under 1 km away", nearbyText(AddressResult.Nearby("z1", "Home", 0.4)))
    }

    // ---- short name ----

    @Test
    fun shortNameIsNeverTheHouseNumber() {
        assertEquals("Residency Road", shortPlaceName("43, Residency Road, Ashok Nagar, Bengaluru 560025", "Work"))
        assertEquals("WeWork Galaxy", shortPlaceName("Work: WeWork Galaxy, 43 Residency Road, 560025", "Work"))
        assertEquals("Galaxy Tower", shortPlaceName("3rd Floor, 12/A, Galaxy Tower, Bengaluru", "Work"))
        assertEquals("Work", shortPlaceName("43, 560025", "Work"))
    }
}
