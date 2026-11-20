package com.example.pagereader

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.pagereader.databinding.FragmentPageBinding
import kotlin.math.abs

/** Одна страница книги: кусок текста, который целиком влезает в экран. */
class PageFragment : Fragment(R.layout.fragment_page) {

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPageBinding.bind(view)
        val position = requireArguments().getInt(ARG_POSITION)

        val model = ViewModelProvider(requireActivity())[ReaderViewModel::class.java]
        val span = model.pages.getOrNull(position)

        binding.pageText.textSize = model.fontSizeSp
        binding.pageText.text = if (span == null) {
            ""
        } else {
            model.pageText.subSequence(span.start, span.end)
        }

        // Касание у правого края — следующая страница, у левого — предыдущая.
        // Слушатель возвращает false, чтобы свайп продолжал работать.
        val slop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var downX = 0f
        var downY = 0f

        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }

                MotionEvent.ACTION_UP -> {
                    val isTap = abs(event.x - downX) < slop && abs(event.y - downY) < slop
                    if (isTap) {
                        val forward = event.x > touched.width / 2f
                        (activity as? PageTurner)?.turnPage(if (forward) 1 else -1)
                    }
                }
            }
            false
        }
    }

    companion object {
        private const val ARG_POSITION = "position"

        fun newInstance(position: Int): PageFragment = PageFragment().apply {
            arguments = Bundle().apply { putInt(ARG_POSITION, position) }
        }
    }
}

/** Реакция на листание: реализуется экраном. */
interface PageTurner {
    fun turnPage(delta: Int)
}
