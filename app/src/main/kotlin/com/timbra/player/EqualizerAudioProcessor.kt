// SPDX-License-Identifier: GPL-3.0-or-later
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

@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    /**
     * [generation] identifies the transfer function: the audio thread drops its filter memory
     * whenever it changes, because feeding x1/x2/y1/y2 from one response into a different one
     * emits a step transient (a fader tap from +15 dB to -15 dB used to click, clipping against
     * the output clamp), and because the bypass branch freezes that memory — so re-enabling
     * would otherwise resume the biquads with sample history from an arbitrarily earlier moment.
     */
    private class Tuning(
        val enabled: Boolean,
        val coeffs: Array<DoubleArray>,
        val activeBands: IntArray,
        /**
         * The biquads can only ADD level and the only limiter downstream is the output clamp, so
         * a loud track with any boost clipped into audible distortion rather than simply getting
         * louder — and because the seven Q=1 bands sit ~1.4 octaves apart their responses overlap,
         * so adjacent boosts compound well past either band's dB figure.
         */
        val preamp: Double,
        val generation: Int,
    )

    @Volatile private var tuning = Tuning(false, identityCoeffs(), IntArray(0), 1.0, 0)

    private val buildLock = Any()

    private var enabledInput = false
    private var gainsInput = IntArray(EqSettings.BAND_COUNT)
    private var sampleRate = 0
    private var generation = 0

    private var channels = 0
    private var state: Array<DoubleArray> = emptyArray()
    private var appliedGeneration = 0

    fun update(enabled: Boolean, gainsDb: IntArray) = synchronized(buildLock) {
        enabledInput = enabled
        gainsInput = gainsDb.copyOf(EqSettings.BAND_COUNT)
        if (sampleRate > 0) publish()
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
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
        if (!cfg.enabled || active.isEmpty()) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val ch = channels
        val preamp = cfg.preamp
        val inShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        out.order(ByteOrder.LITTLE_ENDIAN)
        val total = inShorts.remaining()
        var i = 0
        var c = 0
        while (i < total) {
            val st = state[c]
            var s = inShorts.get().toDouble() * preamp
            for (band in active) {
                val k = band * 4
                val bq = co[band]
                val x = s
                val y = bq[0] * x + bq[1] * st[k] + bq[2] * st[k + 1] - bq[3] * st[k + 2] - bq[4] * st[k + 3]
                st[k + 1] = st[k]; st[k] = x
                st[k + 3] = st[k + 2]; st[k + 2] = y
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

    private fun publish() {
        val co = buildCoeffs(gainsInput, sampleRate)
        // Derived from the SAME condition buildCoeffs uses to emit an identity biquad, instead of
        // recovering it by testing two computed doubles for exact equality against 1.0/0.0 — that
        // coupled the audio thread's fast path to floating-point equality, so a change to how the
        // passthrough case is emitted would silently run every flat band through the cascade.
        val active = (0 until EqSettings.BAND_COUNT)
            .filter { isBandActive(it, gainsInput, sampleRate) }
            .toIntArray()
        val maxBoostDb = active.maxOfOrNull { gainsInput[it] }?.coerceAtLeast(0) ?: 0
        val preamp = if (maxBoostDb == 0) 1.0 else 10.0.pow(-maxBoostDb / 20.0)
        generation++
        tuning = Tuning(enabledInput, co, active, preamp, generation)
    }

    private companion object {
        const val Q = 1.0

        /**
         * Bands at or above Nyquist are skipped: the RBJ formula is only valid for 0 < w0 < pi;
         * at or beyond it the poles leave the unit circle and the filter self-oscillates. 0 dB is
         * an exact passthrough, so it is skipped too.
         */
        fun isBandActive(band: Int, gainsDb: IntArray, sampleRate: Int): Boolean =
            gainsDb.getOrElse(band) { 0 } != 0 && EqSettings.BAND_FREQS[band] * 2 < sampleRate

        fun identityCoeffs(): Array<DoubleArray> =
            Array(EqSettings.BAND_COUNT) { doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0) }

        fun buildCoeffs(gainsDb: IntArray, sampleRate: Int): Array<DoubleArray> =
            Array(EqSettings.BAND_COUNT) { band ->
                val gain = gainsDb.getOrElse(band) { 0 }
                val f0 = EqSettings.BAND_FREQS[band]
                if (!isBandActive(band, gainsDb, sampleRate)) {
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
