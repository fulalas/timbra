// SPDX-License-Identifier: GPL-3.0-or-later
package com.timbra.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.timbra.R
import com.timbra.data.model.Album
import com.timbra.data.model.Artist
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Genre
import com.timbra.data.model.Playlist
import com.timbra.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads the on-device audio library from MediaStore. All tracks are loaded once and
 * cached; albums/artists/folders are derived in memory, genres come from the Genres
 * tables. Refresh with [invalidate].
 */
class MediaRepository(context: Context) {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    /** The "no tags" labels, resolved once. Index rows must show the SAME text the track rows
     *  do — building them from the raw (blank) value rendered title-less rows and, once tapped,
     *  an empty toolbar title. Also the grouping key for [tracksForArtist]. */
    private val unknownArtist by lazy { appContext.getString(R.string.unknown_artist) }
    private val unknownAlbum by lazy { appContext.getString(R.string.unknown_album) }

    /**
     * A lazily-built, invalidatable cache.
     *
     * [invalidate] bumps a generation, and a build that started before it publishes NOTHING —
     * the plain `field ?: build().also { field = it }` idiom could write pre-rescan data back
     * after the cache was cleared (the build is already inside the `?:` branch when invalidate
     * runs, and neither `@Volatile` nor coroutine cancellation stops the trailing assignment),
     * so a deleted track stayed in the library and in folder navigation indefinitely. The mutex
     * also stops two cold callers from running the same expensive query twice.
     *
     * One instance per cached value, never nested onto itself, so the mutexes can't deadlock
     * even though the derived caches build from each other (tracks -> folderRoot -> songFolders).
     */
    private class Cache<T : Any> {
        @Volatile private var value: T? = null
        // AtomicInteger, not a @Volatile Int: `generation++` is a read-modify-write, so two
        // overlapping invalidations (a permission grant and a MediaStore change signal) could
        // collapse into one increment — and a lost increment is exactly what lets an in-flight
        // build() pass the check below and publish pre-rescan data.
        private val generation = AtomicInteger(0)
        private val lock = Mutex()

        fun invalidate() {
            generation.incrementAndGet()
            value = null
        }

        suspend fun get(build: suspend () -> T): T {
            value?.let { return it }
            return lock.withLock {
                value?.let { return@withLock it }
                val startedAt = generation.get()
                val built = build()
                if (generation.get() == startedAt) value = built
                built
            }
        }
    }

    private val tracksCache = Cache<List<Track>>()
    private val albumsCache = Cache<List<Album>>()
    private val artistsCache = Cache<List<Artist>>()
    private val genresCache = Cache<List<Genre>>()
    private val playlistsCache = Cache<List<Playlist>>()
    private val folderRootCache = Cache<FolderNode>()
    private val songFoldersCache = Cache<List<FolderNode>>()

    /** albumId -> its tracks / display artist -> its tracks. [albums]/[artists] compute exactly
     *  these groupings to build their index rows, so caching them turns the per-screen
     *  `allTracks().filter { … }` whole-library walk into one map lookup. */
    private val tracksByAlbumCache = Cache<Map<Long, List<Track>>>()
    private val tracksByArtistCache = Cache<Map<String, List<Track>>>()

    /** genreId -> member track ids, so opening a genre doesn't re-run the Members query. */
    private val genreMembersCache = Cache<ConcurrentHashMap<Long, Set<Long>>>()

    private val caches = listOf(
        tracksCache, albumsCache, artistsCache, genresCache, playlistsCache,
        folderRootCache, songFoldersCache, tracksByAlbumCache, tracksByArtistCache,
        genreMembersCache,
    )

    fun invalidate() = caches.forEach { it.invalidate() }

    suspend fun allTracks(): List<Track> = tracksCache.get {
        withContext(Dispatchers.IO) { queryTracks() }
    }

    /** Cached: the folder tree is rebuilt from all tracks, which is costly and was being
     *  reconstructed on every folder navigation (Advance-List phantom lookups). */
    suspend fun folderRoot(): FolderNode = folderRootCache.get {
        val tracks = allTracks()
        withContext(Dispatchers.Default) { FolderTreeBuilder.build(tracks) }
    }

    /** Cached flat traversal list ([FolderTreeBuilder.songFolders]) — folder navigation
     *  consults it up to several times per gesture, so it must not re-walk the tree. */
    suspend fun songFolders(): List<FolderNode> = songFoldersCache.get {
        val root = folderRoot()
        withContext(Dispatchers.Default) { FolderTreeBuilder.songFolders(root) }
    }

    private suspend fun tracksByAlbum(): Map<Long, List<Track>> = tracksByAlbumCache.get {
        allTracks().groupBy { it.albumId }
    }

    private suspend fun tracksByArtist(): Map<String, List<Track>> = tracksByArtistCache.get {
        allTracks().groupBy { it.artist.ifBlank { unknownArtist } }
    }

