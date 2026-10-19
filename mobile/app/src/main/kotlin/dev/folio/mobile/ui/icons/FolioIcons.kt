package dev.folio.mobile.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Folio UI icon family (design system §6): one coherent outline set.
 *
 * Geometry contract:
 *  - 24×24 viewport on the standard grid;
 *  - single 2dp stroke, round caps and joins (path data from Lucide, ISC —
 *    same grid/stroke the design system mandates);
 *  - fill none by default; filled variants exist only where a *filled state*
 *    genuinely encodes selection (e.g. an active bookmark);
 *  - paths are drawn in black and recolored by `Icon(..., tint)`/local content
 *    color at the call site — never hard-code icon colors here;
 *  - interactive call sites must wrap icons in ≥48dp targets (FolioIconButton).
 *
 * Do not add filled Material icons, emoji or text glyphs as functional controls.
 */

private const val STROKE = 2f

private fun folioIcon(
    name: String,
    block: ImageVector.Builder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = "Folio.$name",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply(block).build()

/** One stroked sub-path: round caps/joins, no fill. */
private fun ImageVector.Builder.stroke(d: String, width: Float = STROKE) {
    addPath(
        pathData = addPathNodes(d),
        fill = null,
        stroke = SolidColor(Color(0xFF000000)),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    )
}

/** Filled shape (selection states / tiny dots only). */
private fun ImageVector.Builder.fill(d: String) {
    addPath(
        pathData = addPathNodes(d),
        fill = SolidColor(Color(0xFF000000)),
    )
}

/** Stroked circle on the 24 grid; fractional radii allowed for optical tuning. */
private fun ImageVector.Builder.strokeCircle(cx: Float, cy: Float, r: Float, width: Float = STROKE) {
    stroke(pathCircle(cx, cy, r), width)
}

/** Filled circle (ellipsis dots). */
private fun ImageVector.Builder.fillCircle(cx: Float, cy: Float, r: Float) {
    fill(pathCircle(cx, cy, r))
}

private fun pathCircle(cx: Float, cy: Float, r: Float): String =
    "M ${cx + r} $cy a $r $r 0 1 0 ${r * -2} 0 a $r $r 0 1 0 ${r * 2} 0"

object FolioIcons {

    val ArrowLeft: ImageVector = folioIcon("ArrowLeft") {
        stroke("m12 19-7-7 7-7")
        stroke("M19 12H5")
    }

    val Search: ImageVector = folioIcon("Search") {
        strokeCircle(11f, 11f, 8f)
        stroke("m21 21-4.34-4.34")
    }

    val Plus: ImageVector = folioIcon("Plus") {
        stroke("M5 12h14")
        stroke("M12 5v14")
    }

    val Minus: ImageVector = folioIcon("Minus") {
        stroke("M5 12h14")
    }

    val Close: ImageVector = folioIcon("Close") {
        stroke("M18 6 6 18")
        stroke("m6 6 12 12")
    }

    val Check: ImageVector = folioIcon("Check") {
        stroke("M20 6 9 17l-5-5")
    }

    val MoreVertical: ImageVector = folioIcon("MoreVertical") {
        fillCircle(12f, 12f, 1.1f)
        fillCircle(12f, 5f, 1.1f)
        fillCircle(12f, 19f, 1.1f)
    }

    /** Sort: up/down arrows (library ordering). */
    val Sort: ImageVector = folioIcon("Sort") {
        stroke("m3 16 4 4 4-4")
        stroke("M7 20V4")
        stroke("m21 8-4-4-4 4")
        stroke("M17 4v16")
    }

    val Filter: ImageVector = folioIcon("Filter") {
        stroke("M2 5h20")
        stroke("M6 12h12")
        stroke("M9 19h6")
    }

    val BookOpen: ImageVector = folioIcon("BookOpen") {
        stroke("M12 5v16")
        stroke("M20.001 19A2 2 0 0022 17V5a2 2 0 00-1.999-2L16 3.002A5 5 0 0012 5a5 5 0 00-4-2H4a2 2 0 00-2 2v12a2 2 0 001.999 2H8a5 5 0 014 2 5 5 0 014-2z")
    }

    val Library: ImageVector = folioIcon("Library") {
        stroke("m16 6 4 14")
        stroke("M12 6v14")
        stroke("M8 8v12")
        stroke("M4 4v16")
    }

    val NotebookPen: ImageVector = folioIcon("NotebookPen") {
        stroke("M13.4 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7.4")
        stroke("M2 6h4")
        stroke("M2 10h4")
        stroke("M2 14h4")
        stroke("M2 18h4")
        stroke("M21.378 5.626a1 1 0 1 0-3.004-3.004l-5.01 5.012a2 2 0 0 0-.506.854l-.837 2.87a.5.5 0 0 0 .62.62l2.87-.837a2 2 0 0 0 .854-.506z")
    }

    val Settings: ImageVector = folioIcon("Settings") {
        stroke("M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915", 1.75f)
        strokeCircle(12f, 12f, 2.5f, 1.75f)
    }

    private val BookmarksOutline =
        "M17 3a2 2 0 0 1 2 2v15a1 1 0 0 1-1.496.868l-4.512-2.578a2 2 0 0 0-1.984 0l-4.512 2.578A1 1 0 0 1 5 20V5a2 2 0 0 1 2-2z"

    val Bookmark: ImageVector = folioIcon("Bookmark") {
        stroke(BookmarksOutline)
    }

    /** Filled state — only when a bookmark is actually active/saved-on-page. */
    val BookmarkFilled: ImageVector = folioIcon("BookmarkFilled") {
        fill(BookmarksOutline)
    }

    val PenLine: ImageVector = folioIcon("PenLine") {
        stroke("M13 21h8")
        stroke("M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z")
    }

    val Trash: ImageVector = folioIcon("Trash") {
        stroke("M10 11v6")
        stroke("M14 11v6")
        stroke("M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6")
        stroke("M3 6h18")
        stroke("M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    }

    /** Reading appearance ("Aa"). */
    val Typeface: ImageVector = folioIcon("Typeface") {
        stroke("M12 4v16")
        stroke("M4 7V5a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v2")
        stroke("M9 20h6")
    }

    /** Reading theme (sun behind moon). */
    val SunMoon: ImageVector = folioIcon("SunMoon") {
        stroke("M12 2v2")
        stroke("M14.837 16.385a6 6 0 1 1-7.223-7.222c.624-.147.97.66.715 1.248a4 4 0 0 0 5.26 5.259c.589-.255 1.396.09 1.248.715")
        stroke("M16 12a4 4 0 0 0-4-4")
        stroke("m19 5-1.256 1.256")
        stroke("M20 12h2")
    }

    val ListTree: ImageVector = folioIcon("ListTree") {
        stroke("M8 5h13")
        stroke("M13 12h8")
        stroke("M13 19h8")
        stroke("M3 10a2 2 0 0 0 2 2h3")
        stroke("M3 5v12a2 2 0 0 0 2 2h3")
    }

    val AlignLeft: ImageVector = folioIcon("AlignLeft") {
        stroke("M21 5H3")
        stroke("M15 12H3")
        stroke("M17 19H3")
    }

    val AlignJustify: ImageVector = folioIcon("AlignJustify") {
        stroke("M3 5h18")
        stroke("M3 12h18")
        stroke("M3 19h18")
    }

    val AlertTriangle: ImageVector = folioIcon("AlertTriangle") {
        stroke("m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3")
        stroke("M12 9v4")
        stroke("M12 17h.01")
    }

    val Clock: ImageVector = folioIcon("Clock") {
        strokeCircle(12f, 12f, 10f)
        stroke("M12 6v6l4 2")
    }

    val HardDrive: ImageVector = folioIcon("HardDrive") {
        stroke("M10 16h.01")
        stroke("M2.212 11.577a2 2 0 0 0-.212.896V18a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-5.527a2 2 0 0 0-.212-.896L18.55 5.11A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z")
        stroke("M21.946 12.013H2.054")
        stroke("M6 16h.01")
    }

    val RefreshCcw: ImageVector = folioIcon("RefreshCcw") {
        stroke("M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8")
        stroke("M3 3v5h5")
        stroke("M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16")
        stroke("M16 16h5v5")
    }
}
