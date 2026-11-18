package com.stark.family

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** One thing visible on screen. idx is the number the model refers to. */
class UiNode(
    val idx: Int, val cls: String, val text: String, val desc: String, val viewId: String, val bounds: Rect,
    val clickable: Boolean, val scrollable: Boolean, val editable: Boolean, val checkable: Boolean,
    val checked: Boolean, val password: Boolean, val node: AccessibilityNodeInfo?, val vision: Boolean = false,
) {
    val label: String get() = text.ifBlank { desc }.ifBlank { viewId }
}

class VisionEl(val label: String, val x: Int, val y: Int)

class VisionResult(val summary: String, val els: List<VisionEl>)

class Snapshot(val pkg: String, val nodes: List<UiNode>, val text: String, val hash: Int) {
    val empty: Boolean get() = nodes.isEmpty()

    /** Adds what the screenshot reader saw as extra tappable items (positions are guesses, flagged as such). */
    fun withVision(v: VisionResult, w: Int, h: Int): Snapshot {
        val all = nodes.toMutableList()
        val extra = StringBuilder()
        if (v.summary.isNotBlank()) extra.append("\n--- स्क्रीनशॉट से (दृश्य) ---\nसार: ").append(v.summary)
        v.els.forEach { e ->
            val cx = e.x * w / 1000
            val cy = e.y * h / 1000
            val idx = all.size
            all.add(UiNode(idx, "दृश्य", e.label, "", "", Rect(cx - 70, cy - 40, cx + 70, cy + 40), true, false, false, false, false, false, null, true))
            extra.append("\n[").append(idx).append("] दृश्य (c) \"").append(e.label).append("\" ~(").append(cx).append(",").append(cy).append(")")
        }
        return Snapshot(pkg, all, (text + extra).take(5000), hash * 31 + v.summary.hashCode())
    }
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

    /** Remembers which app is in front (a package name only, never screen content) and wakes "app opened" rules. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val low = pkg.lowercase()
        if (pkg == packageName || low.contains("systemui") || low.contains("inputmethod") || low.endsWith(".ime")) return
        if (pkg == ContextEngine.foregroundPkg) return
        ContextEngine.foregroundPkg = pkg
        RuleEngine.onAppOpened(applicationContext, pkg)
    }
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
            if (node.node?.performAction(act) == true) return true
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
        val nd = n.node ?: return false
        if (n.password || !n.editable) return false
        nd.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return nd.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressEnter(n: UiNode?): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val node = n?.node ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    // ---------------- screenshot (Android 11+), kept in memory only ----------------

    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < 30) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hb = screenshot.hardwareBuffer
                        val bmp = try {
                            Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: Exception) {
                            null
                        }
                        try { hb.close() } catch (_: Exception) { }
                        if (cont.isActive) cont.resume(bmp)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /** JPEG (max 1024px) as base64, or null if the picture is blank/black (secure window). The bitmap is recycled. */
    fun encodeForVision(bmp: Bitmap): String? {
        val scale = 1024f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        var bright = 0
        for (gx in 0 until 24) for (gy in 0 until 24) {
            val c = small.getPixel(gx * (small.width - 1) / 23, gy * (small.height - 1) / 23)
            bright = maxOf(bright, maxOf((c shr 16) and 0xFF, maxOf((c shr 8) and 0xFF, c and 0xFF)))
        }
        val out = ByteArrayOutputStream()
        if (bright >= 8) small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (small !== bmp) small.recycle()
        bmp.recycle()
        return if (out.size() == 0) null else Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
