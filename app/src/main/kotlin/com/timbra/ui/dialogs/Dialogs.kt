package com.timbra.ui.dialogs

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.timbra.R
import com.timbra.data.SortOrder
import com.timbra.data.ViewAs

object Dialogs {

    fun showSort(context: Context, options: List<SortOrder>, current: SortOrder, onPick: (SortOrder) -> Unit) =
        showChoice(context, R.string.menu_sort, options, { it.labelRes }, current, onPick)

    fun showViewAs(context: Context, current: ViewAs, onPick: (ViewAs) -> Unit) =
        showChoice(context, R.string.menu_view_as, ViewAs.entries, { it.labelRes }, current, onPick)

    /** Single-choice dialog with a Cancel button; picks and dismisses on selection. */
    private fun <T> showChoice(
        context: Context,
        @StringRes titleRes: Int,
        options: List<T>,
        @StringRes labelRes: (T) -> Int,
        current: T,
        onPick: (T) -> Unit,
    ) {
        val labels = options.map { context.getString(labelRes(it)) }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, options.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                onPick(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
