package com.timbra.data

import android.content.Context

/**
 * The folder browser's View-As and Sort choice, persisted and app-wide.
 *
 * Deliberately NOT a per-Fragment field: the sort decides the order a folder's QUEUE is built
 * in, so a view-scoped copy meant the folder you tapped played in the chosen order while the
 * folder an Advance-List step rolled into was built in the default one — walking one folder
 * forward and back did not return you to where you were. (It also silently reverted on rotation
 * and on every trip through the back stack.) One setting, read by the browse list and by every
 * queue-building path, makes browse order and play order the same thing by construction.
 */
class FolderSort(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("folder_sort", Context.MODE_PRIVATE)

    var sortOrder: SortOrder
        get() = SortOrder.entries.getOrElse(
            prefs.getInt(KEY_SORT, SortDefaults.FOLDER_SONGS.ordinal),
        ) { SortDefaults.FOLDER_SONGS }
        set(value) = prefs.edit().putInt(KEY_SORT, value.ordinal).apply()

    var viewAs: ViewAs
        get() = ViewAs.entries.getOrElse(
            prefs.getInt(KEY_VIEW, SortDefaults.FOLDER_VIEW.ordinal),
        ) { SortDefaults.FOLDER_VIEW }
        set(value) = prefs.edit().putInt(KEY_VIEW, value.ordinal).apply()

    private companion object {
        const val KEY_SORT = "folder_sort_order"
        const val KEY_VIEW = "folder_view_as"
    }
}
