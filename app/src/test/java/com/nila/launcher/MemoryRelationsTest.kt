package com.nila.launcher

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRelationsTest {
    private fun fact(text: String, about: String, kind: MemoryKind = MemoryKind.Preference, name: String? = null, relation: String? = null, at: Long = 0) =
        MemoryFact("id-$about-$text", text, kind, at, about, name, relation)

    @Test
    fun plainStatementsAboutPeopleAreSavedByRule() {
        assertEquals(NilaResponse.Remember("likes cricket", MemoryKind.Preference, "cousin"), learnRule("my cousin likes cricket"))
        assertEquals(NilaResponse.Remember("loves filter coffee", MemoryKind.Preference, "amma"), learnRule("My Amma loves filter coffee."))
        assertEquals(NilaResponse.Remember("is allergic to peanuts", MemoryKind.Allergy, "friend"), learnRule("my friend is allergic to peanuts"))
        assertEquals(NilaResponse.Remember("is vegetarian", MemoryKind.Diet, "boss"), learnRule("my boss is vegetarian"))
        assertEquals(NilaResponse.Remember("is vegan", MemoryKind.Diet, "sister"), learnRule("my sister is vegan"))
        assertEquals(NilaResponse.Remember("can't eat gluten", MemoryKind.Diet, "colleague"), learnRule("my colleague can’t eat gluten"))
        assertEquals(NilaResponse.Remember("doesn't eat onion", MemoryKind.Diet, "uncle"), learnRule("my uncle doesn't eat onion"))
        assertEquals(NilaResponse.Remember("hates mushrooms", MemoryKind.Preference, "neighbour"), learnRule("my neighbour hates mushrooms"))
        assertEquals(NilaResponse.Remember("favourite is biryani", MemoryKind.Preference, "cousin"), learnRule("my cousin's favourite is biryani"))
        assertEquals(NilaResponse.Remember("favorite is dosa", MemoryKind.Preference, "wife"), learnRule("my wife's favorite dosa"))
    }

    @Test
    fun otherShapesAreLeftToGemma() {
        assertNull(learnRule("my cousin likes"))
        assertEquals(NilaResponse.Remember("is vegetarian on fridays", MemoryKind.Diet, "cousin"), learnRule("my cousin is vegetarian on fridays"))
        assertNull(learnRule("my car likes petrol"))
        assertNull(learnRule("Priya can't eat gluten"))
        assertNull(learnRule("order my cousin's favourite"))
        assertNull(learnRule("i like cricket"))
    }

    @Test
    fun theSavedFavouriteIsWhatFavouriteOrdersUse() {
        val saved = learnRule("my cousin's favourite is biryani")!!
        assertEquals("biryani", favouriteFor("cousin", listOf(fact(saved.fact, saved.about))))
    }

    @Test
    fun relationsThatCanBeSeveralPeopleAreAskedEachTime() {
        listOf("cousin", "friend", "colleague", "brother", "sister", "son", "daughter", "uncle", "aunt", "grandma", "grandpa", "neighbour", "roommate", "boss")
            .forEach { assertTrue(it, isManyRelation(it)) }
        listOf("wife", "husband", "mom", "mother", "amma", "dad", "father", "appa", ME, "kavya").forEach { assertFalse(it, isManyRelation(it)) }
        assertTrue(isManyRelation("My Cousin"))
        assertFalse(shouldAskContactLink("cousin", null))
        assertTrue(shouldAskContactLink("wife", null))
    }

    @Test
    fun aPickedPersonGetsTheFactWithTheRelationKept() {
        val saved = fact("likes cricket", "cousin")
        val contact = refiled(saved, WhoChoice("Kavya", "0r12-ABC"))
        assertEquals("0r12-abc", contact.about)
        assertEquals("Kavya", contact.personName)
        assertEquals("cousin", contact.relation)
        assertEquals(saved.id, contact.id)
        assertEquals(saved.text, contact.text)
        val typed = refiled(saved, WhoChoice(" Arjun Kumar ", lookupKey = null))
        assertEquals("arjun kumar", typed.about)
        assertEquals("Arjun Kumar", typed.personName)
        assertEquals("cousin", typed.relation)
        assertEquals("cousin", refiled(contact, WhoChoice("Kavya R", null)).relation)
    }

    @Test
    fun knownPeopleComeFirstAndEachPersonOnce() {
        val known = listOf(WhoChoice("Kavya", "k1", known = true), WhoChoice("Arjun", null, known = true))
        val contacts = listOf(ContactChoice("k1", "Kavya"), ContactChoice("k2", "Meera"), ContactChoice("k3", "Ravi"))
        assertEquals(listOf("Kavya", "Arjun", "Meera"), whoChoices(known, contacts).map { it.name })
        assertEquals("Kavya · Cousin", whoLabel(known[0], "cousin"))
        assertEquals("Meera", whoLabel(WhoChoice("Meera", "k2"), "cousin"))
    }

    @Test
    fun thePeopleListShowsNamesWithTheirRelation() {
        val kavya = listOf(fact("likes cricket", "k1", name = "Kavya", relation = "cousin"), fact("loves dosa", "k1", name = "Kavya", relation = "cousin"))
        assertEquals("Kavya", personTitle("k1", kavya))
        assertEquals("Cousin · 2 things NILA knows", personSubtitle(kavya))
        val unsure = listOf(fact("likes chess", "cousin"))
        assertEquals("Cousin (not sure who)", personTitle("cousin", unsure))
        assertEquals("1 thing NILA knows", personSubtitle(unsure))
        assertEquals("Wife", personTitle("wife", listOf(fact("loves butter chicken", "wife"))))
        assertEquals("Kavya (cousin)", factPersonLabel(kavya[0]))
        assertEquals("Wife", factPersonLabel(fact("x", "wife")))
    }

    private val cousins = listOf(
        fact("likes cricket", "k1", name = "Kavya", relation = "cousin", at = 1),
        fact("loves chess", "arjun", name = "Arjun", relation = "cousin", at = 2),
        fact("loves butter chicken", "wife", at = 3),
    )

    @Test
    fun severalCousinsAreAskedAboutNotGuessed() {
        assertEquals("Which cousin, Kavya or Arjun?", whichPersonQuestion("cousin", cousins))
        assertEquals("Which cousin, Kavya, Arjun or someone else?", whichPersonQuestion("cousin", cousins + fact("likes dosa", "cousin")))
        assertNull(whichPersonQuestion("cousin", cousins.take(1)))
        assertNull(whichPersonQuestion("wife", cousins))
        assertEquals("cricket", favouriteFor("cousin", cousins.take(1)))
        assertEquals("order cricket", withNamedFavourite("order Kavya's favourite", cousins))
        assertNull(withNamedFavourite("order Meera's favourite", cousins))
    }

    @Test
    fun askingAboutCousinsListsEachOne() {
        val summary = personSummary("cousin", cousins + fact("likes dosa", "cousin"))
        assertTrue(summary, summary.startsWith("You've told me about more than one cousin:"))
        assertTrue(summary, summary.indexOf("Kavya") < summary.indexOf("Arjun") && summary.indexOf("Arjun") < summary.indexOf("Not sure who"))
        assertFalse(summary.contains("butter chicken"))
        assertTrue(personSummary("cousin", cousins.take(1)).startsWith("Here's what I know about Kavya, your cousin:"))
        assertEquals("cousin", askedAbout("what does my cousin like"))
    }

    @Test
    fun theMigrationOnlyAddsTheRelationColumn() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(2, MIGRATION_1_2.endVersion)
        val run = mutableListOf<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") run += args[0] as String
            null
        } as SupportSQLiteDatabase
        MIGRATION_1_2.migrate(db)
        assertEquals(listOf("ALTER TABLE people ADD COLUMN relation TEXT"), run)
        assertFalse(run.any { it.contains("DROP", ignoreCase = true) || it.contains("DELETE", ignoreCase = true) })
        assertNull(PersonEntity("p", "wife", "Wife", null, null, linkAsked = false, createdAt = 0).relation)
    }
}
