package com.stark.family

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** One thing visible on screen. idx is the number the model refers to. */
class UiNode(
    val idx: Int, val cls: String, val text: String, val desc: String, val viewId: String, val bounds: Rect,
    val clickable: Boolean, val scrollable: Boolean, val editable: Boolean, val checkable: Boolean,
    val checked: Boolean, val password: Boolean, val node: AccessibilityNodeInfo,
) {
    val label: String get() = text.ifBlank { desc }.ifBlank { viewId }
}

class Snapshot(val pkg: String, val nodes: List<UiNode>, val text: String, val hash: Int) {
    val empty: Boolean get() = nodes.isEmpty()
}

/**
 * The agent's eyes and hands. Observe = node tree (never screenshots in v1); Act = performAction / gestures.
 * Passwords are never exposed, and card/OTP-like numbers are redacted before anything leaves this class.
 */
class AgentAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
        fun enabled(): Boolean = instance != null

        private val CARD = Regex("(?:\\d[ -]?){13,19}")
        private val OTP_WORD = Regex("otp|ओटीपी|verification|code|कोड|\\bpin\\b|पिन", RegexOption.IGNORE_CASE)
        private val DIGITS = Regex("\\b\\d{4,8}\\b")

        fun redact(s: String): String {
            var t = CARD.replace(s, "[संख्या छिपी]")
            if (OTP_WORD.containsMatchIn(t)) t = DIGITS.replace(t, "[कोड छिपा]")
            return t
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------------- observe ----------------

    fun snapshot(): Snapshot {
        val root = rootInActiveWindow ?: return Snapshot("", emptyList(), "", 0)
        val pkg = root.packageName?.toString() ?: ""
        val out = mutableListOf<UiNode>()

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.size >= 120 || depth > 40) return
            if (n.isVisibleToUser) {
                val pw = n.isPassword
                val text = if (pw) "" else redact(n.text?.toString()?.trim().orEmpty()).take(70)
                val desc = redact(n.contentDescription?.toString()?.trim().orEmpty()).take(70)
                val interactive = n.isClickable || n.isScrollable || n.isEditable || n.isCheckable
                if (text.isNotEmpty() || desc.isNotEmpty() || interactive) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        out.add(
                            UiNode(
                                out.size, (n.className?.toString() ?: "").substringAfterLast('.'), text, desc,
                                n.viewIdResourceName?.substringAfterLast('/') ?: "", r,
                                n.isClickable, n.isScrollable, n.isEditable, n.isCheckable, n.isChecked, pw, n,
                            ),
                        )
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)

        val lines = out.joinToString("\n") { n ->
            val flags = buildString {
                if (n.clickable) append("c")
                if (n.scrollable) append("s")
                if (n.editable) append(if (n.password) "e,पासवर्ड-फ़ील्ड" else "e")
                if (n.checkable) append(if (n.checked) "k:चालू" else "k:बंद")
            }
            "[${n.idx}] ${n.cls}${if (flags.isEmpty()) "" else " ($flags)"} \"${n.label}\""
        }.take(3500)
        val hash = out.joinToString("|") { "${it.text}${it.desc}${it.bounds}" }.hashCode()
        return Snapshot(pkg, out, lines, hash)
    }

    // ---------------- act ----------------

    suspend fun click(n: UiNode): Boolean {
        var cur: AccessibilityNodeInfo? = n.node
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        return tapAt(n.bounds.centerX(), n.bounds.centerY())
    }

    suspend fun longClick(n: UiNode): Boolean {
        var cur: AccessibilityNodeInfo? = n.node
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            cur = cur.parent
        }
        val p = Path().apply { moveTo(n.bounds.centerX().toFloat(), n.bounds.centerY().toFloat()) }
        return gesture(p, 700)
    }

    suspend fun scroll(target: UiNode?, snap: Snapshot, down: Boolean): Boolean {
        val node = target?.takeIf { it.scrollable }
            ?: snap.nodes.filter { it.scrollable }.maxByOrNull { it.bounds.width() * it.bounds.height() }
        if (node != null) {
            val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.node.performAction(act)) return true
        }
        return swipeDir(if (down) "up" else "down")
    }

    /** direction = which way the finger moves. */
    suspend fun swipeDir(finger: String): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels
        val h = m.heightPixels
        val p = Path()
        when (finger) {
            "up" -> { p.moveTo(w / 2f, h * 0.72f); p.lineTo(w / 2f, h * 0.28f) }
            "down" -> { p.moveTo(w / 2f, h * 0.28f); p.lineTo(w / 2f, h * 0.72f) }
            "left" -> { p.moveTo(w * 0.8f, h / 2f); p.lineTo(w * 0.2f, h / 2f) }
            else -> { p.moveTo(w * 0.2f, h / 2f); p.lineTo(w * 0.8f, h / 2f) }
        }
        return gesture(p, 350)
    }

    suspend fun tapAt(x: Int, y: Int): Boolean = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60)

    private suspend fun gesture(path: Path, ms: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        val started = dispatchGesture(
            g,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            },
            null,
        )
        if (!started && cont.isActive) cont.resume(false)
    }

    /** Never types into password fields. */
    fun typeText(n: UiNode, text: String): Boolean {
        if (n.password || !n.editable) return false
        n.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(n: UiNode?): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val node = n?.node ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
