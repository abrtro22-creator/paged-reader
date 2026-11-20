package com.example.pagereader

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.lifecycle.ViewModel

/**
 * Хранит открытую книгу и разбивку на страницы.
 * Живёт дольше активити, поэтому при повороте экрана текст не читается заново.
 */
class ReaderViewModel : ViewModel() {

    var document: BookDocument? = null
    var fileName: String? = null

    /** Размеченный текст книги. */
    var pageText: Spanned = SpannableStringBuilder()

    /** Границы страниц в символах исходного текста. */
    var pages: List<PageSpan> = emptyList()

    var currentPage: Int = 0
    var fontSizeSp: Float = 19f

    var pageWidth: Int = 0
    var pageHeight: Int = 0

    val pageCount: Int get() = pages.size

    /** С какого символа начинается текущая страница — по нему восстанавливаем позицию. */
    fun anchorOffset(): Int = pages.getOrNull(currentPage)?.start ?: 0

    fun pageForOffset(offset: Int): Int =
        pages.indexOfFirst { offset >= it.start && offset < it.end }.takeIf { it >= 0 } ?: 0

    fun reset() {
        document = null
        pageText = SpannableStringBuilder()
        pages = emptyList()
        currentPage = 0
    }
}
