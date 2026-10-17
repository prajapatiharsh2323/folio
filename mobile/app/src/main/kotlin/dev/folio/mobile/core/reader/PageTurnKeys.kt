package dev.folio.mobile.core.reader

/**
 * Physical page-turner key mapping (R2b): volume keys, Bluetooth
 * page-turner/media buttons, hardware back-page keys and DPAD.
 *
 * Convention (Play Books / Moon+ Reader): Volume Up walks BACKWARD (toward
 * the previous page), Volume Down walks FORWARD.
 *
 * Pure core (JVM-testable): maps raw `android.view.KeyEvent` codes to a turn
 * direction. The activity applies `event.repeatCount == 0` throttling itself.
 */
object PageTurnKeys {

    enum class Direction { FORWARD, BACKWARD }

    fun fromKeyCode(code: Int): Direction? = when (code) {
        24 -> Direction.BACKWARD // VOLUME_UP → previous page
        25 -> Direction.FORWARD // VOLUME_DOWN → next page
        87 -> Direction.FORWARD // MEDIA_NEXT (Bluetooth page-turner)
        88 -> Direction.BACKWARD // MEDIA_PREVIOUS
        92 -> Direction.BACKWARD // PAGE_UP
        93 -> Direction.FORWARD // PAGE_DOWN
        21 -> Direction.BACKWARD // DPAD_LEFT
        22 -> Direction.FORWARD // DPAD_RIGHT
        else -> null
    }
}
