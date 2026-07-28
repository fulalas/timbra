package com.timbra.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mp3.Mp3Extractor

/**
 * Extractor for MPEG audio wrapped in a RIFF/WAVE container — `fmt ` format tag 0x0055
 * (`WAVE_FORMAT_MPEGLAYER3`) or 0x0050 (`WAVE_FORMAT_MPEG`). Old rips and CD-ripper output
 * do this, and the files are normally named `.mp3` with MediaStore reporting `audio/mpeg`.
 *
 * Media3 can't play them: sniffing stops at the first extractor that claims the stream, and
 * `WavExtractor` claims anything with the RIFF/WAVE magic — only to throw
 * `Unsupported WAV format type: 85` once it reads the format tag. The track dies with a
 * source error and `Mp3Extractor`, which would have handled the payload fine, never runs.
 *
 * So this sniffs the narrower case (RIFF/WAVE *carrying MPEG*) and is registered ahead of
 * the defaults (see [TimbraExtractorsFactory]) to win that race. Real PCM/ADPCM WAVs fail
 * the format-tag check and fall through to `WavExtractor` as before. Demuxing itself is
 * delegated to [Mp3Extractor] after the container header is skipped.
 */
@UnstableApi
class RiffMpegExtractor : Extractor {

    private val delegate = Mp3Extractor()

    /** File offset of the `data` chunk payload; -1 until [read] has located it. */
    private var dataStart = -1L

    /** True once the read position is inside the MPEG payload, so the delegate can take over. */
    private var atPayload = false

    override fun sniff(input: ExtractorInput): Boolean {
        val riff = ByteArray(12)
        if (!input.peekFully(riff, 0, 12, /* allowEndOfInput= */ true)) return false
        if (!riff.hasFourCc(0, "RIFF") || !riff.hasFourCc(8, "WAVE")) return false
        // Walk the chunk list to `fmt `: it is usually first, but JUNK/bext padding can precede
        // it, and its own size varies (30 or 32 bytes for MPEG), so a fixed offset won't do.
        val chunk = ByteArray(8)
        var seen = 0
        while (seen++ < MAX_CHUNKS) {
            if (!input.peekFully(chunk, 0, 8, true)) return false
            val size = chunk.le32(4)
            if (size < 0) return false
            if (chunk.hasFourCc(0, "fmt ")) {
                val tag = ByteArray(2)
                if (size < 2 || !input.peekFully(tag, 0, 2, true)) return false
                return tag.le16(0) == FORMAT_MPEG || tag.le16(0) == FORMAT_MPEGLAYER3
            }
            if (!input.advancePeekPosition(size.padded(), true)) return false
        }
        return false
    }

    override fun init(output: ExtractorOutput) = delegate.init(output)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!atPayload) {
            if (!skipToPayload(input)) return Extractor.RESULT_END_OF_INPUT
            atPayload = true
        }
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        // The delegate seeks to absolute file offsets it derived from the first frame, so those
        // are already inside the payload; only a rewind to the very start needs the header
        // skipped again.
        atPayload = dataStart in 1..position
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    /**
     * Advance the read position from the start of the file to the first MPEG frame, i.e. the
     * `data` chunk payload. Returns false if the file ends before one is found.
     */
    private fun skipToPayload(input: ExtractorInput): Boolean {
        input.skipFully(12) // "RIFF" + size + "WAVE"
        val chunk = ByteArray(8)
        var seen = 0
        while (seen++ < MAX_CHUNKS) {
            if (!input.readFully(chunk, 0, 8, true)) return false
            val size = chunk.le32(4)
            if (size < 0) return false
            if (chunk.hasFourCc(0, "data")) {
                dataStart = input.position
                return true
            }
            input.skipFully(size.padded())
        }
        return false
    }

    private fun ByteArray.hasFourCc(offset: Int, id: String): Boolean =
        id.indices.all { this[offset + it].toInt() == id[it].code }

    private fun ByteArray.le16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le32(offset: Int): Int =
        le16(offset) or (le16(offset + 2) shl 16)

    /** RIFF chunk bodies are word-aligned: an odd size is followed by one pad byte. */
    private fun Int.padded(): Int = this + (this and 1)

    private companion object {
        const val FORMAT_MPEG = 0x0050
        const val FORMAT_MPEGLAYER3 = 0x0055

        /** Bound on the chunk walk, so a corrupt size field can't spin. */
        const val MAX_CHUNKS = 16
    }
}

/**
 * The default extractors, preceded by [RiffMpegExtractor] so RIFF-wrapped MPEG is claimed
 * before `WavExtractor` can fail on it.
 */
@UnstableApi
class TimbraExtractorsFactory : ExtractorsFactory {

    private val defaults = DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> =
        arrayOf<Extractor>(RiffMpegExtractor()) + defaults.createExtractors()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        arrayOf<Extractor>(RiffMpegExtractor()) + defaults.createExtractors(uri, responseHeaders)
}
