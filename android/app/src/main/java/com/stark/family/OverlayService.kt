package com.stark.family

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Mode { BUBBLE, PICKER, CHAT, WORKING }

/** What the small "working" strip shows while a screen task runs. */
data class AgentHud(val line: String = "", val step: Int = 0, val max: Int = AgentRunner.MAX_STEPS, val paused: Boolean = false, val sending: Boolean = false)

/** Click-through full-screen layer that outlines the element about to be tapped. */
class HighlightView(ctx: Context, private val color: Int) : View(ctx) {
    var rect: Rect? = null
        set(v) { field = v; invalidate() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 14f; alpha = 70 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }

    override fun onDraw(c: Canvas) {
        val r = rect ?: return
        glow.color = color
        glow.alpha = 70
        line.color = color
        val f = RectF(r).apply { inset(-8f, -8f) }
        c.drawRoundRect(f, 24f, 24f, glow)
        c.drawRoundRect(f, 24f, 24f, line)
    }
}

/** A risky action waiting for the user's yes/no. Nothing runs until answer completes with true. */
class Pending(val text: String, val answer: CompletableDeferred<Boolean>)

/** Lets Compose run inside a Service (no Activity): lifecycle + saved state + view-model store. */
private class ServiceOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val reg = LifecycleRegistry(this)
    private val ssc = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = reg
    override val savedStateRegistry: SavedStateRegistry get() = ssc.savedStateRegistry

    init {
        ssc.performAttach()
        ssc.performRestore(null)
        reg.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        reg.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}

class OverlayService : Service() {
    companion object {
        const val ACTION_STOP = "com.stark.family.STOP_OVERLAY"
        const val ACTION_KILL = "com.stark.family.KILL_TASK"
        private const val CHANNEL = "stark_overlay"
        private const val NOTIF_ID = 11
        var running by mutableStateOf(false)
    }

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var prefs: Prefs
    private lateinit var repo: MemoryRepo
    private val owner = ServiceOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = RelayClient()
    private var view: ComposeView? = null
    private var job: Job? = null
    private var collapseJob: Job? = null
    private var savedX = 0
    private var savedY = 0

