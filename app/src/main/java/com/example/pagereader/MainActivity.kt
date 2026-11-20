package com.example.pagereader

import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.pagereader.databinding.ActivityMainBinding
import com.example.pagereader.databinding.DialogFontSizeBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

class MainActivity : AppCompatActivity(), PageTurner {

    private lateinit var binding: ActivityMainBinding
    private val model: ReaderViewModel by viewModels()

    private val pickBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Доступ сохраняется между запусками: книгу можно открыть снова.
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            loadBook(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets()

        binding.pager.adapter = PageAdapter(this, model)
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                model.currentPage = position
                updateCounter()
            }
        })

        binding.openButton.setOnClickListener { pickBook.launch(MIME_TYPES) }
        binding.settingsButton.setOnClickListener { showFontDialog() }

        // Поворот экрана и изменение размеров окна меняют площадь страницы —
        // текст нужно разложить заново, сохранив место чтения.
        binding.pager.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = (right - left) - binding.pager.paddingLeft - binding.pager.paddingRight
            val height = (bottom - top) - binding.pager.paddingTop - binding.pager.paddingBottom
            if (model.document != null && width > 0 && height > 0 &&
                (width != model.pageWidth || height != model.pageHeight)
            ) {
                repaginate(keepPosition = true)
            }
        }

        if (model.document == null) {
            showEmptyState()
        }
    }

    /**
     * Начиная с Android 15 окно рисуется под системными панелями,
     * поэтому отступы добавляем вручную.
     */
    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    // ------------------------------------------------------------------ загрузка книги

    private fun loadBook(uri: Uri) {
        showLoading()
        lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()

            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val name = displayName(uri)
                    BookLoader.load(openStream(uri), name) to name
                }
            }

            // Индикатор не должен мигать на маленьких файлах.
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed < MIN_LOADING_MS) delay(MIN_LOADING_MS - elapsed)

            outcome
                .onSuccess { (document, name) ->
                    model.document = document
                    model.fileName = name
                    model.pageText = BlockRenderer.render(
                        document.blocks,
                        resources.displayMetrics.density
                    )
                    model.pages = emptyList()
                    model.currentPage = 0
                    repaginate(keepPosition = false)
                }
                .onFailure { showError(it) }
        }
    }

    private fun openStream(uri: Uri): InputStream {
        val stream = when (uri.scheme) {
            "file" -> FileInputStream(
                File(uri.path ?: throw FileNotFoundException("Пустой путь к файлу"))
            )
            else -> contentResolver.openInputStream(uri)
        }
        return stream ?: throw FileNotFoundException("Нет доступа к файлу")
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "Файл"
        var name: String? = null
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) name = cursor.getString(index)
            }
        }
        return name ?: uri.lastPathSegment ?: "Файл"
    }

    // ------------------------------------------------------------------ страницы

    /**
     * Пересчитывает страницы под текущий размер области чтения.
     * [keepPosition] сохраняет место, на котором читатель остановился.
     */
    private fun repaginate(keepPosition: Boolean, attempt: Int = 0) {
        if (model.document == null) return

        // Страницы считаются по реальному размеру области чтения,
        // поэтому перед замером её нужно показать.
        binding.messageState.visibility = View.GONE
        binding.pager.visibility = View.VISIBLE

        // Замер откладываем на следующий кадр: во время layout размеры ещё могут быть старыми.
        binding.pager.post {
            val width = binding.pager.width - binding.pager.paddingLeft - binding.pager.paddingRight
            val height = binding.pager.height - binding.pager.paddingTop - binding.pager.paddingBottom

            if (width <= 0 || height <= 0) {
                if (attempt < MAX_LAYOUT_ATTEMPTS) repaginate(keepPosition, attempt + 1)
                return@post
            }

            val anchor = if (keepPosition) model.anchorOffset() else 0
            val pages = Paginator.split(model.pageText, readingPaint(), width, height)
            if (pages.isEmpty()) return@post

            model.pages = pages
            model.pageWidth = width
            model.pageHeight = height

            val page = model.pageForOffset(anchor)
            // Пересоздаём адаптер, чтобы страницы перечитали размер шрифта.
            binding.pager.adapter = PageAdapter(this, model)
            binding.pager.setCurrentItem(page, false)
            model.currentPage = page

            showContent()
        }
    }

    private fun readingPaint(): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(model.fontSizeSp)
        typeface = Typeface.create(SERIF, Typeface.NORMAL)
        color = ContextCompat.getColor(this@MainActivity, R.color.ink)
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    override fun turnPage(delta: Int) {
        val target = binding.pager.currentItem + delta
        if (target in 0 until model.pageCount) {
            binding.pager.setCurrentItem(target, true)
        }
    }

    // ------------------------------------------------------------------ состояния экрана

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.messageState.visibility = View.GONE
        binding.openButton.isEnabled = false
        binding.settingsButton.isEnabled = false
    }

    private fun showContent() {
        binding.progressBar.visibility = View.GONE
        binding.messageState.visibility = View.GONE
        binding.pager.visibility = View.VISIBLE
        binding.openButton.isEnabled = true
        binding.settingsButton.isEnabled = true
        updateHeader()
        updateCounter()
    }

    private fun showEmptyState() {
        binding.progressBar.visibility = View.GONE
        binding.pager.visibility = View.GONE
        binding.messageState.visibility = View.VISIBLE
        binding.stateIcon.setImageResource(R.drawable.ic_pages)
        binding.stateIcon.alpha = 0.3f
        binding.stateTitle.setText(R.string.empty_title)
        binding.stateSubtitle.setText(R.string.empty_subtitle)
        binding.openButton.isEnabled = true
        binding.settingsButton.isEnabled = true
        updateHeader()
        updateCounter()
    }

    private fun showError(error: Throwable) {
        val (heading, message) = describe(error)

        binding.progressBar.visibility = View.GONE
        binding.pager.visibility = View.GONE
        binding.messageState.visibility = View.VISIBLE
        binding.stateIcon.setImageResource(R.drawable.ic_pages)
        binding.stateIcon.alpha = 0.45f
        binding.stateTitle.text = heading
        binding.stateSubtitle.text = message
        binding.openButton.isEnabled = true
        binding.settingsButton.isEnabled = true

        model.reset()
        binding.bookTitle.setText(R.string.no_book)
        binding.bookMeta.text = ""
        updateCounter()
    }

    private fun describe(error: Throwable): Pair<String, String> = when (error) {
        is BookLoadException -> error.heading to (error.message ?: "Неизвестная ошибка чтения.")
        is FileNotFoundException -> "Файл не найден" to
            "Возможно, его переместили или удалили. Выберите файл заново."
        is SecurityException -> "Нет доступа к файлу" to
            "Приложение не может прочитать этот файл. Откройте его через кнопку «Открыть»."
        is OutOfMemoryError -> "Слишком большой файл" to
            "Не хватило памяти, чтобы разложить текст по страницам."
        else -> "Не удалось открыть файл" to (error.message ?: error.javaClass.simpleName)
    }

    private fun updateHeader() {
        val document = model.document
        if (document == null) {
            binding.bookTitle.setText(R.string.app_name)
            binding.bookMeta.setText(R.string.no_book)
            return
        }

        binding.bookTitle.text = model.fileName ?: getString(R.string.app_name)
        val details = listOfNotNull(document.title, document.author).filter { it.isNotBlank() }
        binding.bookMeta.text = if (details.isEmpty()) {
            getString(R.string.plain_text)
        } else {
            details.joinToString(" • ")
        }
    }

    private fun updateCounter() {
        binding.pageCounter.text = if (model.pageCount == 0) {
            getString(R.string.page_counter_empty)
        } else {
            getString(R.string.page_counter, model.currentPage + 1, model.pageCount)
        }
    }

    // ------------------------------------------------------------------ размер шрифта

    private fun showFontDialog() {
        val dialogBinding = DialogFontSizeBinding.inflate(layoutInflater)
        val seek = dialogBinding.fontSeek
        seek.progress = (model.fontSizeSp - MIN_FONT).toInt()

        fun refreshPreview() {
            val size = MIN_FONT.toInt() + seek.progress
            dialogBinding.fontPreview.textSize = size.toFloat()
            dialogBinding.fontValue.text = getString(R.string.font_size_value, size)
        }
        refreshPreview()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) =
                refreshPreview()

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.font_size)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.done) { _, _ ->
                val size = (MIN_FONT + seek.progress).toFloat()
                if (size != model.fontSizeSp) {
                    model.fontSizeSp = size
                    if (model.pageCount > 0) repaginate(keepPosition = true)
                }
            }
            .show()
    }

    private companion object {
        const val SERIF = "serif"
        const val MIN_FONT = 12f
        const val MIN_LOADING_MS = 280L
        const val MAX_LAYOUT_ATTEMPTS = 12

        val MIME_TYPES = arrayOf(
            "text/plain",
            "text/*",
            "application/fb2",
            "application/x-fictionbook+xml",
            "application/xml",
            "text/xml",
            "application/octet-stream"
        )
    }
}
