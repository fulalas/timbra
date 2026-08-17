// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.timbra.R
import com.timbra.data.MediaRepository
import com.timbra.data.model.Track
import com.timbra.player.QueueItem
import com.timbra.ui.dialogs.Dialogs

object ItemActions {

    fun show(fragment: Fragment, label: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val ctx = fragment.requireContext()
        val options = arrayOf(
            ctx.getString(R.string.menu_enqueue),
            ctx.getString(R.string.menu_info),
            ctx.getString(R.string.menu_share),
            ctx.getString(R.string.menu_delete),
        )
        Dialogs.actions(ctx, label, options) { which ->
            when (which) {
                0 -> enqueue(fragment, tracks)
                1 -> showInfo(fragment, tracks)
                2 -> share(fragment, tracks)
                3 -> confirmDelete(fragment, tracks)
            }
        }
    }

    fun showForQueue(fragment: Fragment, item: QueueItem, onRemove: () -> Unit) {
        val ctx = fragment.requireContext()
        val options = arrayOf(
            ctx.getString(R.string.menu_info),
            ctx.getString(R.string.menu_share),
            ctx.getString(R.string.menu_remove),
        )
        Dialogs.actions(ctx, item.displayTitle, options) { which ->
            when (which) {
                0 -> infoDialog(
                    fragment,
                    infoBody(ctx, item.displayTitle, item.artist, item.album, item.filePath),
                )
                1 -> shareUris(fragment, listOf(MediaRepository.trackUri(item.mediaId)))
                2 -> onRemove()
            }
        }
    }

    private fun infoBody(
        ctx: Context,
        title: String,
        artist: String,
        album: String,
        path: String,
        trackNo: Int = 0,
        discNo: Int = 0,
        durationMs: Long = 0,
    ): String = buildString {
        val none = ctx.getString(R.string.info_none)
        appendLine(ctx.getString(R.string.info_title, title))
        appendLine(ctx.getString(R.string.info_artist, artist.ifBlank { none }))
        appendLine(ctx.getString(R.string.info_album, album.ifBlank { none }))
        if (discNo > 0) appendLine(ctx.getString(R.string.info_disc, discNo))
        if (trackNo > 0) appendLine(ctx.getString(R.string.info_track, trackNo))
        if (durationMs > 0) appendLine(ctx.getString(R.string.info_duration, Format.clock(durationMs)))
        appendLine(ctx.getString(R.string.info_path, path))
    }

    private fun infoDialog(fragment: Fragment, body: String) {
        Dialogs.message(fragment.requireContext(), R.string.menu_info, body.trim())
    }

    private fun enqueue(fragment: Fragment, tracks: List<Track>) {
        fragment.player.enqueueNext(tracks)
        val label = tracks.firstOrNull()?.let {
            if (tracks.size == 1) it.displayTitle
            else fragment.resources.getQuantityString(R.plurals.n_songs, tracks.size, tracks.size)
        } ?: return
        Toast.makeText(
            fragment.requireContext(),
            fragment.getString(R.string.enqueued, label),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showInfo(fragment: Fragment, tracks: List<Track>) {
        val t = tracks.first()
        val body = infoBody(
            fragment.requireContext(),
            t.displayTitle, t.artist, t.album, t.path, t.trackNo, t.discNo, t.durationMs,
        )
        val suffix = if (tracks.size > 1) {
            "\n" + fragment.resources.getQuantityString(
                R.plurals.n_files_selected, tracks.size, tracks.size,
            )
        } else ""
        infoDialog(fragment, body + suffix)
    }

    private fun share(fragment: Fragment, tracks: List<Track>) = shareUris(fragment, tracks.map { it.uri })

    private fun shareUris(fragment: Fragment, uriList: List<Uri>) {
        val uris = ArrayList(uriList)
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = "audio/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fragment.startActivity(Intent.createChooser(intent, fragment.getString(R.string.menu_share)))
    }

    private fun confirmDelete(fragment: Fragment, tracks: List<Track>) {
        val ctx = fragment.requireContext()
        val msg = if (tracks.size == 1) ctx.getString(R.string.delete_confirm)
        else ctx.resources.getQuantityString(R.plurals.delete_confirm_many, tracks.size, tracks.size)
        Dialogs.confirm(ctx, R.string.menu_delete, msg, R.string.menu_delete) {
            (fragment.requireActivity() as MainActivity).requestDelete(tracks.map { it.uri })
        }
    }
}
