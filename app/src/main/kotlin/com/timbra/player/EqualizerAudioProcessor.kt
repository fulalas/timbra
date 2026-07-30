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
import kotlin.math.round
import kotlin.math.sin

/**
 * A 7-band graphic equalizer implemented as a Media3 [AudioProcessor] — pure DSP in ExoPlayer's
 * audio pipeline, so it works on every device regardless of the platform AudioEffect support
 * (the framework [android.media.audiofx.DynamicsProcessing]/Equalizer effects fail to init on
 * some HALs). Each band is a biquad peaking filter (RBJ cookbook) cascaded per channel.
 *
 * Only 16-bit PCM is handled; other encodings pass through untouched (the processor reports
 * itself inactive).
 *
 * Threading: the whole tunable state is one immutable [Tuning] published in a single volatile
 * store (see [tuning]), and every rebuild happens under [buildLock] — the UI thread (via
 * [update]) and the playback thread (via [onConfigure]) both rebuild, and two independent
 * volatile fields could otherwise be read as a mismatched pair or published out of order.
 * Per-sample filter state is touched only on the audio thread.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    /**
     * An atomically-published snapshot of everything the audio thread needs.
     *
     * [generation] identifies the transfer function: the audio thread drops its filter memory
     * whenever it changes, because feeding x1/x2/y1/y2 from one response into a different one
     * emits a step transient (a fader tap from +15 dB to -15 dB used to click, clipping against
     * the output clamp), and because the bypass branch freezes that memory — so re-enabling
     * would otherwise resume the biquads with sample history from an arbitrarily earlier moment.
     */
    private class Tuning(
        val enabled: Boolean,
        /** Per-band normalized coeffs [b0, b1, b2, a1, a2]. */
        val coeffs: Array<DoubleArray>,
        /**
         * Indices of the bands whose coeffs are NOT the identity, i.e. the only ones worth
         * running. Identity biquads output their input exactly and carry no state worth
         * preserving, so skipping them is lossless — and this loop runs per SAMPLE on the audio
         * thread, where a typical 2-3-slider curve would otherwise pay for all 7 bands.
         */
        val activeBands: IntArray,
        val generation: Int,
    )

    @Volatile private var tuning = Tuning(false, identityCoeffs(), IntArray(0), 0)

    /** Guards the rebuild inputs below and serializes publishing. */
    private val buildLock = Any()

    // --- All guarded by [buildLock] ---
    private var enabledInput = false
    private var gainsInput = IntArray(EqSettings.BAND_COUNT)
    private var sampleRate = 0
    private var generation = 0

    // --- Audio thread only ---
    private var channels = 0
    /** Per-channel, per-band filter memory: [channel][band*4 + (x1,x2,y1,y2)]. */
    private var state: Array<DoubleArray> = emptyArray()
    private var appliedGeneration = 0

    /** Called from the session callback (application thread) on every equalizer change. */
    fun update(enabled: Boolean, gainsDb: IntArray) = synchronized(buildLock) {
        enabledInput = enabled
        gainsInput = gainsDb.copyOf(EqSettings.BAND_COUNT)
        if (sampleRate > 0) publish()
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Only 16-bit PCM is supported; anything else bypasses (returns NOT_SET -> inactive).
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        state = Array(channels) { DoubleArray(EqSettings.BAND_COUNT * 4) }
        synchronized(buildLock) {
            sampleRate = inputAudioFormat.sampleRate
            publish()
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        // ONE volatile read: coeffs, activeBands and enabled always belong to the same rebuild.
        val cfg = tuning
        if (cfg.generation != appliedGeneration) {
            appliedGeneration = cfg.generation
            clearState()
        }
        val co = cfg.coeffs
        val active = cfg.activeBands
        // Bypass: copy through unchanged when disabled or every band is flat (all identity).
        if (!cfg.enabled || active.isEmpty()) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val ch = channels
        val inShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        out.order(ByteOrder.LITTLE_ENDIAN)
        val total = inShorts.remaining()
        var i = 0
        var c = 0 // interleaved channel of sample i (a wrapping counter, not a per-sample modulo)
        while (i < total) {
            val st = state[c]
            var s = inShorts.get().toDouble()
            for (band in active) {
                val k = band * 4
                val bq = co[band]
                val x = s
                val y = bq[0] * x + bq[1] * st[k] + bq[2] * st[k + 1] - bq[3] * st[k + 2] - bq[4] * st[k + 3]
                st[k + 1] = st[k]; st[k] = x          // x2 = x1; x1 = x
                st[k + 3] = st[k + 2]; st[k + 2] = y  // y2 = y1; y1 = y
                s = y
            }
            // ROUND, not truncate: `toInt()` rounds toward zero, which biases every sample
            // toward silence and leaves a ±1 LSB dead zone around zero. round() also keeps a
            // NaN from throwing (roundToInt would) — it lands on 0 through the clamp.
            out.putShort(round(s).toInt().coerceIn(-32768, 32767).toShort())
            if (++c == ch) c = 0
            i++
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() = clearState()
    override fun onReset() {
        clearState()
        channels = 0
        synchronized(buildLock) { sampleRate = 0 }
    }

    private fun clearState() = state.forEach { it.fill(0.0) }

    /** Build the coeffs and publish them with [enabledInput] as one snapshot. Call under
     *  [buildLock]: a rebuild that read a stale [sampleRate] could otherwise land AFTER the
     *  one triggered by a format change and leave the wrong band centres in effect. */
    private fun publish() {
        val co = buildCoeffs(gainsInput, sampleRate)
        val active = co.indices.filter { co[it][0] != 1.0 || co[it][1] != 0.0 }.toIntArray()
        generation++
        tuning = Tuning(enabledInput, co, active, generation)
    }

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
