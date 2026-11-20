package com.example.pagereader

import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Границы одной страницы в символах исходного текста.
 * [end] не включается в диапазон — так же, как в String.substring.
 */
data class PageSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/**
 * Режет размеченный текст на страницы.
 *
 * Сначала текст раскладывается в [StaticLayout] той же ширины и с тем же шрифтом,
 * что и у страницы, а затем строки группируются в страницы по высоте.
 * Границы получаются в виде диапазонов символов, поэтому текст не дублируется
 * в памяти: страница — это просто кусок исходной строки.
 */
object Paginator {

    /** Небольшой запас, чтобы последняя строка не обрезалась на границе. */
    private const val BOTTOM_MARGIN = 8

    /** Должно совпадать с android:lineSpacingMultiplier у TextView. */
    const val LINE_SPACING = 1.5f

    fun split(text: Spanned, paint: TextPaint, width: Int, height: Int): List<PageSpan> {
        if (width <= 0 || height <= 0 || text.isEmpty()) return emptyList()

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

        val pageHeight = height - BOTTOM_MARGIN
        val pages = ArrayList<PageSpan>()

        var line = 0
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line)
            var last = line
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= pageHeight) {
                last++
            }

            val start = layout.getLineStart(line)
            var end = layout.getLineEnd(last)

            // Хвостовые переводы строк на странице не нужны — они «съедают» место.
            while (end > start && text[end - 1] == '\n') end--

            if (end > start) pages.add(PageSpan(start, end))
            line = last + 1
        }

        return pages
    }
}
