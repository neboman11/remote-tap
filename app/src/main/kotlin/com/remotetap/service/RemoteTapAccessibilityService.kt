package com.remotetap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.remotetap.model.ButtonConfig
import com.remotetap.repository.PreferencesRepository

class RemoteTapAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteTapAccessibilityService? = null
    }

    private lateinit var prefs: PreferencesRepository
    private var recordingOverlay: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = PreferencesRepository(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelRecordingOverlay()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Adds a transparent full-screen overlay via WindowManager (TYPE_ACCESSIBILITY_OVERLAY).
     * The next touch on the overlay captures the node at that point, saves it to prefs,
     * dismisses the overlay, and invokes [onRecorded].
     * Must be called from the accessibility service; only that context can use this window type.
     */
    fun showRecordingOverlay(onRecorded: (ButtonConfig?) -> Unit) {
        cancelRecordingOverlay() // dismiss any previous overlay

        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val overlay = View(this)
        recordingOverlay = overlay

        overlay.setOnTouchListener { _, event ->
            val config = captureNodeAtPoint(event.rawX, event.rawY)
            if (config != null) prefs.buttonConfig = config
            cancelRecordingOverlay()
            onRecorded(config)
            true
        }

        wm.addView(overlay, params)
    }

    fun cancelRecordingOverlay() {
        val overlay = recordingOverlay ?: return
        recordingOverlay = null
        runCatching {
            getSystemService(WindowManager::class.java).removeView(overlay)
        }
    }

    /**
     * Called by CommandListenerService when a PRESS command arrives.
     * Tries to find the recorded button by node info first, falls back to coordinates.
     */
    fun pressRecordedButton(): Boolean {
        val config = prefs.buttonConfig ?: return false

        val rootNode = rootInActiveWindow ?: return performCoordinateTap(config)

        val targetNode = findNode(rootNode, config)
        return if (targetNode != null) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            targetNode.recycle()
            true
        } else {
            performCoordinateTap(config)
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, config: ButtonConfig): AccessibilityNodeInfo? {
        if (config.viewId.isNotEmpty()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(config.viewId)
            if (nodes.isNotEmpty()) return nodes.first()
        }
        if (config.text.isNotEmpty()) {
            val nodes = root.findAccessibilityNodeInfosByText(config.text)
            if (nodes.isNotEmpty()) return nodes.firstOrNull { it.isClickable }
        }
        return null
    }

    private fun performCoordinateTap(config: ButtonConfig): Boolean {
        val x = config.boundsInScreen.centerX().toFloat()
        val y = config.boundsInScreen.centerY().toFloat()
        if (x == 0f && y == 0f) return false

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    /**
     * Called during setup mode: captures the node under the user's tap
     * and saves it as the button config. Triggered from the recording overlay.
     */
    fun captureNodeAtPoint(x: Float, y: Float): ButtonConfig? {
        val root = rootInActiveWindow ?: return null
        val node = findNodeAt(root, x.toInt(), y.toInt()) ?: return null

        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val config = ButtonConfig(
            packageName = node.packageName?.toString() ?: "",
            viewId = node.viewIdResourceName ?: "",
            text = node.text?.toString() ?: "",
            contentDescription = node.contentDescription?.toString() ?: "",
            className = node.className?.toString() ?: "",
            boundsInScreen = com.remotetap.model.SerializableRect(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            )
        )
        node.recycle()
        return config
    }

    private fun findNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeAt(child, x, y)
            if (result != null) return result
            child.recycle()
        }
        return if (node.isClickable) node else null
    }
}
