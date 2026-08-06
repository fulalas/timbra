// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.data.model

import android.net.Uri

/** A single playable audio file backed by MediaStore. */
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNo: Int,
    /** Disc number (1-based) when the tags carry one, else 0 — the primary key of the album
     *  order, so a multi-disc album doesn't interleave its discs. */
    val discNo: Int,
    val dateAddedSec: Long,
    /** Absolute file path (or best available), used for folder-tree grouping and filename sort. */
    val path: String,
    /**
     * Derived once at construction, NOT a computed getter: the filename comparator selects on
     * it and `compareBy` evaluates the selector on both operands of every comparison, so a
     * getter allocated a fresh substring O(n log n) times per sort (on the main thread for
     * folder advances). Defaulted from [path], so callers never pass it.
     */
    val fileName: String = path.substringAfterLast('/'),
) {
    /** Title, falling back to the file name when tags are missing. */
    val displayTitle: String get() = title.ifBlank { fileName }
}

/** A browsable library category shown on the Library screen. */
data class Category(
    val kind: CategoryKind,
    val iconRes: Int,
    val titleRes: Int,
)

enum class CategoryKind { FOLDERS, ALBUMS, ARTISTS, SONGS, GENRES, PLAYLISTS, QUEUE }

data class Album(val id: Long, val title: String, val artist: String, val trackCount: Int)
data class Artist(val id: Long, val name: String, val trackCount: Int)
data class Genre(val id: Long, val name: String, val trackCount: Int)
data class Playlist(val id: Long, val name: String, val trackCount: Int)

/** A node in the folder hierarchy derived from track paths. */
class FolderNode(
    val name: String,
    val path: String,
) {
    val subFolders: MutableMap<String, FolderNode> = linkedMapOf()
    val tracks: MutableList<Track> = mutableListOf()

    val childFolders: List<FolderNode> get() = subFolders.values.toList()

    /**
     * Total tracks in this node and all descendants.
     *
     * Filled in by [com.timbra.data.FolderTreeBuilder.build] in one explicit pass once the tree
     * is complete — NOT a `by lazy`. Both [tracks] and [subFolders] are populated after the node
     * is constructed, so a lazy value read mid-build would memoise a partial count and freeze it
     * for the object's lifetime, with no way to invalidate it.
     */
    var totalTrackCount: Int = 0
        internal set
}
