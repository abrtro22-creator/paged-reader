package com.example.pagereader

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Ошибка чтения, которую можно показать пользователю. */
class BookLoadException(
    message: String,
    val heading: String = "Не удалось открыть файл"
) : Exception(message)

/** Читает файл и превращает его в [BookDocument]. */
object BookLoader {

    private const val SIZE_LIMIT = 20 * 1024 * 1024
    private val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    private val BLANK_LINE = Regex("\n{2,}")

    fun load(stream: InputStream, fileName: String): BookDocument {
        val bytes = drain(stream)
        if (bytes.isEmpty()) {
            throw BookLoadException("В файле нет ни одного байта.", "Файл пустой")
        }

        val document = if (looksLikeFb2(bytes, fileName)) parseFb2(bytes) else parsePlainText(bytes)

        if (document.blocks.isEmpty()) {
            throw BookLoadException("В файле нет текста, который можно показать.", "Файл пустой")
        }
        return document
    }

    private fun drain(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        input.use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                total += read
                if (total > SIZE_LIMIT) {
                    throw BookLoadException(
                        "Документ больше 20 МБ — столько страниц не осилит ни один читатель.",
                        "Файл слишком большой"
                    )
                }
                out.write(chunk, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun looksLikeFb2(bytes: ByteArray, fileName: String): Boolean {
        if (fileName.endsWith(".fb2", ignoreCase = true)) return true
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return head.contains("FictionBook", ignoreCase = true)
    }

    private fun parseFb2(bytes: ByteArray): BookDocument = try {
        Fb2BlocksParser.parse(bytes)
    } catch (e: BookLoadException) {
        throw e
    } catch (e: Exception) {
        throw BookLoadException(
            "Книга повреждена или это не FB2. Попробуйте другой файл.",
            "Не удалось разобрать FB2"
        )
    }

    private fun parsePlainText(bytes: ByteArray): BookDocument {
        val text = String(bytes, guessCharset(bytes))
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val blocks = text.split(BLANK_LINE)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Block.Paragraph(it) }

        return BookDocument(title = null, author = null, blocks = blocks)
    }

    /**
     * Кодировка определяется по BOM, затем строгой проверкой UTF-8.
     * Если байты не являются корректным UTF-8 — это почти наверняка windows-1251.
     */
    private fun guessCharset(bytes: ByteArray): Charset {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Charsets.UTF_16LE
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Charsets.UTF_16BE
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            Charsets.UTF_8
        } catch (e: CharacterCodingException) {
            WINDOWS_1251
        }
    }
}
