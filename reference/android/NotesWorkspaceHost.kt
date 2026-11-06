package com.classmate.classmate

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.iko.notes.NotesWorkspace

/** One retained native subtree above Flutter. Only its own rectangle receives native input. */
class NotesWorkspaceHost(private val activity: Activity, private val closed: () -> Unit) {
    private var workspace: NotesWorkspace? = null
    private var resumed = false
    private var saved: Bundle? = null
    private var wantedVisible = false
    private var left = 0
    private var top = 0
    private var width = 0
    private var height = 0
    private var parent: FrameLayout? = null
    private val layoutChanged = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyBounds() }

    val isCreated: Boolean get() = workspace != null
    val isVisible: Boolean get() = workspace?.view?.isShown == true && wantedVisible && resumed

    fun open(intent: Intent) {
        val current = workspace
        if (current != null) {
            applyBounds()
            current.acceptIntent(intent)
            return
        }
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
            ?: throw IllegalStateException("找不到当前窗口内容区域")
        val created = NotesWorkspace(activity, intent, saved, true) {
            setVisible(false)
            closed()
        }
        saved = null
        workspace = created
        parent = content
        content.addOnLayoutChangeListener(layoutChanged)
        content.addView(created.view, FrameLayout.LayoutParams(0, 0))
        if (resumed) created.onHostResume()
        applyBounds()
    }

    /** Physical pixels, measured from the Android window's top-left, including its content offset. */
    fun setBounds(left: Int, top: Int, width: Int, height: Int, visible: Boolean) {
        this.left = left
        this.top = top
        this.width = width.coerceAtLeast(0)
        this.height = height.coerceAtLeast(0)
        wantedVisible = visible
        applyBounds()
    }

    fun setVisible(visible: Boolean) {
        wantedVisible = visible
        applyBounds()
    }

    private fun applyBounds() {
        val current = workspace ?: return
        val content = parent ?: return
        val offset = IntArray(2)
        content.getLocationInWindow(offset)
        val x = left - offset[0]
        val y = top - offset[1]
        val drawable = width > 0 && height > 0 && content.width > 0 && content.height > 0 &&
            x < content.width && y < content.height && x + width > 0 && y + height > 0
        val params = current.view.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height || params.leftMargin != x || params.topMargin != y) {
            params.width = width
            params.height = height
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.leftMargin = x
            params.topMargin = y
            current.view.layoutParams = params
        }
        current.setVisible(wantedVisible && drawable)
    }

    fun restoreState(state: Bundle?) { saved = state?.getBundle("ikoNotesWorkspace") }
    fun saveState(state: Bundle) {
        val current = workspace ?: return
        state.putBundle("ikoNotesWorkspace", Bundle().also(current::saveState))
    }
    fun onResume() { resumed = true; workspace?.onHostResume(); applyBounds() }
    fun onPause() { resumed = false; workspace?.onHostPause() }
    fun onStop() { resumed = false; workspace?.onHostStop() }
    fun onConfigurationChanged(config: Configuration) {
        workspace?.onHostConfigurationChanged(config)
        parent?.post { applyBounds() }
    }
    fun onActivityResult(request: Int, result: Int, data: Intent?): Boolean {
        val current = workspace ?: return false
        if (!current.handlesActivityResult(request)) return false
        current.onActivityResult(request, result, data)
        return true
    }
    fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray): Boolean {
        val current = workspace ?: return false
        if (!current.handlesPermissionResult(request)) return false
        current.onRequestPermissionsResult(request, permissions, results)
        return true
    }
    fun handleBack(): Boolean = workspace?.handleBack() == true
    fun preDispatchKeyEvent(event: KeyEvent): Boolean = workspace?.preDispatchKeyEvent(event) == true
    fun isBusy(): Boolean = workspace?.isBusy == true
    fun flush(reply: (String?) -> Unit) {
        val current = workspace
        if (current == null) reply(null) else current.flush { reply(it) }
    }
    fun whenIdle(reply: (String?) -> Unit) = flush(reply)

    /** Call after flush for library replacement or runtime removal. User files remain untouched. */
    fun disposeWorkspace() {
        val current = workspace ?: return
        current.setVisible(false)
        current.dispose()
        (current.view.parent as? ViewGroup)?.removeView(current.view)
        parent?.removeOnLayoutChangeListener(layoutChanged)
        workspace = null
        parent = null
    }
    fun dispose() { disposeWorkspace() }
}