    suspend fun albums(): List<Album> = albumsCache.get {
        tracksByAlbum()
            .map { (id, tracks) ->
                Album(
                    id,
                    tracks.first().album.ifBlank { unknownAlbum },
                    tracks.first().artist.ifBlank { unknownArtist },
                    tracks.size,
                )
            }
            // NATURAL, not plain lowercase(): every track list orders titles this way, so a
            // lexicographic index made "Live 2" sort after "Live 10" on one screen and before
            // it on the next.
            .sortedWith(compareBy(NATURAL) { it.title })
    }

    suspend fun artists(): List<Artist> = artistsCache.get {
        tracksByArtist()
            .map { (name, tracks) -> Artist(name.hashCode().toLong(), name, tracks.size) }
            .sortedWith(compareBy(NATURAL) { it.name })
    }

    suspend fun tracksForAlbum(albumId: Long): List<Track> =
        tracksByAlbum()[albumId] ?: emptyList()

    /** [artistName] is the DISPLAYED name (what [artists] emitted), which is also the grouping
     *  key — so an untagged artist's rows resolve instead of silently coming back empty. */
    suspend fun tracksForArtist(artistName: String): List<Track> =
        tracksByArtist()[artistName] ?: emptyList()

    // --- Genres (queried from the dedicated tables) ---

