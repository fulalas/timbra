// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.data

import android.content.Context
import com.timbra.player.enumByName

class FolderSort(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("folder_sort", Context.MODE_PRIVATE)

    var sortOrder: SortOrder
        get() = prefs.getString(KEY_SORT, null)?.let { enumByName(it, SortDefaults.FOLDER_SONGS) }
            ?: SortOrder.entries.getOrElse(
                prefs.getInt(KEY_SORT_LEGACY, SortDefaults.FOLDER_SONGS.ordinal),
            ) { SortDefaults.FOLDER_SONGS }
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    var viewAs: ViewAs
        get() = prefs.getString(KEY_VIEW, null)?.let { enumByName(it, SortDefaults.FOLDER_VIEW) }
            ?: ViewAs.entries.getOrElse(
                prefs.getInt(KEY_VIEW_LEGACY, SortDefaults.FOLDER_VIEW.ordinal),
            ) { SortDefaults.FOLDER_VIEW }
        set(value) = prefs.edit().putString(KEY_VIEW, value.name).apply()

    private companion object {
        // Names, written since 0.8.0.
        const val KEY_SORT = "folder_sort_order_name"
        const val KEY_VIEW = "folder_view_as_name"

        // Ordinals, written before 0.8.0 — read once, to migrate an existing install.
        const val KEY_SORT_LEGACY = "folder_sort_order"
        const val KEY_VIEW_LEGACY = "folder_view_as"
    }
}
