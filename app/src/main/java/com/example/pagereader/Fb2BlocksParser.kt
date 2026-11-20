package com.example.pagereader

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream

/**
 * Разбор FB2 в список блоков.
 *
 * В отличие от «плоского» чтения текста, здесь каждый абзац и заголовок становятся
 * отдельным объектом [Block]: благодаря этому оформление задаётся на этапе отрисовки,
 * а не зашивается в строку пробелами и переводами строк.
 */
internal object Fb2BlocksParser {

    private val SPACES = Regex("\\s+")

    private enum class Kind { TITLE, PARAGRAPH, EPIGRAPH }

    fun parse(bytes: ByteArray): BookDocument {
        val blocks = ArrayList<Block>()

        var bookTitle: String? = null
        var nickName: String? = null
        var firstName = ""
        var lastName = ""

        var inBody = false
        var inDescription = false
        var inTitle = false
        var inEpigraph = false
        var metaField: String? = null

        val buffer = StringBuilder()

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(bytes), null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = localName(parser)
                    when (tag) {
                        // Обложки и картинки в base64 не нужны — пропускаем целиком.
                        "binary" -> skipElement(parser)
                        "description" -> inDescription = true
                        "body" -> inBody = true
                        "title" -> if (inBody) {
                            inTitle = true
                            buffer.setLength(0)
                        }
                        "epigraph" -> if (inBody) inEpigraph = true
                        "p", "v" -> if (inBody && !inTitle) buffer.setLength(0)
                        "empty-line" -> if (inBody) blocks.add(Block.Divider)
                        "book-title", "first-name", "last-name", "nickname" ->
                            if (inDescription) metaField = tag
                        else -> Unit
                    }
                }

                XmlPullParser.TEXT -> {
                    val raw = parser.text
                    if (!raw.isNullOrEmpty()) {
                        if (inBody) {
                            if (buffer.isNotEmpty()) buffer.append(' ')
                            buffer.append(raw)
                        } else if (inDescription && metaField != null) {
                            val value = raw.replace(SPACES, " ").trim()
                            if (value.isNotEmpty()) {
                                when (metaField) {
                                    "book-title" -> bookTitle = value
                                    "first-name" -> firstName = value
                                    "last-name" -> lastName = value
                                    "nickname" -> nickName = value
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = localName(parser)
                    when (tag) {
                        "description" -> inDescription = false
                        "body" -> inBody = false
                        "title" -> if (inBody) {
                            flush(blocks, buffer, Kind.TITLE)
                            inTitle = false
                        }
                        "epigraph" -> if (inBody) inEpigraph = false
                        "p", "v" -> if (inBody && !inTitle) {
                            flush(blocks, buffer, if (inEpigraph) Kind.EPIGRAPH else Kind.PARAGRAPH)
                        }
                        else -> if (inDescription && tag == metaField) metaField = null
                    }
                }
            }
            event = parser.next()
        }

        val author = when {
            firstName.isNotBlank() || lastName.isNotBlank() ->
                listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            else -> nickName
        }

        return BookDocument(title = bookTitle, author = author, blocks = blocks)
    }

    private fun flush(blocks: MutableList<Block>, buffer: StringBuilder, kind: Kind) {
        val text = buffer.toString().replace(SPACES, " ").trim()
        buffer.setLength(0)
        if (text.isEmpty()) return
        blocks.add(
            when (kind) {
                Kind.TITLE -> Block.Title(text)
                Kind.EPIGRAPH -> Block.Epigraph(text)
                Kind.PARAGRAPH -> Block.Paragraph(text)
            }
        )
    }

    private fun localName(parser: XmlPullParser): String =
        parser.name?.substringAfter(':')?.lowercase() ?: ""

    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
