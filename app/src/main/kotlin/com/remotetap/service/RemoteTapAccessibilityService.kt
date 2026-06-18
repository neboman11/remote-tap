package com.remotetap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.remotetap.model.ButtonConfig
import com.remotetap.model.SerializableRect
import com.remotetap.repository.PreferencesRepository

class RemoteTapAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteTapAccessibilityService? = null
    }

    private lateinit var prefs: PreferencesRepository

    // Non-null while the user is in "tap the button you want to record" mode.
    private var onRecordingCallback: ((ButtonConfig?) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = PreferencesRepository(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        onRecordingCallback = null
        instance = null
    }

    override fun onInterrupt() {}

    /**
     * When a click event arrives and we're in recording mode, capture the source node
     * and save it as the button config. Ignores clicks inside RemoteTap itself so that
     * tapping "Cancel" in ButtonRecordingActivity doesn't accidentally record a button.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val callback = onRecordingCallback ?: return
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return

        val source = event.source ?: return
        if (source.packageName?.toString() == applicationContext.packageName) {
            source.recycle()
            return
        }

        val bounds = Rect()
        source.getBoundsInScreen(bounds)
        val config = ButtonConfig(
            packageName = source.packageName?.toString() ?: "",
            viewId = source.viewIdResourceName ?: "",
            text = source.text?.toString() ?: "",
            contentDescription = source.contentDescription?.toString() ?: "",
            className = source.className?.toString() ?: "",
            boundsInScreen = SerializableRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        )
        source.recycle()

        onRecordingCallback = null
        prefs.buttonConfig = config
        callback(config)
    }

    /**
     * Enter recording mode: the next tap in any other app is captured and saved.
     * Call [cancelRecordingMode] to exit without recording.
     */
    fun startRecordingMode(onRecorded: (ButtonConfig?) -> Unit) {
        onRecordingCallback = onRecorded
    }

    fun cancelRecordingMode() {
        onRecordingCallback = null
    }

    fun isRecording() = onRecordingCallback != null

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
}
