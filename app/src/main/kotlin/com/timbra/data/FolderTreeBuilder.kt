// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.data

import com.timbra.data.model.FolderNode
import com.timbra.data.model.Track

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
        val collapsed = collapseSingleChildChains(root)
        computeTotals(collapsed)
        return collapsed
    }

    private fun computeTotals(node: FolderNode): Int {
        var total = node.tracks.size
        for (child in node.subFolders.values) total += computeTotals(child)
        node.totalTrackCount = total
        return total
    }

    private fun collapseSingleChildChains(root: FolderNode): FolderNode {
        var node = root
        while (node.tracks.isEmpty() && node.subFolders.size == 1) {
            node = node.subFolders.values.first()
        }
        return node
    }

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

    fun flatten(node: FolderNode): List<Track> {
        val out = ArrayList<Track>()
        out.addAll(node.tracks)
        node.subFolders.values.forEach { out.addAll(flatten(it)) }
        return out
    }

    /** Child folders of [node] in the app's ONE canonical folder order — shared by the
     *  Folders screen and the playback traversal so they can never drift apart. NATURAL,
     *  matching every track list, so "Disc 2" doesn't sort after "Disc 10". */
    fun sortedChildren(node: FolderNode): List<FolderNode> =
        node.childFolders.sortedWith(compareBy(NATURAL) { it.name })

    fun songFolders(root: FolderNode): List<FolderNode> {
        val out = ArrayList<FolderNode>()
        fun walk(node: FolderNode) {
            if (node.tracks.isNotEmpty()) out.add(node)
            sortedChildren(node).forEach { walk(it) }
        }
        walk(root)
        return out
    }

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
