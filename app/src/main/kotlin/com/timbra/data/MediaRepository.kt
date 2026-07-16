package com.timbra.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import com.timbra.data.model.Album
import com.timbra.data.model.Artist
import com.timbra.data.model.FolderNode
import com.timbra.data.model.Genre
import com.timbra.data.model.Playlist
import com.timbra.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the on-device audio library from MediaStore. All tracks are loaded once and
 * cached; albums/artists/folders are derived in memory, genres come from the Genres
 * tables. Refresh with [invalidate].
 */
class MediaRepository(context: Context) {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    @Volatile private var cachedTracks: List<Track>? = null
    @Volatile private var cachedAlbums: List<Album>? = null
    @Volatile private var cachedArtists: List<Artist>? = null
    @Volatile private var cachedGenres: List<Genre>? = null
    @Volatile private var cachedPlaylists: List<Playlist>? = null
    @Volatile private var cachedFolderRoot: FolderNode? = null
    @Volatile private var cachedSongFolders: List<FolderNode>? = null

    fun invalidate() {
        cachedTracks = null
        cachedAlbums = null
        cachedArtists = null
        cachedGenres = null
        cachedPlaylists = null
        cachedFolderRoot = null
        cachedSongFolders = null
    }

    suspend fun allTracks(): List<Track> = withContext(Dispatchers.IO) {
        cachedTracks ?: queryTracks().also { cachedTracks = it }
    }

    /** Cached: the folder tree is rebuilt from all tracks, which is costly and was being
     *  reconstructed on every folder navigation (Advance-List phantom lookups). */
    suspend fun folderRoot(): FolderNode = withContext(Dispatchers.IO) {
        cachedFolderRoot ?: FolderTreeBuilder.build(allTracks()).also { cachedFolderRoot = it }
    }

    /** Cached flat traversal list ([FolderTreeBuilder.songFolders]) — folder navigation
     *  consults it up to several times per gesture, so it must not re-walk the tree. */
    suspend fun songFolders(): List<FolderNode> = withContext(Dispatchers.IO) {
        cachedSongFolders ?: FolderTreeBuilder.songFolders(folderRoot()).also { cachedSongFolders = it }
    }

    suspend fun albums(): List<Album> = withContext(Dispatchers.IO) {
        cachedAlbums ?: allTracks()
            .groupBy { it.albumId }
            .map { (id, tracks) ->
                Album(id, tracks.first().album, tracks.first().artist, tracks.size)
            }
            .sortedBy { it.title.lowercase() }
            .also { cachedAlbums = it }
    }

    suspend fun artists(): List<Artist> = withContext(Dispatchers.IO) {
        cachedArtists ?: allTracks()
            .groupBy { it.artist }
            .map { (name, tracks) -> Artist(name.hashCode().toLong(), name, tracks.size) }
            .sortedBy { it.name.lowercase() }
            .also { cachedArtists = it }
    }

    suspend fun tracksForAlbum(albumId: Long): List<Track> =
        allTracks().filter { it.albumId == albumId }

    suspend fun tracksForArtist(artistName: String): List<Track> =
        allTracks().filter { it.artist == artistName }

    // --- Genres (queried from the dedicated tables) ---

    suspend fun genres(): List<Genre> = withContext(Dispatchers.IO) {
        cachedGenres?.let { return@withContext it }
        val out = mutableListOf<Genre>()
        val uri = MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
        resolver.query(
            uri,
            arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME),
            null, null, MediaStore.Audio.Genres.NAME,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Genres._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                val count = genreMemberIds(id).size
                if (count > 0) out.add(Genre(id, name, count))
            }
        }
        out.also { cachedGenres = it }
    }

    suspend fun tracksForGenre(genreId: Long): List<Track> = withContext(Dispatchers.IO) {
        val ids = genreMemberIds(genreId)
        allTracks().filter { it.id in ids }
    }

    private fun genreMemberIds(genreId: Long): Set<Long> {
        val ids = HashSet<Long>()
        val uri = MediaStore.Audio.Genres.Members.getContentUri("external", genreId)
        resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (c.moveToNext()) ids.add(c.getLong(idCol))
        }
        return ids
    }

    // --- Playlists (legacy MediaStore tables) ---

    @Suppress("DEPRECATION")
    suspend fun playlists(): List<Playlist> = withContext(Dispatchers.IO) {
        cachedPlaylists?.let { return@withContext it }
        // Build the id map once, not per playlist.
        val byId = allTracks().associateBy { it.id }
        val out = mutableListOf<Playlist>()
        resolver.query(
            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
            null, null, MediaStore.Audio.Playlists.NAME,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol)?.takeIf { it.isNotBlank() } ?: continue
                val count = playlistMemberIds(id).count { it in byId }
                out.add(Playlist(id, name, count))
            }
        }
        out.also { cachedPlaylists = it }
    }

    suspend fun tracksForPlaylist(playlistId: Long): List<Track> = withContext(Dispatchers.IO) {
        val byId = allTracks().associateBy { it.id }
        playlistMemberIds(playlistId).mapNotNull { byId[it] }
    }

    @Suppress("DEPRECATION")
    private fun playlistMemberIds(playlistId: Long): List<Long> {
        val ids = ArrayList<Long>()
        val uri = MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId)
        resolver.query(
            uri, arrayOf(MediaStore.Audio.Playlists.Members.AUDIO_ID),
            null, null, MediaStore.Audio.Playlists.Members.PLAY_ORDER,
        )?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Audio.Playlists.Members.AUDIO_ID)
            while (c.moveToNext()) ids.add(c.getLong(col))
        }
        return ids
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
            while (c.moveToNext()) out.add(readTrack(c))
        }
        return out
    }

    private fun readTrack(c: Cursor): Track {
        fun col(name: String) = c.getColumnIndex(name)
        val id = c.getLong(col(MediaStore.Audio.Media._ID))
        val data = col(MediaStore.Audio.Media.DATA).let { if (it >= 0) c.getString(it) else null }
        val displayName = col(MediaStore.Audio.Media.DISPLAY_NAME)
            .let { if (it >= 0) c.getString(it) else null }
        val relPath = col(MediaStore.Audio.Media.RELATIVE_PATH)
            .let { if (it >= 0) c.getString(it) else null }
        val path = when {
            !data.isNullOrEmpty() -> data
            relPath != null -> "/" + (relPath.trimEnd('/') + "/" + (displayName ?: "")).trimStart('/')
            else -> displayName ?: id.toString()
        }
        val trackRaw = col(MediaStore.Audio.Media.TRACK).let { if (it >= 0) c.getInt(it) else 0 }
        return Track(
            id = id,
            uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
            title = c.getString(col(MediaStore.Audio.Media.TITLE)) ?: (displayName ?: "?"),
            artist = c.getString(col(MediaStore.Audio.Media.ARTIST)).orUnknown(),
            album = c.getString(col(MediaStore.Audio.Media.ALBUM)).orUnknown(),
            albumId = c.getLong(col(MediaStore.Audio.Media.ALBUM_ID)),
            durationMs = c.getLong(col(MediaStore.Audio.Media.DURATION)),
            // MediaStore encodes disc*1000 + track; keep just the track number.
            trackNo = if (trackRaw > 1000) trackRaw % 1000 else trackRaw,
            dateAddedSec = c.getLong(col(MediaStore.Audio.Media.DATE_ADDED)),
            path = path,
        )
    }

    private fun String?.orUnknown(): String =
        if (this.isNullOrBlank() || this == "<unknown>") "" else this

    companion object {
        fun albumArtUri(albumId: Long): Uri =
            ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
    }
}
