package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TypeLabelFormatterTest {

    @Test
    fun `empty string returns empty string`() {
        assertEquals("", formatAddonTypeLabel(""))
    }

    @Test
    fun `string with only spaces returns empty string`() {
        assertEquals("", formatAddonTypeLabel("   "))
    }

    @Test
    fun `lowercase string returns capitalized string`() {
        assertEquals("Movie", formatAddonTypeLabel("movie"))
    }

    @Test
    fun `uppercase string remains uppercase`() {
        assertEquals("MOVIE", formatAddonTypeLabel("MOVIE"))
    }

    @Test
    fun `mixed case string with lowercase first letter returns capitalized first letter`() {
        assertEquals("MoViE", formatAddonTypeLabel("moViE"))
    }

    @Test
    fun `string with leading and trailing spaces returns capitalized trimmed string`() {
        assertEquals("Movie", formatAddonTypeLabel(" movie "))
        assertEquals("MOVIE", formatAddonTypeLabel("  MOVIE  "))
    }

    @Test
    fun `single lowercase character returns capitalized character`() {
        assertEquals("M", formatAddonTypeLabel("m"))
    }

    @Test
    fun `single uppercase character remains uppercase`() {
        assertEquals("M", formatAddonTypeLabel("M"))
    }
}
