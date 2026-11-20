package com.example.pagereader

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/** Каждая страница — отдельный фрагмент, поэтому листание работает «из коробки». */
class PageAdapter(
    activity: FragmentActivity,
    private val model: ReaderViewModel
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = model.pageCount

    override fun createFragment(position: Int): Fragment = PageFragment.newInstance(position)
}
