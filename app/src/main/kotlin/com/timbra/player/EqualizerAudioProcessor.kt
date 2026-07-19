package com.timbra.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A 7-band graphic equalizer implemented as a Media3 [AudioProcessor] — pure DSP in ExoPlayer's
 * audio pipeline, so it works on every device regardless of the platform AudioEffect support
 * (the framework [android.media.audiofx.DynamicsProcessing]/Equalizer effects fail to init on
 * some HALs). Each band is a biquad peaking filter (RBJ cookbook) cascaded per channel.
 *
 * Only 16-bit PCM is handled; other encodings pass through untouched (the processor reports
 * itself inactive). Gains/enabled are updated from the binder thread and read on the audio
 * thread via @Volatile; per-sample filter state is touched only on the audio thread.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    @Volatile private var enabled = false
    @Volatile private var gainsDb = IntArray(EqSettings.BAND_COUNT)
    /** Per-band normalized coeffs [b0, b1, b2, a1, a2]; rebuilt when gains/format change. */
    @Volatile private var coeffs: Array<DoubleArray> = identityCoeffs()

    @Volatile private var sampleRate = 0
    private var channels = 0
    /** Per-channel, per-band filter memory: [channel][band*4 + (x1,x2,y1,y2)]. */
    private var state: Array<DoubleArray> = emptyArray()

    /** Called from the service (binder thread) on every equalizer change. */
    fun update(enabled: Boolean, gainsDb: IntArray) {
        this.enabled = enabled
        this.gainsDb = gainsDb.copyOf(EqSettings.BAND_COUNT)
        if (sampleRate > 0) coeffs = buildCoeffs(this.gainsDb, sampleRate)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Only 16-bit PCM is supported; anything else bypasses (returns NOT_SET -> inactive).
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        state = Array(channels) { DoubleArray(EqSettings.BAND_COUNT * 4) }
        coeffs = buildCoeffs(gainsDb, sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        // Bypass: copy through unchanged when disabled.
        if (!enabled) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val co = coeffs
        val ch = channels
        val inShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        out.order(ByteOrder.LITTLE_ENDIAN)
        val total = inShorts.remaining()
        var i = 0
        while (i < total) {
            val c = i % ch
            val st = state[c]
            var s = inShorts.get().toDouble()
            var band = 0
            while (band < EqSettings.BAND_COUNT) {
                val k = band * 4
                val bq = co[band]
                val x = s
                val y = bq[0] * x + bq[1] * st[k] + bq[2] * st[k + 1] - bq[3] * st[k + 2] - bq[4] * st[k + 3]
                st[k + 1] = st[k]; st[k] = x          // x2 = x1; x1 = x
                st[k + 3] = st[k + 2]; st[k + 2] = y  // y2 = y1; y1 = y
                s = y
                band++
            }
            out.putShort(s.toInt().coerceIn(-32768, 32767).toShort())
            i++
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() = clearState()
    override fun onReset() { clearState(); sampleRate = 0; channels = 0 }

    private fun clearState() = state.forEach { it.fill(0.0) }

    private companion object {
        /** Q for each peaking band — moderate width, smooth overlap across the 7 bands. */
        const val Q = 1.0

        fun identityCoeffs(): Array<DoubleArray> =
            Array(EqSettings.BAND_COUNT) { doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0) }

        /** RBJ cookbook peaking-EQ biquads, normalized so a0 = 1 (0 dB = exact passthrough). */
        fun buildCoeffs(gainsDb: IntArray, sampleRate: Int): Array<DoubleArray> =
            Array(EqSettings.BAND_COUNT) { band ->
                val gain = gainsDb.getOrElse(band) { 0 }
                val f0 = EqSettings.BAND_FREQS[band]
                // Skip (pass through) bands at/above Nyquist: the RBJ formula is only valid for
                // 0 < w0 < π; at or beyond it the poles leave the unit circle and the filter
                // self-oscillates. Also short-circuit 0 dB (an exact passthrough).
                if (gain == 0 || f0 * 2 >= sampleRate) {
                    doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
                } else {
                    val a = 10.0.pow(gain / 40.0)
                    val w0 = 2.0 * PI * f0 / sampleRate
                    val cosW0 = cos(w0)
                    val alpha = sin(w0) / (2.0 * Q)
                    val b0 = 1 + alpha * a
                    val b1 = -2 * cosW0
                    val b2 = 1 - alpha * a
                    val a0 = 1 + alpha / a
                    val a1 = -2 * cosW0
                    val a2 = 1 - alpha / a
                    doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
                }
            }
    }
}
