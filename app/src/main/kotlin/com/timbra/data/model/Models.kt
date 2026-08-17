// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.data.model

import android.net.Uri

data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNo: Int,
    val discNo: Int,
    val dateAddedSec: Long,
    val path: String,
    /**
     * Derived once at construction, NOT a computed getter: the filename comparator selects on
     * it and `compareBy` evaluates the selector on both operands of every comparison, so a
     * getter allocated a fresh substring O(n log n) times per sort (on the main thread for
     * folder advances). Defaulted from [path], so callers never pass it.
     */
    val fileName: String = path.substringAfterLast('/'),
) {
    val displayTitle: String get() = title.ifBlank { fileName }
}

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

class FolderNode(
    val name: String,
    val path: String,
) {
    val subFolders: MutableMap<String, FolderNode> = linkedMapOf()
    val tracks: MutableList<Track> = mutableListOf()

    val childFolders: List<FolderNode> get() = subFolders.values.toList()

    var totalTrackCount: Int = 0
        internal set
}
