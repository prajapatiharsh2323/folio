package dev.folio.mobile.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightnessAlphaTest {

    @Test
    fun `default brightness paints no scrim`() {
        assertEquals(0f, brightnessAlpha(100.0), 0.0001f)
    }

    @Test
    fun `minimum brightness never goes fully black`() {
        assertTrue(brightnessAlpha(30.0) < 0.75f)
        assertTrue(brightnessAlpha(30.0) > 0.3f)
    }

    @Test
    fun `monotonic between bounds`() {
        assertTrue(brightnessAlpha(60.0) > brightnessAlpha(80.0))
        assertTrue(brightnessAlpha(60.0) < brightnessAlpha(30.0))
    }
}
