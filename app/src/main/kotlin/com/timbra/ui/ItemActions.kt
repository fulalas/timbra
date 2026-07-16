package com.timbra.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.timbra.R
import com.timbra.data.model.Track
import com.timbra.player.QueueItem

/**
 * Long-press context menu for a track or folder: Enqueue (play next, FIFO),
 * Info / Tags, Share, and Delete (removes the file from storage).
 */
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
        AlertDialog.Builder(ctx)
            .setTitle(label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> enqueue(fragment, tracks)
                    1 -> showInfo(fragment, tracks)
                    2 -> share(fragment, tracks)
                    3 -> confirmDelete(fragment, tracks)
                }
            }
            .show()
    }

    /** Queue-screen context menu: Info / Share / Remove (removes from queue, not from disk). */
    fun showForQueue(fragment: Fragment, item: QueueItem, onRemove: () -> Unit) {
        val ctx = fragment.requireContext()
        val options = arrayOf(
            ctx.getString(R.string.menu_info),
            ctx.getString(R.string.menu_share),
            ctx.getString(R.string.menu_remove),
        )
        AlertDialog.Builder(ctx)
            .setTitle(item.title.ifBlank { item.filePath.substringAfterLast('/') })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showQueueInfo(fragment, item)
                    1 -> shareUris(fragment, listOf(queueUri(item)))
                    2 -> onRemove()
                }
            }
            .show()
    }

    private fun queueUri(item: QueueItem): Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, item.mediaId)

    private fun showQueueInfo(fragment: Fragment, item: QueueItem) {
        infoDialog(fragment, buildString {
            appendLine("Title: ${item.title.ifBlank { item.filePath.substringAfterLast('/') }}")
            appendLine("Artist: ${item.artist.ifBlank { "—" }}")
            appendLine("Album: ${item.album.ifBlank { "—" }}")
            appendLine("Path: ${item.filePath}")
        })
    }

    private fun infoDialog(fragment: Fragment, body: String) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.menu_info)
            .setMessage(body.trim())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun enqueue(fragment: Fragment, tracks: List<Track>) {
        (fragment.requireActivity() as MainActivity).player.enqueueNext(tracks)
        val label = tracks.firstOrNull()?.let {
            if (tracks.size == 1) it.displayTitle else "${tracks.size} songs"
        } ?: return
        Toast.makeText(
            fragment.requireContext(),
            fragment.getString(R.string.enqueued, label),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showInfo(fragment: Fragment, tracks: List<Track>) {
        val t = tracks.first()
        infoDialog(fragment, buildString {
            appendLine("Title: ${t.displayTitle}")
            appendLine("Artist: ${t.artist.ifBlank { "—" }}")
            appendLine("Album: ${t.album.ifBlank { "—" }}")
            if (t.trackNo > 0) appendLine("Track: ${t.trackNo}")
            appendLine("Duration: ${Format.clock(t.durationMs)}")
            appendLine("Path: ${t.path}")
            if (tracks.size > 1) appendLine("\n(${tracks.size} files selected)")
        })
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
        else ctx.getString(R.string.delete_confirm_many, tracks.size)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.menu_delete)
            .setMessage(msg)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                (fragment.requireActivity() as MainActivity).requestDelete(tracks.map { it.uri })
            }
            .show()
    }
}
