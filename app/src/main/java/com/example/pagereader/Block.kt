package com.example.pagereader

/** Смысловой блок документа: из них собирается текст книги. */
sealed interface Block {

    /** Заголовок главы или название произведения. */
    data class Title(val text: String) : Block

    /** Обычный абзац. */
    data class Paragraph(val text: String) : Block

    /** Эпиграф или цитата — выводится курсивом с отступом. */
    data class Epigraph(val text: String) : Block

    /** Разделитель между частями текста. */
    data object Divider : Block
}

/**
 * Разобранная книга. Текст хранится не одной строкой, а списком блоков —
 * так проще менять оформление (отступы, курсив, выравнивание) независимо от содержимого.
 */
data class BookDocument(
    val title: String?,
    val author: String?,
    val blocks: List<Block>
)
