package dev.folio.mobile.core.reader

import dev.folio.mobile.core.reader.PageTurnKeys.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import android.view.KeyEvent

class PageTurnKeysTest {

    @Test
    fun `volume keys turn pages (down=forward, up=backward)`() {
        assertEquals(Direction.FORWARD, PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertEquals(Direction.BACKWARD, PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `media keys drive bluetooth page-turners`() {
        assertEquals(Direction.FORWARD, PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(Direction.BACKWARD, PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun `hardware page and dpad keys`() {
        assertEquals(Direction.BACKWARD, PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(Direction.FORWARD, PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(Direction.BACKWARD, PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(Direction.FORWARD, PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `unmapped keys are null`() {
        assertNull(PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_BACK))
        assertNull(PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_ENTER))
        assertNull(PageTurnKeys.fromKeyCode(-1))
        assertNull(PageTurnKeys.fromKeyCode(KeyEvent.KEYCODE_VOLUME_MUTE))
    }
}
