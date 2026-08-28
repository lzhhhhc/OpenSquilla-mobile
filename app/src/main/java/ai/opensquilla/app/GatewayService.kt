package ai.opensquilla.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that keeps the local AI gateway alive while the app is
 * in the background. Without it, aggressive OEM killers (EMUI/MIUI/ColorOS)
 * freeze the process the moment the user switches away, and the WebView loses
 * its 127.0.0.1 backend.
 *
 * The ongoing notification doubles as the live task monitor: the service polls
 * the gateway's read-only ``/api/sessions`` summary and rewrites the
 * notification with the running/queued task counts and the active task title
 * (indeterminate progress bar while a task streams). It only re-notifies when
 * the summary actually changes, so a 4-second poll never churns the shade.
 *
 * Uses foregroundServiceType="specialUse" (no time limit, unlike dataSync on
 * Android 14+) because the gateway must run for as long as the user wants it.
 */
class GatewayService : Service() {

    private var poller: ScheduledExecutorService? = null
    private val pollInFlight = AtomicBoolean(false)
    private var lastSummaryKey: String? = null
    private var previousRunningByKey: Map<String, SessionRow>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        // Python is always started by MainActivity's background thread (never on
        // the UI thread). The service only makes sure the gateway is serving;
        // it must NOT call Python.start() itself — concurrent init would race.
        ensureGatewayServing()
        startTaskProgressPoller()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureGatewayServing()
        return START_STICKY
    }

    override fun onDestroy() {
        poller?.shutdownNow()
        poller = null
        super.onDestroy()
    }

    /**
     * Single-instance guard: MainActivity is the *only* caller of
     * opensquilla_android.serve() — the service must never start a second
     * gateway (a concurrent serve would hit the pid-lock and spam
     * "already_running"). The service only keeps the process foregrounded;
     * if the port is down we simply schedule a liveness probe.
     */
    private fun ensureGatewayServing() {
        if (!isPortOpen("127.0.0.1", GATEWAY_PORT)) {
            // MainActivity boots the gateway on its own thread. Nothing to do
            // here; just re-check later via onStartCommand/START_STICKY.
            return
        }
    }

    private fun isPortOpen(host: String, port: Int): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), 300) }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * The poller owns the notification's task-progress content. A fixed-delay
     * schedule on one daemon worker keeps the cost trivial (one localhost GET
     * per tick) and survives gateway restarts: a failed tick just skips.
     */
    private fun startTaskProgressPoller() {
        if (poller != null) return
        poller = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gateway-task-progress").apply { isDaemon = true }
        }
        // First tick is delayed: MainActivity boots Python on its own thread,
        // so the gateway is usually not accepting connections yet in onCreate.
        poller?.scheduleWithFixedDelay({
            if (!pollInFlight.compareAndSet(false, true)) return@scheduleWithFixedDelay
            try {
                applyTaskProgressToNotification()
            } catch (e: Exception) {
                Log.d(TAG, "task progress tick skipped: ${e.message}")
            } finally {
                pollInFlight.set(false)
            }
        }, FIRST_POLL_DELAY_SECONDS, POLL_PERIOD_SECONDS, TimeUnit.SECONDS)
    }

    private fun applyTaskProgressToNotification() {
        val result = fetchPollResult() ?: run {
            Log.d(TAG, "task progress poll: gateway not answering yet")
            return
        }
        detectFinishedTasks(result.rows)
        val summary = result.summary
        val key = summary.key()
        if (key == lastSummaryKey) return
        lastSummaryKey = key
        Log.d(TAG, "task progress: $key")
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(summary))
    }

    /**
     * A task "finishes" when it drops out of the running set between two polls.
     * Detection survives an OEM background freeze: while frozen no tick runs,
     * and the first tick after unfreezing replays the transition against the
     * pre-freeze snapshot, so the reminder is late but never silently lost.
     * A row that vanished entirely (session deleted) stays silent.
     */
    private fun detectFinishedTasks(rows: List<SessionRow>) {
        val runningNow = rows.filter { it.runStatus == "running" }
            .associateBy({ it.key }, { it })
        val previous = previousRunningByKey
        previousRunningByKey = runningNow
        if (previous == null) return
        for ((key, previousRow) in previous) {
            if (runningNow.containsKey(key)) continue
            val current = rows.firstOrNull { it.key == key } ?: continue
            val outcome = when (current.runStatus) {
                "idle" -> "已完成"
                "failed", "timeout" -> "已失败"
                "cancelled", "interrupted" -> "已停止"
                else -> null // went back to the queue: not a finish
            } ?: continue
            postTaskFinishedNotification(key, previousRow.title, outcome)
        }
    }

    /** Distinct dismissible alert so the completion ping never replaces the keeper bar. */
    private fun postTaskFinishedNotification(sessionKey: String, title: String, outcome: String) {
        ensureTaskAlertChannel()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, TASK_ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OpenSquilla 任务提醒")
            .setContentText("任务「${title.ifBlank { "未命名" }}」$outcome")
            .setContentIntent(makeContentIntent())
            .setAutoCancel(true)
        manager.notify(
            TASK_ALERT_NOTIFICATION_ID_BASE + (sessionKey.hashCode() and 0x7fffffff) % 100000,
            builder.build()
        )
        Log.d(TAG, "task finished alert: $sessionKey $outcome")
    }

    /** Read-only localhost GET; the endpoint carries no auth and returns a tiny list. */
    private fun fetchPollResult(): PollResult? {
        val connection = try {
            val url = URL("http://127.0.0.1:$GATEWAY_PORT/api/sessions?limit=20")
            (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 3000
                requestMethod = "GET"
            }
        } catch (e: Exception) {
            return null
        }
        try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val sessions = JSONObject(body).optJSONArray("sessions") ?: return null
            var running = 0
            var queued = 0
            var runningTitle = ""
            var queuedTitle = ""
            val rows = ArrayList<SessionRow>(sessions.length())
            for (i in 0 until sessions.length()) {
                val row = sessions.optJSONObject(i) ?: continue
                val rowStatus = row.optString("runStatus")
                val rowTitle = row.optString("title")
                val rowKey = row.optString("key")
                rows.add(SessionRow(rowKey, rowTitle, rowStatus))
                when (rowStatus) {
                    "running" -> {
                        running += 1
                        if (runningTitle.isEmpty()) runningTitle = rowTitle
                    }
                    "queued" -> {
                        queued += 1
                        if (queuedTitle.isEmpty()) queuedTitle = rowTitle
                    }
                }
            }
            return PollResult(TaskSummary(running, queued, runningTitle, queuedTitle), rows)
        } catch (e: Exception) {
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun buildNotification(summary: TaskSummary? = null): Notification {
        ensureNotificationChannel()
        val title = "OpenSquilla 运行中"
        val text = when {
            summary == null ->
                // Boot window: the gateway is not accepting yet.
                "本地 AI 网关正在 127.0.0.1:$GATEWAY_PORT 提供服务"
            summary.running > 0 ->
                if (summary.running > 1) {
                    "任务：${summary.runningTitle.ifBlank { "未命名" }} 等 ${summary.running} 个进行中"
                } else {
                    "任务：${summary.runningTitle.ifBlank { "未命名" }}进行中"
                }
            summary.queued > 0 ->
                "任务：${summary.queuedTitle.ifBlank { "未命名" }}排队中"
            else ->
                "当前无任务"
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(makeContentIntent())
            .setOngoing(true)
            .setShowWhen(false)
        // The gateway does not expose a numeric completion ratio, so an active
        // task renders as an indeterminate activity bar; it clears on idle.
        builder.setProgress(0, 0, summary != null && summary.running > 0)
        return builder.build()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "本地 AI 网关",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持本地 AI 网关在后台持续运行，并显示实时任务进度"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    /** Completion pings are user-facing news: default importance = sound, no heads-up. */
    private fun ensureTaskAlertChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                TASK_ALERT_CHANNEL_ID,
                "任务提醒",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "单个任务结束（完成/失败/停止）时提醒"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun makeContentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private data class SessionRow(
        val key: String,
        val title: String,
        val runStatus: String,
    )

    private data class PollResult(
        val summary: TaskSummary,
        val rows: List<SessionRow>,
    )

    private data class TaskSummary(
        val running: Int,
        val queued: Int,
        val runningTitle: String,
        val queuedTitle: String,
    ) {
        fun key(): String = "$running|$queued|$runningTitle|$queuedTitle"
    }

    companion object {
        private const val TAG = "SQGateway"
        private const val CHANNEL_ID = "gateway"
        private const val NOTIFICATION_ID = 1001
        private const val TASK_ALERT_CHANNEL_ID = "task-alert"
        private const val TASK_ALERT_NOTIFICATION_ID_BASE = 5000
        private const val GATEWAY_PORT = 18790
        private const val POLL_PERIOD_SECONDS = 4L
        private const val FIRST_POLL_DELAY_SECONDS = 6L

        /** Start (or restart) the keeper service. Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            context.startForegroundService(intent)
        }
    }
}