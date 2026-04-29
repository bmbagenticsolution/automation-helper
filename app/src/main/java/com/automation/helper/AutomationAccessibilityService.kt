package com.automation.helper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The background engine that actually performs taps, swipes, and text input
 * on whatever app is currently in the foreground. Enabled once via:
 *   Settings -> Accessibility -> Automation Helper -> ON
 *
 * Other parts of the app talk to it through the static `instance` reference.
 */
class AutomationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomationSvc"
        @Volatile var instance: AutomationAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() { /* no-op */ }

    // ---------- Public command API ----------

    fun tapAtCoordinates(x: Float, y: Float, callback: (Boolean) -> Unit) {
        val (jx, jy) = HumanBehavior.jitter(x, y).let { HumanBehavior.coerceToScreen(it.first, it.second) }
        val path = Path().apply { moveTo(jx, jy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, HumanBehavior.tapDurationMs()))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback(true) }
            override fun onCancelled(g: GestureDescription?) { callback(false) }
        }, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long? = null, callback: (Boolean) -> Unit) {
        val (sx1, sy1) = HumanBehavior.coerceToScreen(x1, y1)
        val (sx2, sy2) = HumanBehavior.coerceToScreen(x2, y2)
        if (!HumanBehavior.ensureMinDistance(sx1, sy1, sx2, sy2)) {
            callback(false); return
        }
        val path = HumanBehavior.curvedSwipePath(sx1, sy1, sx2, sy2)
        val distance = kotlin.math.sqrt((sx2 - sx1) * (sx2 - sx1) + (sy2 - sy1) * (sy2 - sy1))
        val duration = durationMs ?: HumanBehavior.swipeDurationMs(distance)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback(true) }
            override fun onCancelled(g: GestureDescription?) { callback(false) }
        }, null)
    }

    /**
     * Find the first node whose visible text or content-description matches `text`
     * (case-insensitive substring match), then tap it.
     */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = findNodeByText(root, text.lowercase()) ?: return false
        return performClickOnNodeOrParent(match)
    }

    /**
     * Find the first node whose Android view-id resource name matches `viewId`
     * (e.g. "com.example.app:id/login_button"), then tap it.
     */
    fun tapByViewId(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val node = nodes?.firstOrNull() ?: return false
        return performClickOnNodeOrParent(node)
    }

    /**
     * Type text into whatever input field currently has focus on screen.
     */
    fun typeIntoFocused(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.let { findEditableNode(it) }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pressNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /**
     * Walk the accessibility tree of the current screen and return a flat
     * description of every visible interactive element. Useful as a "what's on
     * screen right now" debug aid.
     */
    fun dumpScreen(): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val sb = StringBuilder()
        dumpNode(root, 0, sb)
        return sb.toString().ifBlank { "(no nodes)" }
    }

    // ---------- Private helpers ----------

    private fun findNodeByText(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        if (text.contains(query) || desc.contains(query)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByText(child, query)?.let { return it }
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableNode(child)?.let { return it }
        }
        return null
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        // Fallback: tap the centre of the node's bounds.
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            tapAtCoordinates(bounds.exactCenterX(), bounds.exactCenterY()) { }
            return true
        }
        return false
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val id = node.viewIdResourceName.orEmpty()
        val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
        if (text.isNotBlank() || desc.isNotBlank() || node.isClickable) {
            val indent = "  ".repeat(depth)
            sb.append(indent).append(cls)
            if (id.isNotBlank()) sb.append(" id=").append(id)
            if (text.isNotBlank()) sb.append(" text=\"").append(text).append('"')
            if (desc.isNotBlank()) sb.append(" desc=\"").append(desc).append('"')
            if (node.isClickable) sb.append(" [clickable]")
            sb.append('\n')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1, sb)
        }
    }
}
