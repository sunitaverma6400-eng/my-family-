package com.stark.family

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

enum class Mode { BUBBLE, PICKER, CHAT }

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
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Stark Family बबल चालू है")
            .setContentText("परिवार किसी भी ऐप के ऊपर बुलाया जा सकता है")
            .setContentIntent(open)
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
        v.setViewTreeLifecycleOwner(owner)
        v.setViewTreeSavedStateRegistryOwner(owner)
        v.setViewTreeViewModelStoreOwner(owner)
        v.setContent {
            OverlayUi(
                mode = mode, ch = character, said = said, busy = busy, waking = waking, input = input,
                results = results, pendingText = pending?.text,
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
        val focusable = m == Mode.CHAT
        lp.flags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (m == Mode.BUBBLE) {
            lp.x = savedX
            lp.y = savedY
        } else {
            lp.x = ((resources.displayMetrics.widthPixels - dp(300)) / 2).coerceAtLeast(0)
            lp.y = dp(80)
        }
        update()
        if (m == Mode.BUBBLE) collapseJob?.cancel() else scheduleCollapse()
    }

    private fun summon(c: Character) {
        character = c
        said = ""
        input = ""
        results.clear()
        changeMode(Mode.CHAT)
    }

    private fun scheduleCollapse() {
        collapseJob?.cancel()
        collapseJob = scope.launch {
            delay(25_000)
            if (!busy && pending == null && input.isBlank()) changeMode(Mode.BUBBLE)
        }
    }

    /** Low-risk tools run at once; anything risky waits for an explicit "हाँ" on screen. Results are honest. */
    private suspend fun runTools(calls: List<ToolCall>, who: String) {
        for (c in calls.take(3)) {
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

    private fun stopTask() {
        job?.cancel()
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
        view?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        view = null
        owner.destroy()
        running = false
        super.onDestroy()
    }
}
