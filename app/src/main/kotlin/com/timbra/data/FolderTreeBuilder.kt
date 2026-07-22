package com.timbra.data

import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track

/**
 * Builds a virtual folder hierarchy from track file paths (no MANAGE_EXTERNAL_STORAGE
 * needed — paths come from MediaStore). Directories that contain only a single child
 * directory are collapsed so the tree starts at meaningful roots.
 */
object FolderTreeBuilder {

    fun build(tracks: List<Track>): FolderNode {
        val root = FolderNode(name = "/", path = "")
        for (track in tracks) {
            val dir = track.path.substringBeforeLast('/', missingDelimiterValue = "")
            if (dir.isEmpty()) {
                root.tracks.add(track)
                continue
            }
            val segments = dir.split('/').filter { it.isNotEmpty() }
            var node = root
            val builtPath = StringBuilder()
            for (seg in segments) {
                builtPath.append('/').append(seg)
                node = node.subFolders.getOrPut(seg) {
                    FolderNode(name = seg, path = builtPath.toString())
                }
            }
            node.tracks.add(track)
        }
        return collapseSingleChildChains(root)
    }

    /** Collapse leading chains like /storage/emulated/0/Music -> Music root. */
    private fun collapseSingleChildChains(root: FolderNode): FolderNode {
        var node = root
        while (node.tracks.isEmpty() && node.subFolders.size == 1) {
            node = node.subFolders.values.first()
        }
        return node
    }

    /** Locate a node by its absolute [path] within [root] (DFS). */
    fun find(root: FolderNode, path: String): FolderNode? {
        if (path.isBlank() || path == root.path) return root
        val stack = ArrayDeque<FolderNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            if (n.path == path) return n
            n.subFolders.values.forEach { stack.addLast(it) }
        }
        return null
    }

    /** All tracks in [node] and its descendants (used by the flat view). */
    fun flatten(node: FolderNode): List<Track> {
        val out = ArrayList<Track>()
        out.addAll(node.tracks)
        node.subFolders.values.forEach { out.addAll(flatten(it)) }
        return out
    }

    /** Child folders of [node] in the app's ONE canonical folder order — shared by the
     *  Folders screen and the playback traversal so they can never drift apart. */
    fun sortedChildren(node: FolderNode): List<FolderNode> =
        node.childFolders.sortedBy { it.name.lowercase() }

    /**
     * The library as one flat, depth-first list of the folders that DIRECTLY contain
     * songs — THE traversal order for every folder jump/advance. Container folders
     * (only subfolders, no loose files) are not entries; a folder entry stands for its
     * own tracks only, its subfolders being entries of their own.
     */
    fun songFolders(root: FolderNode): List<FolderNode> {
        val out = ArrayList<FolderNode>()
        fun walk(node: FolderNode) {
            if (node.tracks.isNotEmpty()) out.add(node)
            sortedChildren(node).forEach { walk(it) }
        }
        walk(root)
        return out
    }

    /**
     * The (previous, next) song-folders around the first of [anchorPaths] that resolves to an
     * entry in the flat traversal [folders]; nulls at the edges or when none resolves. [anchorPaths]
     * is tried in priority order (e.g. the UI passes its folderContext then the playing file's
     * directory; the service passes just that directory) — the single source of truth for
     * Advance-List folder stepping, shared by [com.timbra.ui.MainActivity] and
     * [com.timbra.player.PlaybackService].
     */
    fun neighbourFolders(
        folders: List<FolderNode>,
        vararg anchorPaths: String?,
    ): Pair<FolderNode?, FolderNode?> {
        val idx = anchorPaths.asSequence()
            .filter { !it.isNullOrEmpty() }
            .map { anchor -> folders.indexOfFirst { it.path == anchor } }
            .firstOrNull { it >= 0 } ?: return null to null
        return folders.getOrNull(idx - 1) to folders.getOrNull(idx + 1)
    }
}
