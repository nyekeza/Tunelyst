package com.tunelyst.player

import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.session.MediaController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Owns the queue sheet's lifecycle, sizing and nested back handling. */
internal class QueueDialogController(private val host: Host) {
    interface Host {
        val activity: AppCompatActivity
        val player: MediaController?
        val tracks: List<Track>
        val surface: Int
        val onSurface: Int
        val muted: Int
        val accent: Int
        val tileSurface: Int
        val onTile: Int
        val insetTop: Int
        val insetBottom: Int
        fun dp(value: Int): Int
        fun cover(track: Track, size: Int): View
        fun togglePlayback()
        fun toast(message: String)
    }
    private var dialog: BottomSheetDialog? = null
    private var queue: QueueScreen? = null
    fun refresh(queueChanged: Boolean) { queue?.refresh(queueChanged) }
    fun dismiss() { dialog?.dismiss(); dialog = null; queue = null }
    fun show(): Unit = with(host) {
        val p = player ?: run { toast("Player connecting…"); return }
        if (dialog?.isShowing == true) return
        val a = activity
        val sheetDialog = BottomSheetDialog(a)
        val byId = tracks.associateBy { it.id.toString() }
        var behavior: BottomSheetBehavior<FrameLayout>? = null
        val sheet = QueueScreen(a, p, surface, onSurface, muted, accent, tileSurface, onTile,
            onToggleSize = {
                behavior?.let { it.state = if (it.state == BottomSheetBehavior.STATE_EXPANDED) BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_EXPANDED }
            }, togglePlayback = { togglePlayback() }) { id ->
            byId[id]?.let { cover(it, dp(52)) }
        }
        queue = sheet; dialog = sheetDialog
        val available = (a.resources.displayMetrics.heightPixels - maxOf(insetTop, dp(24)) - insetBottom).coerceAtLeast(dp(240))
        sheetDialog.setContentView(sheet, android.view.ViewGroup.LayoutParams(-1, available))
        sheetDialog.setOnDismissListener { if (dialog === sheetDialog) { dialog = null; queue = null } }
        sheetDialog.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val b = behavior
                if (b?.state == BottomSheetBehavior.STATE_EXPANDED) b.state = BottomSheetBehavior.STATE_COLLAPSED else sheetDialog.dismiss()
            }
        })
        fun updateQueueOcclusion(bottomSheet: View) {
            val parent = bottomSheet.parent as? View ?: return
            if (bottomSheet.height <= 0 || parent.height <= 0) return
            val windowFrame = Rect()
            parent.getWindowVisibleDisplayFrame(windowFrame)
            if (windowFrame.bottom <= windowFrame.top) return
            val sheetLocation = IntArray(2); val parentLocation = IntArray(2)
            bottomSheet.getLocationOnScreen(sheetLocation); parent.getLocationOnScreen(parentLocation)
            sheet.setBottomOcclusion(QueueViewport.obscured(sheetLocation[1], bottomSheet.height,
                parentLocation[1], parent.height, windowFrame.bottom, insetBottom))
        }
        sheetDialog.setOnShowListener {
            val container = sheetDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            container?.setBackgroundColor(Color.TRANSPARENT)
            container?.let {
                behavior = BottomSheetBehavior.from(it).apply {
                    isDraggable = true; isHideable = true; skipCollapsed = false
                    peekHeight = (available * 0.58f).toInt().coerceAtLeast(minOf(dp(350), available))
                    addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                        override fun onSlide(bottomSheet: View, slideOffset: Float) { updateQueueOcclusion(bottomSheet) }
                        override fun onStateChanged(bottomSheet: View, newState: Int) {
                            if (newState == BottomSheetBehavior.STATE_EXPANDED || newState == BottomSheetBehavior.STATE_COLLAPSED) {
                                sheet.setExpanded(newState == BottomSheetBehavior.STATE_EXPANDED)
                                updateQueueOcclusion(bottomSheet)
                            }
                            if (newState == BottomSheetBehavior.STATE_HIDDEN && sheetDialog.isShowing) sheetDialog.dismiss()
                        }
                    })
                    state = BottomSheetBehavior.STATE_COLLAPSED
                }
                sheet.setExpanded(false)
                it.post { if (sheetDialog.isShowing) updateQueueOcclusion(it) }
            }
        }
        sheetDialog.show()
    }
}
