package com.nila.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyNilaQuestionsTest {
    private val facts = listOf(
        newFact("My name is Gurupriyan", MemoryKind.Other, 1),
        newFact("I like filter coffee", MemoryKind.Preference, 2),
        newFact("Vegetarian", MemoryKind.Diet, 3),
        newFact("My wife loves butter chicken", MemoryKind.Preference, 4, about = "wife"),
    )

    @Test
    fun everydayWaysOfAskingAboutYourselfReadMyNila() {
        listOf(
            "what do you know about me", "what are my details", "show my profile", "my details",
            "who am I", "what do I like", "tell me about myself", "what do you remember about me",
        ).forEach { assertEquals(it, ME, askedAbout(it)) }
    }

    @Test
    fun askingAboutSomeoneElseFindsThatPerson() {
        assertEquals("wife", askedAbout("what does my wife like"))
        assertEquals("amma", askedAbout("tell me about my amma"))
    }

    @Test
    fun theNameQuestionGetsJustTheName() {
        assertTrue(askedName("what's my name"))
        assertEquals("You're Gurupriyan.", nameAnswer(facts))
    }

    @Test
    fun notesAndOrdersAreNotMyNilaQuestions() {
        assertNull(askedAbout("show my notes"))
        assertNull(askedAbout("order biryani"))
        assertFalse(askedName("take a note that my name is on the list"))
    }

    @Test
    fun theSummaryUsesMyNilasCleanLinesAndTheName() {
        val summary = personSummary(ME, facts)
        assertTrue(summary, summary.startsWith("Here's what I know about you, Gurupriyan:"))
        assertTrue(summary, "• Likes filter coffee" in summary)
        assertFalse(summary, "My name is" in summary)
    }
}