    suspend fun genres(): List<Genre> = genresCache.get {
        withContext(Dispatchers.IO) {
            val out = mutableListOf<Genre>()
            val uri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
            resolver.query(
                uri,
                arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
                null, null, MediaStore.Audio.Genres.NAME,
            )?.use { c ->
                // Tolerant column lookup, like the core track query below: these are the LEGACY
                // MediaStore tables and are sparse on Android 11+, so a provider that doesn't
                // expose a column must leave the category empty rather than throw out of the
                // caller's unguarded lifecycleScope.launch and take the app down.
                val idCol = c.getColumnIndex(MediaStore.Audio.Genres._ID)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Genres.NAME)
                if (idCol >= 0 && nameCol >= 0) {
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val name = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                        // Row COUNT only — materializing every member id into a HashSet just to
                        // read its size walked the whole Members table once per genre.
                        val count = genreMemberCount(id)
                        if (count > 0) out.add(Genre(id, name, count))
                    }
                }
            }
            out
        }
    }

    suspend fun tracksForGenre(genreId: Long): List<Track> {
        val tracks = allTracks()
        val members = genreMembersCache.get { ConcurrentHashMap() }
        // Read-then-put on a concurrent map rather than a blocking `synchronized` around the
        // query: holding a monitor across a content-provider call parks an IO dispatcher thread
        // and serialises genres that share nothing. A rare duplicate query is the better trade.
        val ids = members[genreId] ?: withContext(Dispatchers.IO) { genreMemberIds(genreId) }
            .also { members[genreId] = it }
        return tracks.filter { it.id in ids }
    }

    private fun genreMemberCount(genreId: Long): Int {
        val uri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
        return resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)
            ?.use { it.count } ?: 0
    }

    private fun genreMemberIds(genreId: Long): Set<Long> {
        val ids = HashSet<Long>()
        val uri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
        resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(MediaStore.Audio.Media._ID)
            if (idCol >= 0) while (c.moveToNext()) ids.add(c.getLong(idCol))
        }
        return ids
    }

    // --- Playlists (legacy MediaStore tables) ---

    @Suppress("DEPRECATION")
    suspend fun playlists(): List<Playlist> = playlistsCache.get {
        val tracks = allTracks()
        withContext(Dispatchers.IO) {
            // Build the id map once, not per playlist — and INSIDE the dispatch: outside it, this
            // O(n) map over the whole library ran on whatever dispatcher the caller happened to
            // use, which nothing here enforces is not the main thread.
            val byId = tracks.associateBy { it.id }
            val out = mutableListOf<Playlist>()
            resolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
                null, null, MediaStore.Audio.Playlists.NAME,
            )?.use { c ->
                // Tolerant, for the same reason as genres() above: legacy table, sparse on
                // Android 11+, and the throw would land in an unguarded coroutine.
                val idCol = c.getColumnIndex(MediaStore.Audio.Playlists._ID)
                val nameCol = c.getColumnIndex(MediaStore.Audio.Playlists.NAME)
                if (idCol >= 0 && nameCol >= 0) {
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val name = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                        val count = playlistMemberIds(id).count { it in byId }
                        out.add(Playlist(id, name, count))
                    }
                }
            }
            out
        }
    }

    suspend fun tracksForPlaylist(playlistId: Long): List<Track> {
        val tracks = allTracks()
        return withContext(Dispatchers.IO) {
            // Map built inside the dispatch (see playlists()).
            val byId = tracks.associateBy { it.id }
            playlistMemberIds(playlistId).mapNotNull { byId[it] }
        }
    }

    @Suppress("DEPRECATION")
    private fun playlistMemberIds(playlistId: Long): List<Long> {
        val ids = ArrayList<Long>()
        val uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
        resolver.query(
            uri, arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID),
            null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER,
        )?.use { c ->
            val col = c.getColumnIndex(MediaStore.Audio.Playlists.Members.AUDIO_ID)
            if (col >= 0) while (c.moveToNext()) ids.add(c.getLong(col))
        }
        return ids
    }

    // --- Change detection ---

    /**
     * A marker of the audio table's current state, compared against the previous value to decide
     * whether a refresh is needed at all ([com.timbra.TimbraApp.refreshLibraryIfChanged]).
     * Deliberately NOT cached — reading through to MediaStore is the entire point.
     *
     * Row count and the newest DATE_ADDED / DATE_MODIFIED catch an add, a delete or an in-place
     * retag. The path fold catches what they can't: a same-volume MOVE or RENAME changes neither
     * the count nor the file's mtime, and in a folder-first player that is the change that matters
     * most — the folder tree and the traversal list would otherwise keep serving a hierarchy that
     * no longer exists. Summed (not accumulated in order), since the row order is unspecified.
     *
     * Everything is folded here rather than asked for as `count(*)`/`max(...)`: MediaStore
     * validates the projection on Android 10+ and rejects anything that isn't a real column, so
     * SQL aggregates are unavailable. That makes this O(rows) — one cursor walk on the IO
     * dispatcher, roughly an order of magnitude under [queryTracks] (which reads eleven columns,
     * four of them strings, and allocates a [Track] and a Uri per row), but NOT free: it is why
     * the caller compares before refreshing instead of refreshing outright.
     */
    suspend fun libraryFingerprint(): String = withContext(Dispatchers.IO) {
        var count = 0
        var newestAdded = 0L
        var newestModified = 0L
        var pathFold = 0
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.DATA,
            ),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
        )?.use { c ->
            val addedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
            val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                count++
                if (addedCol >= 0) newestAdded = maxOf(newestAdded, c.getLong(addedCol))
                if (modifiedCol >= 0) newestModified = maxOf(newestModified, c.getLong(modifiedCol))
                if (dataCol >= 0) pathFold += c.getString(dataCol)?.hashCode() ?: 0
            }
        }
        "$count/$newestAdded/$newestModified/$pathFold"
    }

    // --- Core track query ---

    private fun queryTracks(): List<Track> {
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            // RELATIVE_PATH only exists on API 29+; querying it earlier throws.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val out = ArrayList<Track>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, null, null,
        )?.use { c ->
            // Resolve the column indices ONCE: getColumnIndex is a linear scan of the column
            // names, and doing all 11 per row cost ~110k lookups on a 10k-track library —
            // during the one query that gates first paint of every list.
            val cols = TrackColumns(c)
            while (c.moveToNext()) out.add(readTrack(c, cols))
        }
        return out
    }

    /** The (loop-invariant) column indices of the [queryTracks] projection. */
    private class TrackColumns(c: Cursor) {
        val id = c.getColumnIndex(MediaStore.Audio.Media._ID)
        val title = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
        val album = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
        val albumId = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
        val duration = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
        val track = c.getColumnIndex(MediaStore.Audio.Media.TRACK)
        val dateAdded = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
        val data = c.getColumnIndex(MediaStore.Audio.Media.DATA)
        val displayName = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
        val relativePath = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
    }

    private fun readTrack(c: Cursor, cols: TrackColumns): Track {
        val id = c.getLong(cols.id)
        val data = if (cols.data >= 0) c.getString(cols.data) else null
        val displayName = if (cols.displayName >= 0) c.getString(cols.displayName) else null
        val relPath = if (cols.relativePath >= 0) c.getString(cols.relativePath) else null
        val path = when {
            !data.isNullOrEmpty() -> data
            relPath != null -> "/" + (relPath.trimEnd('/') + "/" + (displayName ?: "")).trimStart('/')
            else -> displayName ?: id.toString()
        }
        val trackRaw = if (cols.track >= 0) c.getInt(cols.track) else 0
        return Track(
            id = id,
            uri = trackUri(id),
            title = c.getString(cols.title) ?: (displayName ?: "?"),
            artist = c.getString(cols.artist).orUnknown(),
            album = c.getString(cols.album).orUnknown(),
            albumId = c.getLong(cols.albumId),
            durationMs = c.getLong(cols.duration),
            // MediaStore encodes disc*1000 + track. Keep BOTH: dropping the disc made a
            // multi-disc album interleave under the album/track order. `>= 1000` (not `>`),
            // so disc 1 / track 0 — stored as exactly 1000 — doesn't survive as trackNo 1000
            // and sort itself last.
            trackNo = if (trackRaw >= 1000) trackRaw % 1000 else trackRaw,
            discNo = if (trackRaw >= 1000) trackRaw / 1000 else 0,
            dateAddedSec = c.getLong(cols.dateAdded),
            path = path,
        )
    }

    private fun String?.orUnknown(): String =
        if (this.isNullOrBlank() || this == "<unknown>") "" else this

    companion object {
        /** Parsed once — [albumArtUri] is called per track while a queue is built (10k times
         *  for Shuffle-All), and re-parsing the constant allocated a Uri each time. */
        private val ALBUM_ART_BASE: Uri = Uri.parse("content://media/external/audio/albumart")

        fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

        /**
         * The playable/shareable content Uri of a track by MediaStore id. Art consumers load
         * through this (not the album-art table alone) so embedded-only covers are found too.
         */
        fun trackUri(mediaId: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
    }
}
