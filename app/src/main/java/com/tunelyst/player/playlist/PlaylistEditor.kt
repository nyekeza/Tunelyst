package com.tunelyst.player

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executor

/** Playlist dialogs are independent of the main screen renderer. */
internal class PlaylistEditor(private val activity: AppCompatActivity, private val io: Executor, private val onChanged: () -> Unit) {
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    fun rename(id: Long, current: String) {
        val input = EditText(activity).apply { setSingleLine(); setText(current); setSelection(text.length) }
        MaterialAlertDialogBuilder(activity).setTitle("Rename playlist").setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().take(120)
                if (name.isNotEmpty()) io.execute {
                    val ok = runCatching { LibraryDb(activity.applicationContext).use { it.renamePlaylist(id, name) } }.getOrDefault(false)
                    activity.runOnUiThread { if (!activity.isDestroyed && ok) onChanged() }
                }
            }.show()
    }
    /** Draft edits are local until Save; Cancel never changes the playlist. */
    fun edit(id: Long, original: List<Long>, tracks: List<Track>) {
        val byId = tracks.associateBy { it.id }
        val draft = original.toMutableList()
        val selected = mutableSetOf<Long>()
        var undo: List<Long>? = null
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0) }
        val info = TextView(activity).apply { text = "Hold and drag to reorder, or use arrows. Select songs to remove."; textSize = 12f }
        box.addView(info)
        val list = ListView(activity).apply { isVerticalScrollBarEnabled = false; dividerHeight = 0 }
        val adapter = object : BaseAdapter() {
            override fun getCount() = draft.size
            override fun getItem(position: Int) = draft[position]
            override fun getItemId(position: Int) = draft[position]
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                // Each control has a 48dp target, including accessible arrow alternatives to dragging.
                val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
                val idHere = draft[position]; val song = byId[idHere]
                val label = TextView(activity).apply { text = if (song == null) "Unavailable song ($idHere)" else "${song.title} · ${song.artist}"; maxLines = 2; textSize = 14f; TrackTouchFeedback.apply(this) }
                row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                fun move(delta: Int) {
                    val from = draft.indexOf(idHere); val to = from + delta
                    if (from >= 0 && to in draft.indices) { draft.removeAt(from); draft.add(to, idHere); notifyDataSetChanged() }
                }
                fun arrow(symbol: String, description: String, delta: Int) = android.widget.Button(activity).apply {
                    text = symbol; contentDescription = description; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0); setOnClickListener { move(delta) }
                }
                row.addView(arrow("↑", "Move ${song?.title ?: "unavailable song"} up", -1), LinearLayout.LayoutParams(dp(48), dp(48)))
                row.addView(arrow("↓", "Move ${song?.title ?: "unavailable song"} down", 1), LinearLayout.LayoutParams(dp(48), dp(48)))
                val check = CheckBox(activity).apply {
                    isChecked = idHere in selected; contentDescription = "Select ${song?.title ?: "unavailable song"} for removal"
                    setOnCheckedChangeListener { _, checked -> if (checked) selected.add(idHere) else selected.remove(idHere) }
                }
                row.addView(check, LinearLayout.LayoutParams(dp(48), dp(48)))
                label.contentDescription = "${song?.title ?: "Unavailable song"}. Hold and drag to reorder."
                label.setOnLongClickListener { view -> view.startDragAndDrop(android.content.ClipData.newPlainText("playlist item", ""), View.DragShadowBuilder(view), idHere, 0) }
                row.setOnDragListener { _, event ->
                    when (event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is Long
                        android.view.DragEvent.ACTION_DROP -> {
                            val moving = event.localState as? Long; val from = moving?.let { draft.indexOf(it) } ?: -1; val to = draft.indexOf(idHere)
                            if (from >= 0 && to >= 0 && from != to) { draft.removeAt(from); draft.add(to, moving!!); notifyDataSetChanged() }; true
                        }
                        else -> true
                    }
                }
                return row
            }
        }
        list.adapter = adapter
        box.addView(list, LinearLayout.LayoutParams(-1, dp((activity.resources.configuration.screenHeightDp / 2).coerceIn(180, 360))))
        val controls = LinearLayout(activity).apply { gravity = Gravity.CENTER }
        fun action(text: String, run: () -> Unit) = android.widget.Button(activity).apply { this.text = text; isAllCaps = false; setOnClickListener { run() } }
        controls.addView(action("Remove selected") {
            if (selected.isNotEmpty()) { undo = draft.toList(); draft.removeAll(selected); selected.clear(); adapter.notifyDataSetChanged() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(action("Undo removal") {
            undo?.let { draft.clear(); draft.addAll(it); selected.clear(); undo = null; adapter.notifyDataSetChanged() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(controls)
        MaterialAlertDialogBuilder(activity).setTitle("Edit playlist").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val edited = draft.toList()
                io.execute {
                    val ok = runCatching { LibraryDb(activity.applicationContext).use { it.replacePlaylistOrder(id, original, edited) } }.getOrDefault(false)
                    activity.runOnUiThread { if (!activity.isDestroyed) {
                        android.widget.Toast.makeText(activity, if (ok) "Playlist updated" else "Playlist changed; reopen and try again", android.widget.Toast.LENGTH_SHORT).show()
                        if (ok) onChanged()
                    } }
                }
            }.show()
    }
    fun addSongs(id: Long, tracks: List<Track>) {
        val chosen = mutableSetOf<Long>()
        var shown = tracks
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val search = EditText(activity).apply { hint = "Filter songs"; setSingleLine() }
        box.addView(search, LinearLayout.LayoutParams(-1, -2))
        val list = ListView(activity).apply { isVerticalScrollBarEnabled = false; isFastScrollEnabled = false }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = shown[position].id
            override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
                val row = (recycled as? LinearLayout) ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
                    TrackTouchFeedback.apply(this)
                    val text = TextView(activity).apply { textSize = 15f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
                    addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(CheckBox(activity).apply { isClickable = false; isFocusable = false }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
                val song = shown[position]
                (row.getChildAt(0) as TextView).text = "${song.title} · ${song.artist}"
                (row.getChildAt(1) as CheckBox).isChecked = song.id in chosen
                row.contentDescription = "${song.title}, ${song.artist}, ${if (song.id in chosen) "selected" else "not selected"}"
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val id = shown[position].id
            if (!chosen.add(id)) chosen.remove(id)
            adapter.notifyDataSetChanged()
        }
        box.addView(list, LinearLayout.LayoutParams(-1, dp(360)))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty().trim()
                shown = if (q.isEmpty()) tracks else tracks.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        MaterialAlertDialogBuilder(activity).setTitle("Add songs").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Add selected") { _, _ ->
                val ids = tracks.filter { it.id in chosen }.map { it.id }
                if (ids.isNotEmpty()) io.execute {
                    val result = runCatching { LibraryDb(activity.applicationContext).use { it.addManyToPlaylist(id, ids) } }
                    activity.runOnUiThread { if (!activity.isDestroyed) {
                        android.widget.Toast.makeText(activity, result.fold({ "Added $it songs" }, { "Couldn't add songs" }), android.widget.Toast.LENGTH_SHORT).show()
                        if (result.isSuccess) onChanged()
                    } }
                }
            }.show()
    }
}
