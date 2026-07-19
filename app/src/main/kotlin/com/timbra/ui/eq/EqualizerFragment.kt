package com.timbra.ui.eq

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import com.timbra.R
import com.timbra.databinding.FragmentEqualizerBinding
import com.timbra.databinding.ItemEqBandBinding
import com.timbra.eqSettings
import com.timbra.player.EqSettings
import com.timbra.ui.MainActivity

/**
 * The 7-band graphic equalizer, opened from the global 3-dot menu. Reads/writes the persisted
 * [EqSettings] and pushes every change to the live DSP via
 * [com.timbra.player.PlayerConnection.applyEq]. Its own overflow offers a single action, Reset.
 */
class EqualizerFragment : Fragment(), MenuProvider {

    private var _b: FragmentEqualizerBinding? = null
    private val b get() = _b!!

    private val player get() = (requireActivity() as MainActivity).player
    private val settings get() = requireContext().eqSettings

    private val faders = arrayOfNulls<VerticalFader>(EqSettings.BAND_COUNT)
    private val seeks = arrayOfNulls<SeekBar>(EqSettings.BAND_COUNT)
    private val gainLabels = arrayOfNulls<TextView>(EqSettings.BAND_COUNT)

    /** Suppresses persistence/apply while we set slider positions programmatically. */
    private var binding = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        _b = FragmentEqualizerBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        requireActivity().addMenuProvider(this, viewLifecycleOwner)

        val inflater = LayoutInflater.from(requireContext())
        val gains = settings.gains()

        for (i in 0 until EqSettings.BAND_COUNT) {
            val row = ItemEqBandBinding.inflate(inflater, b.bands, true)
            row.bandFreq.text = freqLabel(EqSettings.BAND_FREQS[i])
            row.bandSeek.max = EqSettings.MAX_GAIN_DB - EqSettings.MIN_GAIN_DB
            // Set position BEFORE attaching the listener so this initial set doesn't write back.
            row.bandSeek.progress = gains[i] - EqSettings.MIN_GAIN_DB
            row.bandGain.text = gainLabel(gains[i])
            row.bandSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    val db = progress + EqSettings.MIN_GAIN_DB
                    row.bandGain.text = gainLabel(db)
                    if (binding) return
                    settings.setBand(i, db)
                    applyLive()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            // The whole column is the touch target (generous tolerance): map touch Y -> progress.
            row.bandHolder.max = row.bandSeek.max
            row.bandHolder.onValue = { p -> row.bandSeek.progress = p }
            // Turn the horizontal SeekBar into a vertical fader: make it as long as its holder,
            // then rotate -90° (max ends up at the top). Done post-layout so the height is known.
            row.bandHolder.doOnLayout { holder ->
                row.bandSeek.layoutParams = row.bandSeek.layoutParams.also { it.width = holder.height }
                row.bandSeek.rotation = -90f
            }
            faders[i] = row.bandHolder
            seeks[i] = row.bandSeek
            gainLabels[i] = row.bandGain
        }

        b.eqEnable.isChecked = settings.enabled
        setEnableLabel(settings.enabled)
        setBandsEnabled(settings.enabled)
        b.eqEnable.setOnCheckedChangeListener { _, checked ->
            settings.enabled = checked
            setEnableLabel(checked)
            setBandsEnabled(checked)
            applyLive()
        }
    }

    private fun setEnableLabel(enabled: Boolean) {
        b.eqEnableLabel.setText(if (enabled) R.string.eq_on else R.string.eq_off)
    }

    // --- Overflow menu: only Reset ---

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_eq, menu)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_eq_reset) {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.eq_reset_confirm)
                .setPositiveButton(R.string.eq_reset) { _, _ -> resetToFlat() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return true
        }
        return false
    }

    private fun resetToFlat() {
        settings.reset()
        binding = true
        for (i in 0 until EqSettings.BAND_COUNT) {
            seeks[i]?.progress = -EqSettings.MIN_GAIN_DB   // 0 dB -> center
            gainLabels[i]?.text = gainLabel(0)
        }
        binding = false
        applyLive()
    }

    private fun applyLive() = player.applyEq(settings.enabled, settings.gains())

    private fun setBandsEnabled(enabled: Boolean) {
        b.bands.alpha = if (enabled) 1f else 0.4f
        faders.forEach { it?.isEnabled = enabled }
        // Also disable the underlying SeekBars: when a fader stops intercepting (disabled), the
        // still-enabled child SeekBar would otherwise keep receiving touches on its thumb strip.
        seeks.forEach { it?.isEnabled = enabled }
    }

    /** "0", "+3", "-5" (dB). */
    private fun gainLabel(db: Int): String = if (db > 0) "+$db" else db.toString()

    /** "60", "400", "1k", "2.4k", "15k". */
    private fun freqLabel(hz: Int): String = when {
        hz < 1000 -> hz.toString()
        hz % 1000 == 0 -> "${hz / 1000}k"
        else -> "${hz / 1000}.${(hz % 1000) / 100}k"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        faders.fill(null)
        seeks.fill(null)
        gainLabels.fill(null)
        _b = null
    }
}
