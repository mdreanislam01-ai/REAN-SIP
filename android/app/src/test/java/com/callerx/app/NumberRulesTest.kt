package com.callerx.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure number helpers used by search and call blocking. */
class NumberRulesTest {

    @Test
    fun canonicalDigits_treatsLocalAndInternationalFormsAsEqual() {
        assertEquals("1712345678", canonicalDigits("+8801712345678"))
        assertEquals("1712345678", canonicalDigits("008801712345678"))
        assertEquals("1712345678", canonicalDigits("01712345678"))
        assertEquals("1712345678", canonicalDigits("8801712345678"))
        assertEquals(canonicalDigits("01712345678"), canonicalDigits("+8801712345678"))
    }

    @Test
    fun canonicalDigits_ignoresSpacesAndDashes() {
        assertEquals("1712345678", canonicalDigits("017-1234 5678"))
        assertEquals("", digitsOnly("(  ) - +"))
        assertEquals("1712", digitsOnly("+1 7-1 2"))
    }

    @Test
    fun blockedEntryMatches_exactSuffixAndWildcard() {
        assertTrue(blockedEntryMatches("01712345678", "01712345678"))
        assertTrue(blockedEntryMatches("01712345678", "+8801712345678"))
        assertTrue(blockedEntryMatches("+88017*", "01799999999"))
        assertFalse(blockedEntryMatches("01712345678", "01712345679"))
        assertFalse(blockedEntryMatches("", "01712345678"))
    }
}
