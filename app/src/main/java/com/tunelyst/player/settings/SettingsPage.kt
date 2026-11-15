package com.tunelyst.player

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.session.MediaController
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Settings UI; preferences and destructive operations remain owned by their controllers. */
internal class SettingsPage(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val content: LinearLayout
        val onSurface: Int
        val muted: Int
        val accent: Int
        val loading: Boolean
        val player: MediaController?
        val settings: TunelystSettings
        val backup: BackupController
        val hiddenCount: Int
        fun dp(value: Int): Int
        fun label(text: String, size: Float, color: Int, bold: Boolean): TextView
        fun back()
        fun render()
        fun hasAccess(): Boolean
        fun scan()
        fun requestAccess()
        fun libraryFilters()
        fun filterSummary(): String
        fun shuffleSettings()
        fun sleepTimer()
        fun duplicateFinder()
        fun clearHistory()
        fun restoreHiddenSongs()
        fun toast(message: String)
        fun iconButton(icon: Int, description: String, action: () -> Unit): android.widget.ImageButton
        fun collectionRow(title: String, detail: String, icon: Int, click: () -> Unit)
    }
    fun render() = with(host) {
        val a = activity
        content.addView(iconButton(R.drawable.ny_back, "Back from settings") { back() },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(4) })
        content.addView(label("Settings", 27f, onSurface, true).apply { setPadding(0, dp(6), 0, dp(8)) })
        fun section(title: String) {
            content.addView(label(title, 14f, muted, true).apply { setPadding(dp(2), dp(20), 0, dp(8)) })
        }
        section("Appearance")
        collectionRow("Device colors", settings.deviceColorsLabel(), R.drawable.ny_palette) { settings.toggleDeviceColors() }
        section("Playback")
        collectionRow("Track transitions", TransitionSettings.label(a), R.drawable.ny_transition) { TransitionSettings.choose(a) { host.render() } }
        collectionRow("Playback speed", "${settings.speed()}× · applies to local playback", R.drawable.ny_speed) {
            settings.chooseSpeed { speed -> player?.setPlaybackSpeed(speed); host.render() }
        }
        collectionRow("Sleep timer", "Duration, end of song and fade-out", R.drawable.ny_history) { sleepTimer() }
        collectionRow("Smart Shuffle controls", "Likes, recent plays and skips", R.drawable.ny_shuffle) { shuffleSettings() }
        collectionRow("Equalizer", "Adjust your playback sound", R.drawable.ny_equalizer) {
            val controller = player
            if (controller == null) toast("Player connecting…") else EqualizerDialog(a, controller, accent).show()
        }
        section("Library & backup")
        collectionRow("Refresh library", if (loading) "Scanning local audio…" else "Scan local audio files again", R.drawable.ny_refresh) { if (hasAccess()) scan() else requestAccess() }
        collectionRow("Library filters", filterSummary(), R.drawable.ny_tab_library) { libraryFilters() }
        collectionRow("Find likely duplicates", "Review only · no automatic deletion", R.drawable.ny_tab_search) { duplicateFinder() }
        collectionRow("Audio access", "Open Android app permissions", R.drawable.ny_shield) { a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))) }
        collectionRow("Export local backup", "Save likes, playlists and listening history", R.drawable.ny_export) { backup.export() }
        collectionRow("Import local backup", "Merge likes and history; add playlist copies", R.drawable.ny_import) { backup.import() }
        collectionRow("Clear listening history", "Keeps liked songs and playlists", R.drawable.ny_history) { clearHistory() }
        if (hiddenCount > 0) collectionRow("Restore hidden Vibes songs", "$hiddenCount hidden from Instant Vibes", R.drawable.ny_refresh) {
            MaterialAlertDialogBuilder(a).setTitle("Restore hidden songs?").setMessage("They can appear in Instant Vibes again. Likes and playlists are unchanged.")
                .setNegativeButton("Cancel", null).setPositiveButton("Restore") { _, _ -> restoreHiddenSongs() }.show()
        }
        section("About")
        collectionRow("About Tunelyst", "Local music, no account or streaming", R.drawable.ny_info) {
            MaterialAlertDialogBuilder(a).setTitle("Tunelyst").setMessage("Your music stays on your device. Smart Shuffle uses local likes, plays and early skips. Tunelyst does not stream or upload your music.").setPositiveButton("Close", null).show()
        }
    }
}
