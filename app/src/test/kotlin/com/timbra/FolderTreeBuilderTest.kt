// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra

import com.timbra.TestTracks.track
import com.timbra.data.FolderTreeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The virtual folder tree and the traversal Advance-List steps through. */
class FolderTreeBuilderTest {

    private val music = "/storage/emulated/0/Music"

    private fun library() = FolderTreeBuilder.build(
        listOf(
            track(1, path = "$music/Rock/02 b.mp3"),
            track(2, path = "$music/Rock/01 a.mp3"),
            track(3, path = "$music/Jazz/c.mp3"),
            track(4, path = "$music/Jazz/Live/d.mp3"),
        ),
    )

    @Test fun `leading single-child chain collapses to a meaningful root`() {
        val root = library()
        assertEquals("Music", root.name)
        assertEquals(music, root.path)
    }

    @Test fun `total counts are filled in for the whole tree after the build`() {
        // Not a `by lazy` on the node: tracks and subFolders are populated after construction, so a
        // lazy value read mid-build would memoise a partial count permanently.
        val root = library()
        assertEquals(4, root.totalTrackCount)
        val byName = root.subFolders
        assertEquals(2, byName.getValue("Jazz").totalTrackCount)
        assertEquals(2, byName.getValue("Rock").totalTrackCount)
        assertEquals(1, byName.getValue("Jazz").subFolders.getValue("Live").totalTrackCount)
    }

    @Test fun `find resolves a descendant by absolute path and misses cleanly`() {
        val root = library()
        assertEquals("Live", FolderTreeBuilder.find(root, "$music/Jazz/Live")?.name)
        assertNull(FolderTreeBuilder.find(root, "$music/Nope"))
        assertSame(root, FolderTreeBuilder.find(root, root.path))
    }

    @Test fun `children come back in natural order`() {
        val root = FolderTreeBuilder.build(
            listOf(
                track(1, path = "$music/Disc 10/a.mp3"),
                track(2, path = "$music/Disc 2/b.mp3"),
            ),
        )
        assertEquals(listOf("Disc 2", "Disc 10"), FolderTreeBuilder.sortedChildren(root).map { it.name })
    }

    @Test fun `songFolders lists only folders that directly hold tracks, depth first`() {
        val folders = FolderTreeBuilder.songFolders(library())
        // "Music" itself holds no loose files, so it is not an entry.
        assertEquals(listOf("Jazz", "Live", "Rock"), folders.map { it.name })
    }

    @Test fun `neighbourFolders walks the traversal and reports the edges as null`() {
        val folders = FolderTreeBuilder.songFolders(library())
        val (prevOfLive, nextOfLive) = FolderTreeBuilder.neighbourFolders(folders, "$music/Jazz/Live")
        assertEquals("Jazz", prevOfLive?.name)
        assertEquals("Rock", nextOfLive?.name)

        val (prevOfFirst, _) = FolderTreeBuilder.neighbourFolders(folders, "$music/Jazz")
        assertNull(prevOfFirst)
        val (_, nextOfLast) = FolderTreeBuilder.neighbourFolders(folders, "$music/Rock")
        assertNull(nextOfLast)
    }

    @Test fun `neighbourFolders tries the anchors in priority order`() {
        val folders = FolderTreeBuilder.songFolders(library())
        // A stale first anchor (e.g. a folderContext gone after a rescan) falls through to the next.
        val (prev, next) = FolderTreeBuilder.neighbourFolders(folders, "$music/Gone", "$music/Jazz/Live")
        assertEquals("Jazz", prev?.name)
        assertEquals("Rock", next?.name)
        assertEquals(null to null, FolderTreeBuilder.neighbourFolders(folders, null, ""))
    }

    @Test fun `flatten collects a whole subtree`() {
        val root = library()
        assertEquals(setOf(3L, 4L), FolderTreeBuilder.flatten(root.subFolders.getValue("Jazz")).map { it.id }.toSet())
        assertEquals(4, FolderTreeBuilder.flatten(root).size)
    }

    @Test fun `an empty library yields an empty root rather than failing`() {
        val root = FolderTreeBuilder.build(emptyList())
        assertEquals(0, root.totalTrackCount)
        assertEquals(emptyList<Any>(), FolderTreeBuilder.songFolders(root))
    }

    @Test fun `a path with no directory lands at the root`() {
        val root = FolderTreeBuilder.build(listOf(track(1, path = "loose.mp3")))
        assertEquals(1, root.totalTrackCount)
        assertEquals(1, root.tracks.size)
    }
}
