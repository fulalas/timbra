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
}
