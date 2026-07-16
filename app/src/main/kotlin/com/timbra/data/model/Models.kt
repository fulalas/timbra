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
    val dateAddedSec: Long,
    /** Absolute file path (or best available), used for folder-tree grouping and filename sort. */
    val path: String,
) {
    val fileName: String get() = path.substringAfterLast('/')

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

    /** Total tracks in this node and all descendants (computed once, lazily). */
    val totalTrackCount: Int by lazy {
        tracks.size + subFolders.values.sumOf { it.totalTrackCount }
    }
}
