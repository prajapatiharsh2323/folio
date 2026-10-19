package dev.folio.mobile.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Folio layout tokens (design system §4–§5). Values come from the 4dp scale;
 * screens consume these objects instead of re-declaring dp literals so the
 * rhythm stays consistent.
 */
object FolioSpacing {
    val x1 = 4.dp    // icon/text micro gap
    val x2 = 8.dp    // label-to-value gap
    val x3 = 12.dp   // compact row padding
    val x4 = 16.dp   // standard content spacing
    val x5 = 20.dp   // phone horizontal content inset
    val x6 = 24.dp   // section separation, sheet padding
    val x8 = 32.dp   // large section break
    val x10 = 40.dp  // empty-state composition
    val x12 = 48.dp  // hero spacing
}

object FolioDimensions {
    val touchTarget = 48.dp            // minimum interactive area
    val icon = 24.dp                   // icon artwork in the drawing grid
    val iconSmall = 20.dp              // dense metadata icons only
    val searchHeight = 50.dp
    val buttonHeight = 50.dp
    val chipHeight = 32.dp
    val settingsRowMinHeight = 56.dp
    val bookCoverWidth = 56.dp
    val bookCoverHeight = 78.dp
    val divider = 1.dp
    val progressTrack = 3.dp
    val screenInset = 20.dp            // default horizontal content inset
}

object FolioRadii {
    val cover = 4.dp                   // book covers/thumbnails
    val control = 8.dp                 // chips, compact controls
    val field = 12.dp                  // search/text fields, buttons
    val card = 14.dp                   // grouped content only
    val sheet = 24.dp                  // modal bottom sheets
    val pill = 999.dp
}
