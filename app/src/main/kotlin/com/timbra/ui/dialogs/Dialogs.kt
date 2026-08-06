// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui.dialogs

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.timbra.R
import com.timbra.data.SortOrder
import com.timbra.data.ViewAs

/**
 * Every dialog the app shows, so their look and button order stay consistent. The confirmation
 * and message dialogs were hand-built at five call sites and had already diverged (one with no
 * title, one with the buttons reversed).
 */
object Dialogs {

    fun showSort(context: Context, current: SortOrder, onPick: (SortOrder) -> Unit) =
        showChoice(context, R.string.menu_sort, SortOrder.entries, { it.labelRes }, current, onPick)

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

    /** Destructive-action confirmation: Cancel, then the named action. */
    fun confirm(
        context: Context,
        @StringRes titleRes: Int,
        message: String,
        @StringRes confirmRes: Int,
        onConfirm: () -> Unit,
    ) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(confirmRes) { _, _ -> onConfirm() }
            .show()
    }

    /** Read-only message with a single OK. */
    fun message(context: Context, @StringRes titleRes: Int, body: String) {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Pick one action from [options] (a long-press context menu). */
    fun actions(context: Context, title: String, options: Array<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(options) { _, which -> onPick(which) }
            .show()
    }
}
