package com.remotetap.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.remotetap.model.ButtonConfig
import com.remotetap.model.SerializableRect
import com.remotetap.repository.PreferencesRepository

class RemoteTapAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteTapAccessibilityService? = null
        private const val TAG = "RemoteTap"
    }

    private lateinit var prefs: PreferencesRepository
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recordingOverlay: View? = null
    private var recordingTargetPackage: String? = null
    private var recordingPoller: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = PreferencesRepository(applicationContext)
        // Ensure gesture capability is set so dispatchGesture is not silently dropped.
        // Also declared in accessibility_service_config.xml for new installs.
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelRecordingMode()
        instance = null
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /**
     * Waits for the target app to be in the foreground, then shows a full-screen
     * TYPE_ACCESSIBILITY_OVERLAY to capture the next tap. This prevents the user's
     * navigation gestures (Recents, home, etc.) from being recorded by mistake.
     */
    fun startRecordingMode(targetPackage: String, onRecorded: (ButtonConfig?) -> Unit) {
        cancelRecordingMode()
        recordingTargetPackage = targetPackage

        val poller = object : Runnable {
            override fun run() {
                if (recordingTargetPackage == null) return
                val current = rootInActiveWindow?.also { it.recycle() }?.packageName?.toString()
                if (current == targetPackage) {
                    addRecordingOverlay(onRecorded)
                } else {
                    mainHandler.postDelayed(this, 250L)
                }
            }
        }
        recordingPoller = poller
        mainHandler.postDelayed(poller, 300L)
    }

    private fun addRecordingOverlay(onRecorded: (ButtonConfig?) -> Unit) {
        if (recordingTargetPackage == null) return
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
            if (event.action != MotionEvent.ACTION_DOWN) return@setOnTouchListener true
            val config = captureNodeAtPoint(event.rawX, event.rawY)
            cancelRecordingMode()
            if (config != null) prefs.buttonConfig = config
            onRecorded(config)
            true
        }

        wm.addView(overlay, params)
    }

    fun cancelRecordingMode() {
        recordingPoller?.let { mainHandler.removeCallbacks(it) }
        recordingPoller = null
        recordingTargetPackage = null
        val overlay = recordingOverlay ?: return
        recordingOverlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(overlay) }
    }

    fun isRecording() = recordingOverlay != null || recordingTargetPackage != null

    /**
     * Tries to find the accessibility node at (x, y). For native Android views this
     * captures viewId/text for reliable future matching. For React Native and other
     * frameworks it falls back to saving just the screen coordinates, which are used
     * by [pressRecordedButton] via dispatchGesture.
     */
    private fun captureNodeAtPoint(x: Float, y: Float): ButtonConfig? {
        val bounds = Rect()

        val root = rootInActiveWindow
        if (root != null) {
            val node = findClickableNodeAt(root, x.toInt(), y.toInt())
            if (node != null) {
                node.getBoundsInScreen(bounds)
                val config = ButtonConfig(
                    packageName = node.packageName?.toString() ?: "",
                    viewId = node.viewIdResourceName ?: "",
                    text = node.text?.toString() ?: "",
                    contentDescription = node.contentDescription?.toString() ?: "",
                    className = node.className?.toString() ?: "",
                    boundsInScreen = SerializableRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
                )
                node.recycle()
                return config
            }
            root.recycle()
        }

        // No named node found (e.g. React Native). Save coordinates only so
        // pressRecordedButton can fall back to dispatchGesture.
        return ButtonConfig(
            packageName = recordingTargetPackage ?: "",
            boundsInScreen = SerializableRect(
                left = (x - 1).toInt(), top = (y - 1).toInt(),
                right = (x + 1).toInt(), bottom = (y + 1).toInt()
            )
        )
    }

    private fun findClickableNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableNodeAt(child, x, y)
            if (result != null) return result
            child.recycle()
        }
        return if (node.isClickable) node else null
    }

    /**
     * Called by CommandListenerService when a PRESS command arrives.
     * Tries node-based ACTION_CLICK first; falls back to dispatchGesture at
     * the recorded coordinates (required for React Native and other apps that
     * don't expose named accessibility nodes).
     *
     * If the target app isn't in the foreground and we have a packageName,
     * launch it first and wait briefly for it to appear.
     */
    fun pressRecordedButton(): Boolean {
        val config = prefs.buttonConfig ?: run {
            Log.w(TAG, "pressRecordedButton: no button config saved")
            return false
        }
        Log.d(TAG, "pressRecordedButton: pkg=${config.packageName} viewId=${config.viewId} text=${config.text} coords=(${config.boundsInScreen.centerX()},${config.boundsInScreen.centerY()})")

        // Try node-based click first (works for native Android views)
        val rootNode = rootInActiveWindow
        Log.d(TAG, "rootInActiveWindow pkg=${rootNode?.packageName}")
        if (rootNode != null) {
            val targetNode = findNode(rootNode, config)
            if (targetNode != null) {
                Log.d(TAG, "found node via accessibility tree, performing ACTION_CLICK")
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                targetNode.recycle()
                return true
            }
            rootNode.recycle()
        }

        // Node not found — bring the target app to the foreground if needed, then gesture
        if (config.packageName.isNotEmpty()) {
            val currentPkg = rootInActiveWindow?.also { it.recycle() }?.packageName?.toString()
            if (currentPkg != config.packageName) {
                Log.d(TAG, "target app not in foreground (current=$currentPkg), launching ${config.packageName}")
                val launch = applicationContext.packageManager
                    .getLaunchIntentForPackage(config.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                if (launch != null) {
                    applicationContext.startActivity(launch)
                    // Poll until the target window is active, up to 5 seconds
                    val deadline = System.currentTimeMillis() + 5_000L
                    while (System.currentTimeMillis() < deadline) {
                        val pkg = rootInActiveWindow?.also { it.recycle() }?.packageName?.toString()
                        if (pkg == config.packageName) break
                        Thread.sleep(100)
                    }
                    // Small settle time after the window appears so the UI finishes rendering
                    Thread.sleep(500)
                }
            }
        }

        Log.d(TAG, "falling back to dispatchGesture at (${config.boundsInScreen.centerX()},${config.boundsInScreen.centerY()})")
        return performCoordinateTap(config)
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

    /**
     * Shows a solid red square at the recorded tap coordinates for 3 seconds so the user
     * can verify the gesture will land on the right element. Works by adding a small
     * TYPE_ACCESSIBILITY_OVERLAY precisely positioned at the target pixel.
     */
    fun showTapIndicator() {
        val config = prefs.buttonConfig ?: return
        val cx = config.boundsInScreen.centerX()
        val cy = config.boundsInScreen.centerY()
        Log.d(TAG, "showTapIndicator at ($cx, $cy)")

        val size = 80
        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also { lp ->
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = cx - size / 2
            lp.y = cy - size / 2
        }

        val indicator = View(this)
        indicator.setBackgroundColor(Color.RED)
        wm.addView(indicator, params)
        mainHandler.postDelayed({ runCatching { wm.removeView(indicator) } }, 3_000L)
    }

    private fun performCoordinateTap(config: ButtonConfig): Boolean {
        val x = config.boundsInScreen.centerX().toFloat()
        val y = config.boundsInScreen.centerY().toFloat()
        if (x == 0f && y == 0f) return false
        // lineTo ensures ACTION_MOVE is generated between DOWN and UP, which some
        // React Native gesture handlers require to properly recognise a tap.
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        // dispatchGesture must run on the main thread; its binder callback is
        // silently lost when called from a background thread.
        val latch = CountDownLatch(1)
        var dispatched = false
        mainHandler.post {
            dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.d(TAG, "gesture completed")
                    latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "gesture CANCELLED")
                    latch.countDown()
                }
            }, mainHandler)
            if (!dispatched) latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
        return dispatched
    }
}