    private var mode by mutableStateOf(Mode.BUBBLE)
    private var character by mutableStateOf(Characters.JARVIS)
    private var said by mutableStateOf("")
    private var input by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var waking by mutableStateOf(false)
    private var pending by mutableStateOf<Pending?>(null)
    private var hud by mutableStateOf(AgentHud())
    private var report by mutableStateOf<String?>(null)
    private var lastGoal = ""
    private var lastWho = ""
    private var agentRunning by mutableStateOf(false)
    private var pauseGate: CompletableDeferred<Unit>? = null
    private var ignoreTouchUntil = 0L
    private var highlightView: HighlightView? = null
    private val results = mutableStateListOf<String>()
    private val hist = mutableMapOf<String, MutableList<Pair<String, String>>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = Prefs(this)
        repo = MemoryRepo.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_KILL) {
            if (view != null) stopTask()  // kill switch for a running screen task
            return START_NOT_STICKY
        }
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) showOverlay()
        running = true
        scope.launch { repo.maintain(prefs.relayUrl, prefs.token) }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Stark बबल", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val kill = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_KILL), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Stark Family बबल चालू है")
            .setContentText("परिवार किसी भी ऐप के ऊपर बुलाया जा सकता है")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "⏹ काम रोको", kill).build())
            .addAction(Notification.Action.Builder(0, "बंद करें", stop).build())
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
        }
        savedX = lp.x
        savedY = lp.y
        val v = ComposeView(this)
        v.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) onOutsideTouch()
            false
        }
        v.setViewTreeLifecycleOwner(owner)
        v.setViewTreeSavedStateRegistryOwner(owner)
        v.setViewTreeViewModelStoreOwner(owner)
        v.setContent {
            OverlayUi(
                mode = mode, ch = character, said = said, busy = busy, waking = waking, input = input,
                results = results, pendingText = pending?.text, hud = hud, onResume = { resume() }, report = report, onRerun = { rerun() },
                onDecision = { pending?.answer?.complete(it) },
                onInput = { input = it; scheduleCollapse() },
                onSend = { send(it) },
                onPick = { summon(it) },
                onBubbleTap = { changeMode(if (mode == Mode.BUBBLE) Mode.PICKER else Mode.BUBBLE) },
                onDrag = { dx, dy -> drag(dx, dy) },
                onCollapse = { changeMode(Mode.BUBBLE) },
                onStop = { stopTask() },
            )
        }
        view = v
        wm.addView(v, lp)
    }

    private fun update() {
        val v = view ?: return
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) { }
    }

    private fun drag(dx: Float, dy: Float) {
        lp.x += dx.toInt()
        lp.y += dy.toInt()
        update()
    }

    private fun changeMode(m: Mode) {
        if (m == mode) return
        if (mode == Mode.BUBBLE) {
            savedX = lp.x
            savedY = lp.y
        }
        mode = m
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (m == Mode.CHAT) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            (if (m == Mode.WORKING) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0)
        if (m == Mode.BUBBLE) {
            lp.x = savedX
            lp.y = savedY
        } else {
            lp.x = ((resources.displayMetrics.widthPixels - dp(300)) / 2).coerceAtLeast(0)
            lp.y = if (m == Mode.WORKING) dp(36) else dp(80)
        }
        update()
        if (m == Mode.BUBBLE || m == Mode.WORKING) collapseJob?.cancel() else scheduleCollapse()
    }

    private fun summon(c: Character) {
        character = c
        said = ""
        input = ""
        report = null
        results.clear()
        changeMode(Mode.CHAT)
    }

    private fun scheduleCollapse() {
        collapseJob?.cancel()
        collapseJob = scope.launch {
            delay(25_000)
            if (!busy && !agentRunning && pending == null && input.isBlank()) changeMode(Mode.BUBBLE)
        }
    }

    /** Low-risk tools run at once; anything risky waits for an explicit "हाँ" on screen. Results are honest. */
    private suspend fun runTools(calls: List<ToolCall>, who: String) {
        for (c in calls.take(3)) {
            if (c.name == "screen_task") {
                runAgent(c.args.optString("goal"), who)
                continue
            }
            val plan = withContext(Dispatchers.IO) { Tools.plan(applicationContext, c, who) }
            if (plan.description.isNotBlank() && (plan.risk != Risk.LOW || Tools.needsConfirm(c))) {
                val ask = CompletableDeferred<Boolean>()
                pending = Pending(plan.description, ask)
                collapseJob?.cancel()
                val yes = try { ask.await() } finally { pending = null }
                if (!yes) {
                    results.add("✗ रद्द किया: ${plan.description}")
                    continue
                }
            }
            val res = plan.run()
            results.add((if (res.ok) "✓ " else "✗ ") + res.message)
        }
    }

    // ---------------- screen tasks (Agent Engine) ----------------

    private fun onOutsideTouch() {
        if (!agentRunning || pauseGate != null || System.currentTimeMillis() < ignoreTouchUntil) return
        pauseGate = CompletableDeferred()
        hud = hud.copy(paused = true)
    }

    private fun resume() {
        pauseGate?.complete(Unit)
        pauseGate = null
        hud = hud.copy(paused = false)
    }

    private fun showHighlight(r: Rect?) {
        if (r == null) {
            highlightView?.rect = null
            return
        }
        if (highlightView == null) {
            val hv = HighlightView(this, character.accent.toArgb())
            val hlp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            try { wm.addView(hv, hlp); highlightView = hv } catch (_: Exception) { }
        }
        highlightView?.rect = r
    }

    private fun hideHighlight() {
        highlightView?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        highlightView = null
    }

    private suspend fun runAgent(goal: String, who: String) {
        if (goal.isBlank()) return
        if (!AgentAccessibilityService.enabled()) {
            results.add("✗ स्क्रीन-एजेंट चालू नहीं है। Accessibility सेटिंग खुल रही है: 'Stark Family एजेंट' चालू कीजिए (और ऐप में 📋 में पूरा तरीक़ा लिखा है)।")
            try { openAccessibilitySettings(this) } catch (_: Exception) { }
            return
        }
        val ui = object : AgentUi {
            override fun narrate(text: String) { hud = hud.copy(line = text); said = text }
            override fun status(step: Int, max: Int) { hud = hud.copy(step = step, max = max) }
            override fun highlight(r: Rect?) = showHighlight(r)
            override fun markGesture() { ignoreTouchUntil = System.currentTimeMillis() + 1800 }
            override fun sending(on: Boolean) { hud = hud.copy(sending = on) }
            override suspend fun hideForCapture(hide: Boolean) {
                view?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
                highlightView?.visibility = if (hide) View.INVISIBLE else View.VISIBLE
                if (hide) delay(280)
            }
            override suspend fun confirm(text: String): Boolean {
                val ask = CompletableDeferred<Boolean>()
                pending = Pending(text, ask)
                return try { ask.await() } finally { pending = null }
            }
            override suspend fun awaitIfPaused() { pauseGate?.await() }
        }
        lastGoal = goal
        lastWho = who
        report = null
        agentRunning = true
        pauseGate = null
        hud = AgentHud(line = "शुरू कर रहा हूँ…", max = prefs.agentMaxSteps)
        changeMode(Mode.WORKING)
        var out: AgentOutcome? = null
        try {
            out = AgentRunner(applicationContext, client, ui, prefs.agentMaxSteps, prefs.blockedPkgs).run(goal, who, prefs.relayUrl, prefs.token)
        } finally {
            agentRunning = false
            pauseGate = null
            hideHighlight()
            changeMode(Mode.CHAT)
        }
        val o = out ?: return
        val structured = o.report.contains("स्थिति:")
        report = if (structured) o.report else null
        said = if (structured) "" else o.report
        results.add(
            when (o.status) {
                "ok" -> "✓ काम पूरा (${o.steps} क़दम)"
                "ask" -> "❓ आपकी मदद चाहिए"
                "stopped" -> "⏹ रोका गया"
                "limit" -> "⏱ सीमा पूरी हुई"
                else -> "✗ काम नहीं हो पाया"
            },
        )
        repo.addEpisode(who, "[स्क्रीन-काम] $goal", o.report, "screen_task")
    }

    private fun rerun() {
        if (agentRunning || lastGoal.isBlank()) return
        results.clear()
        report = null
        said = ""
        job = scope.launch {
            try {
                runAgent(lastGoal, lastWho)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                said = "कुछ गड़बड़ हुई।"
            }
            scheduleCollapse()
        }
    }

    private fun stopTask() {
        job?.cancel()
        pauseGate?.cancel()
        busy = false
        waking = false
        said = "रोक दिया।"
        scheduleCollapse()
    }

    private fun send(text: String) {
        val t = text.trim()
        if (busy || t.isEmpty()) return
        if (prefs.relayUrl.isBlank() || prefs.token.isBlank()) {
            said = "पहले ऐप खोलकर सेटिंग (⚙) में Relay URL और टोकन डालिए।"
            return
        }
        input = ""
        busy = true
        said = ""
        report = null
        results.clear()
        collapseJob?.cancel()
        val cid = character.id
        val h = hist.getOrPut(cid) { mutableListOf() }
        job = scope.launch {
            try {
                val mem = repo.relevant(t, cid)
                val r = client.chat(
                    prefs.relayUrl, prefs.token, cid, t, h.takeLast(8),
                    tools = Tools.NAMES, context = Tools.context(), memory = mem,
                ) { waking = it }
                said = r.speech.ifBlank { if (r.toolCalls.isEmpty()) "..." else "ठीक है।" }
                h.add("user" to t)
                h.add("assistant" to r.speech)
                busy = false
                waking = false
                repo.addEpisode(cid, t, r.speech, r.toolCalls.joinToString(",") { it.name })
                runTools(r.toolCalls, cid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RelayClient.RelayException) {
                said = e.message ?: "कुछ गड़बड़ हुई।"
            } catch (e: Exception) {
                said = "कुछ गड़बड़ हुई, थोड़ी देर बाद कोशिश कीजिए।"
            } finally {
                busy = false
                waking = false
                pending = null
            }
            scheduleCollapse()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        hideHighlight()
        view?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        view = null
        owner.destroy()
        running = false
        super.onDestroy()
    }
}
