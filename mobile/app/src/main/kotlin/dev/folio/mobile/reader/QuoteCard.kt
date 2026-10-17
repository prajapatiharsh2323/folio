package dev.folio.mobile.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import dev.folio.mobile.core.annotation.QuoteCardContent
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.HighlightColor
import java.io.File

/**
 * Quote-card share image (R3 deliverable): paints a shareable PNG card from a
 * schema-1 [Annotation] and hands it to the system share sheet via FileProvider.
 *
 * Palette: charcoal plate #23242F + violet bookmark brand #715CFC (the Folio
 * mark), text tokens from the dark theme. Content (clamps/wraps) comes from the
 * pure [QuoteCardContent].
 */
object QuoteCard {

    private const val CARD_WIDTH = 1200
    private const val CARD_HEIGHT = 630
    private const val BRAND_VIOLET = 0xFF715CFC.toInt()
    private const val CHARCOAL = 0xFF23242F.toInt()
    private const val TEXT = 0xFFE6E3DC.toInt()
    private const val TEXT_DIM = 0xFF9A96A6.toInt()

    /** PWA HIGHLIGHT_COLORS, opaque, used as the color band. */
    private fun bandArgb(color: HighlightColor?): Int = when (color) {
        HighlightColor.Green -> 0xFFB7E4B0.toInt()
        HighlightColor.Blue -> 0xFFA9D1F5.toInt()
        HighlightColor.Pink -> 0xFFF5B7CC.toInt()
        else -> 0xFFFFE58A.toInt()
    }

    suspend fun share(
        context: Context,
        annotation: Annotation,
        bookTitle: String?,
        author: String?
    ): Uri? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val layout = QuoteCardContent.of(
            quote = annotation.quote,
            note = annotation.note,
            bookTitle = bookTitle,
            author = author,
            colorHex = bandArgb(annotation.color).toLong() and 0xFFFFFFFFL
        )
        val bitmap = render(layout)
        val dir = File(context.cacheDir, "quote-card").apply { mkdirs() }
        val file = File(dir, "quote-${annotation.id.substringBefore('-')}.png")
        file.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return@withContext null
        }
        bitmap.recycle()
        FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
    }

    /**
     * Visible for JVM-independent inspection of paint math; not unit-testable
     * (android.graphics).
     */
    fun render(layout: QuoteCardContent.QuoteCardLayout): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(CHARCOAL)

        // Left accent: highlight-color band over the violet brand edge.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = BRAND_VIOLET
        canvas.drawRoundRect(48f, 48f, 62f, (CARD_HEIGHT - 48).toFloat(), 7f, 7f, paint)
        paint.color = layout.colorArgb.toInt()
        canvas.drawRoundRect(66f, 48f, 74f, (CARD_HEIGHT - 48).toFloat(), 4f, 4f, paint)

        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = 44f
        }
        var y = 120f
        var shown = 0
        for (line in layout.quoteLines) {
            if (shown >= 8) break
            canvas.drawText(line, 100f, y, quotePaint)
            y += quotePaint.textSize * 1.28f
            shown++
        }

        if (layout.noteLines.isNotEmpty()) {
            val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT_DIM
                textSize = 30f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
            canvas.drawText("— " + layout.noteLines.first(), 100f, y + 14f, notePaint)
            y += notePaint.textSize * 1.7f
        }

        // Attribution row + wordmark pinned to the bottom.
        val attribution = listOfNotNull(layout.bookTitle, layout.author)
            .joinToString(" · ")
        if (attribution.isNotEmpty()) {
            val attrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = TEXT_DIM
                textSize = 26f
            }
            canvas.drawText(attribution, 100f, (CARD_HEIGHT - 64).toFloat(), attrPaint)
        }
        val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BRAND_VIOLET
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 30f
        }
        val bounds = android.graphics.Rect()
        markPaint.getTextBounds("— Folio", 0, 7, bounds)
        canvas.drawText(
            "— Folio",
            (CARD_WIDTH - 72 - bounds.width()).toFloat(),
            (CARD_HEIGHT - 64).toFloat(),
            markPaint
        )
        return bitmap
    }
}
