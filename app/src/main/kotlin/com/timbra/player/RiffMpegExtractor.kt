// SPDX-License-Identifier: GPL-3.0-or-later
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

@UnstableApi
class RiffMpegExtractor : Extractor {

    private val delegate = Mp3Extractor()

    private var dataStart = -1L

    private var atPayload = false

    override fun sniff(input: ExtractorInput): Boolean {
        val riff = ByteArray(12)
        if (!input.peekFully(riff, 0, 12, true)) return false
        if (!riff.hasFourCc(0, "RIFF") || !riff.hasFourCc(8, "WAVE")) return false
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
        atPayload = dataStart in 1..position
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    private fun skipToPayload(input: ExtractorInput): Boolean {
        if (!input.skipFully(12, true)) return false
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
            if (!input.skipFully(size.padded(), true)) return false
        }
        return false
    }

    private fun ByteArray.hasFourCc(offset: Int, id: String): Boolean =
        id.indices.all { this[offset + it].toInt() == id[it].code }

    private fun ByteArray.le16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.le32(offset: Int): Int =
        le16(offset) or (le16(offset + 2) shl 16)

    private fun Int.padded(): Int =
        if (this >= Int.MAX_VALUE - 1) Int.MAX_VALUE - 1 else this + (this and 1)

    private companion object {
        const val FORMAT_MPEG = 0x0050
        const val FORMAT_MPEGLAYER3 = 0x0055

        const val MAX_CHUNKS = 16
    }
}

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
