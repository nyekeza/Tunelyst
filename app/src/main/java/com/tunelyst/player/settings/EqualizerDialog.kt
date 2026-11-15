package com.tunelyst.player

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** The effect is owned by PlaybackService; this dialog only sends local session commands. */
internal class EqualizerDialog(private val activity: AppCompatActivity, private val player: MediaController, private val accent: Int) {
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private var request = 0
    private fun text(value: String, size: Float = 14f) = TextView(activity).apply { this.text = value; textSize = size }

    fun show() {
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(body) }
        dialog = MaterialAlertDialogBuilder(activity).setTitle("Equalizer").setView(scroll).setNegativeButton("Close", null).create().also { it.show() }
        load()
    }

    private fun send(mode: String, args: Bundle = Bundle(), completed: (SessionResult?) -> Unit) {
        args.putString("mode", mode)
        val future = try { player.sendCustomCommand(SessionCommand(PlaybackService.EQUALIZER_COMMAND, Bundle.EMPTY), args) }
            catch (_: Exception) { completed(null); return }
        future.addListener({
            if (!activity.isDestroyed && dialog?.isShowing == true) completed(runCatching { future.get() }.getOrNull())
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun change(mode: String, args: Bundle = Bundle()) {
        send(mode, args) { result ->
            if (result?.resultCode != SessionResult.RESULT_SUCCESS) Toast.makeText(activity, "Could not change the equalizer on this device", Toast.LENGTH_SHORT).show()
            load()
        }
    }

    private fun load() {
        val thisRequest = ++request
        body.removeAllViews(); body.addView(text("Checking audio effects…"))
        send("get") { result ->
            if (thisRequest != request) return@send
            if (result?.resultCode != SessionResult.RESULT_SUCCESS) {
                body.removeAllViews(); body.addView(text("Equalizer is unavailable while the player is disconnected.")); return@send
            }
            render(result.extras)
        }
    }

    private fun render(state: Bundle) {
        body.removeAllViews()
        if (!state.getBoolean("available")) {
            body.addView(text(state.getString("message") ?: "Equalizer is unavailable on this device."))
            val retry = Button(activity).apply { text = "Try again"; isAllCaps = false; setOnClickListener { load() } }
            body.addView(retry, LinearLayout.LayoutParams(-2, -2))
            return
        }
        val enabled = state.getBoolean("enabled")
        body.addView(text("Applies to Tunelyst playback. Available controls depend on your device.", 12f))
        val toggle = SwitchCompat(activity).apply {
            text = "Equalizer"; isChecked = enabled; minHeight = dp(48)
            setOnCheckedChangeListener { _, checked -> change("enabled", Bundle().apply { putBoolean("enabled", checked) }) }
        }
        body.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        val presets = state.getStringArrayList("presets").orEmpty()
        if (presets.isNotEmpty()) {
            val preset = Button(activity).apply { text = "Presets"; isAllCaps = false; isEnabled = enabled }
            preset.setOnClickListener {
                MaterialAlertDialogBuilder(activity).setTitle("Equalizer preset").setItems(presets.toTypedArray()) { _, index ->
                    change("preset", Bundle().apply { putInt("preset", index) })
                }.setNegativeButton("Cancel", null).show()
            }
            body.addView(preset, LinearLayout.LayoutParams(-1, -2))
        }
        val frequencies = state.getIntArray("frequencies") ?: IntArray(0)
        val levels = state.getIntArray("levels") ?: IntArray(0)
        val min = state.getInt("min"); val max = state.getInt("max")
        if (max < min || frequencies.size != levels.size || frequencies.isEmpty()) {
            body.addView(text("No adjustable bands are available.")); return
        }
        frequencies.indices.forEach { band ->
            val hz = frequencies[band] / 1000f
            val frequency = if (hz >= 1000) String.format(Locale.getDefault(), "%.1f kHz", hz / 1000) else String.format(Locale.getDefault(), "%.0f Hz", hz)
            fun levelText(level: Int) = String.format(Locale.getDefault(), "%s · %+.1f dB", frequency, level / 100f)
            val caption = text(levelText(levels[band])).apply { setPadding(0, dp(8), 0, 0) }
            body.addView(caption)
            val bar = SeekBar(activity).apply barApply@ {
                this.max = max - min; progress = (levels[band] - min).coerceIn(0, max - min)
                isEnabled = enabled; progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
                contentDescription = "$frequency equalizer level"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    private var dragging = false
                    private val commit = Runnable {
                        change("band", Bundle().apply { putInt("band", band); putInt("level", min + this@barApply.progress) })
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) { dragging = true; removeCallbacks(commit) }
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            caption.text = levelText(min + progress)
                            if (!dragging) { removeCallbacks(commit); postDelayed(commit, 350) }
                        }
                    }
                    override fun onStopTrackingTouch(seekBar: SeekBar?) { dragging = false; removeCallbacks(commit); commit.run() }
                })
            }
            body.addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val reset = Button(activity).apply { text = "Reset to flat"; isAllCaps = false; isEnabled = enabled; setOnClickListener { change("reset") } }
        body.addView(reset, LinearLayout.LayoutParams(-1, -2))
    }
}
