// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.data

import android.content.Context
import com.timbra.player.enumByName

/**
 * The folder browser's View-As and Sort choice, persisted and app-wide.
 *
 * Deliberately NOT a per-Fragment field: the sort decides the order a folder's QUEUE is built
 * in, so a view-scoped copy meant the folder you tapped played in the chosen order while the
 * folder an Advance-List step rolled into was built in the default one — walking one folder
 * forward and back did not return you to where you were. (It also silently reverted on rotation
 * and on every trip through the back stack.) One setting, read by the browse list and by every
 * queue-building path, makes browse order and play order the same thing by construction.
 *
 * Stored by NAME, not ordinal: an ordinal is positional, so inserting or reordering a [SortOrder]
 * entry would silently reinterpret the saved choice as a different sort — and the fallback below
 * cannot detect it, because the stale ordinal is still in range. The legacy ordinal keys are read
 * once when no name is present, so an in-place update keeps the existing choice.
 */
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
