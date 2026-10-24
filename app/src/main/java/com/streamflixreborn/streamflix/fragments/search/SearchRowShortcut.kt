package com.streamflixreborn.streamflix.fragments.search

/**
 * Stable poster-row navigation shared by the TV remote's per-row shortcut.
 * It does not change grid items, focusable card types or poster ordering.
 */
internal object SearchRowShortcut {
    fun rowOf(position: Int, columns: Int): Int =
        if (position < 0 || columns <= 0) -1 else position / columns

    fun lastIndex(row: Int, columns: Int, itemCount: Int): Int {
        if (row < 0 || columns <= 0 || itemCount <= 0) return -1
        val start = row.toLong() * columns
        if (start >= itemCount) return -1
        return minOf(itemCount - 1, (start + columns - 1).toInt())
    }

    fun isRowEnd(position: Int, columns: Int, itemCount: Int): Boolean =
        position >= 0 && position < itemCount &&
            position == lastIndex(rowOf(position, columns), columns, itemCount)

    fun clampToAvailable(position: Int, itemCount: Int): Int =
        if (itemCount <= 0) -1 else position.coerceIn(0, itemCount - 1)
}
