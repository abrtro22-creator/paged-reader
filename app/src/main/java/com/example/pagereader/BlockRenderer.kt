package com.example.pagereader

import android.graphics.Typeface
import android.text.Layout.Alignment
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/**
 * Собирает блоки в размеченный текст: заголовки по центру и крупнее,
 * абзацы с красной строкой, эпиграфы курсивом с двойным отступом.
 */
object BlockRenderer {

    fun render(blocks: List<Block>, density: Float): Spanned {
        val out = SpannableStringBuilder()
        val indent = (20 * density).toInt()

        blocks.forEach { block ->
            when (block) {
                is Block.Title -> {
                    if (out.isNotEmpty()) out.append("\n\n")
                    val start = out.length
                    out.append(block.text)
                    out.bold(start, out.length)
                    out.span(start, out.length, RelativeSizeSpan(1.18f))
                    out.span(start, out.length, AlignmentSpan.Standard(Alignment.ALIGN_CENTER))
                }

                is Block.Paragraph -> {
                    if (out.isNotEmpty()) out.append("\n\n")
                    val start = out.length
                    out.append(block.text)
                    out.span(start, out.length, LeadingMarginSpan.Standard(indent, 0))
                }

                is Block.Epigraph -> {
                    if (out.isNotEmpty()) out.append("\n\n")
                    val start = out.length
                    out.append(block.text)
                    out.span(start, out.length, StyleSpan(Typeface.ITALIC))
                    out.span(start, out.length, LeadingMarginSpan.Standard(indent * 2, indent * 2))
                }

                Block.Divider -> {
                    if (out.isNotEmpty()) out.append("\n\n")
                    // Неразрывные пробелы: разделитель не должен разрываться между страницами.
                    out.append("∗\u00A0∗\u00A0∗")
                }
            }
        }

        return out
    }

    private fun SpannableStringBuilder.bold(from: Int, to: Int) =
        span(from, to, StyleSpan(Typeface.BOLD))

    private fun SpannableStringBuilder.span(from: Int, to: Int, what: Any) {
        if (to > from) setSpan(what, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
