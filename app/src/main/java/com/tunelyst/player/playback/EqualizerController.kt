package com.tunelyst.player

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.os.Bundle
import androidx.media3.common.Player

/** Owns the platform equalizer for this player's audio session, including background playback. */
internal class EqualizerController(context: Context, private val player: Player) : Player.Listener {
    private val prefs = context.getSharedPreferences("tunelyst_equalizer", Context.MODE_PRIVATE)
    private var equalizer: Equalizer? = null
    private var sessionId = 0
    private var failure = "Start playing music to use the equalizer."

    init {
        player.addListener(this)
        attach(player.audioSessionId)
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) { attach(audioSessionId) }

    private fun attach(id: Int) {
        if (id == sessionId && equalizer != null) return
        runCatching { equalizer?.release() }; equalizer = null; sessionId = id
        if (id <= 0) { failure = "Start playing music to use the equalizer."; return }
        val effect = try { Equalizer(0, id) } catch (_: Exception) {
            failure = "Equalizer is not available on this device or audio output."
            return
        }
        try {
            if (!effect.hasControl()) { effect.release(); failure = "Another audio effect currently controls the equalizer."; return }
            val range = effect.bandLevelRange
            for (band in 0 until effect.numberOfBands.toInt()) {
                val key = "band_${effect.getCenterFreq(band.toShort())}"
                if (prefs.contains(key)) effect.setBandLevel(band.toShort(), prefs.getInt(key, 0).coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
            val enabled = prefs.getBoolean("enabled", false)
            if (effect.setEnabled(enabled) != AudioEffect.SUCCESS) throw IllegalStateException("Could not set equalizer state")
            equalizer = effect; failure = ""
        } catch (_: Exception) {
            effect.release(); failure = "Equalizer could not be initialized for this audio output."
        }
    }

    fun snapshot(): Bundle {
        // An output or another effect may have temporarily blocked creation.
        if (equalizer == null && sessionId > 0) attach(sessionId)
        val effect = equalizer
        if (effect == null) return Bundle().apply { putBoolean("available", false); putString("message", failure) }
        return try {
            if (!effect.hasControl()) return Bundle().apply { putBoolean("available", false); putString("message", "Another audio effect currently controls the equalizer.") }
            val count = effect.numberOfBands.toInt()
            val range = effect.bandLevelRange
            Bundle().apply {
                putBoolean("available", true); putBoolean("enabled", effect.enabled)
                putInt("min", range[0].toInt()); putInt("max", range[1].toInt())
                putIntArray("frequencies", IntArray(count) { effect.getCenterFreq(it.toShort()) })
                putIntArray("levels", IntArray(count) { effect.getBandLevel(it.toShort()).toInt() })
                putStringArrayList("presets", ArrayList((0 until effect.numberOfPresets.toInt()).map { effect.getPresetName(it.toShort()) }))
            }
        } catch (_: Exception) { Bundle().apply { putBoolean("available", false); putString("message", "Equalizer is temporarily unavailable.") } }
    }

    fun apply(mode: String, args: Bundle): Boolean {
        val effect = equalizer ?: return false
        return try {
            if (!effect.hasControl()) return false
            when (mode) {
                "enabled" -> {
                    val value = args.getBoolean("enabled", false)
                    if (effect.setEnabled(value) != AudioEffect.SUCCESS) return false
                    prefs.edit().putBoolean("enabled", value).apply()
                }
                "band" -> {
                    val band = args.getInt("band", -1)
                    if (band !in 0 until effect.numberOfBands.toInt()) return false
                    val range = effect.bandLevelRange
                    val level = args.getInt("level", 0).coerceIn(range[0].toInt(), range[1].toInt())
                    effect.setBandLevel(band.toShort(), level.toShort())
                    saveBands(effect)
                }
                "preset" -> {
                    val preset = args.getInt("preset", -1)
                    if (preset !in 0 until effect.numberOfPresets.toInt()) return false
                    effect.usePreset(preset.toShort()); saveBands(effect)
                }
                "reset" -> {
                    val range = effect.bandLevelRange
                    val flat = 0.coerceIn(range[0].toInt(), range[1].toInt()).toShort()
                    for (band in 0 until effect.numberOfBands.toInt()) effect.setBandLevel(band.toShort(), flat)
                    saveBands(effect)
                }
                else -> return false
            }
            true
        } catch (_: Exception) { false }
    }

    private fun saveBands(effect: Equalizer) {
        val editor = prefs.edit()
        for (band in 0 until effect.numberOfBands.toInt()) {
            val b = band.toShort()
            editor.putInt("band_${effect.getCenterFreq(b)}", effect.getBandLevel(b).toInt())
        }
        editor.apply()
    }

    fun release() {
        player.removeListener(this)
        runCatching { equalizer?.release() }; equalizer = null; sessionId = 0
    }
}
