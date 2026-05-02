package com.letta.mobile.channel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.letta.mobile.MainActivity
import com.letta.mobile.R
import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.repository.AgentRepository
import com.letta.mobile.data.timeline.IngestedMessageListener
import com.letta.mobile.data.timeline.TimelineRepository
import com.letta.mobile.util.Telemetry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the app process alive and the resume-stream subscribers running even
 * when no Activity is foregrounded. Without this, Android would stop our
 * activities when the screen turns off, cancelling UI coroutines and making
 * the [TimelineRepository]'s loops dormant.
 *
 * Design:
 *   1. On start, iterate every conversation the current agent has and call
 *      [TimelineRepository.getOrCreate] — this spawns a subscriber coroutine
 *      for each that lives as long as the repository (a @Singleton).
 *   2. Install an [IngestedMessageListener] that posts a system notification
 *      via [ChannelNotificationPublisher] whenever a new assistant/tool
 *      message arrives and we're not currently showing that conversation.
 *   3. Hold a persistent low-priority foreground notification so Android
 *      won't kill the process.
 *
 * See letta-mobile-mge5 for the architectural epic.
 */
@AndroidEntryPoint
class ChatPushService : Service() {

    @Inject lateinit var timelineRepository: TimelineRepository
    @Inject lateinit var conversationApi: ConversationApi
    @Inject lateinit var agentRepository: AgentRepository
    @Inject lateinit var notificationPublisher: ChannelNotificationPublisher
    @Inject lateinit var currentConversationTracker: CurrentConversationTracker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmupJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // letta-mobile-50p2: foreground promotion can fail (e.g. FGS quota
        // exhausted on Android 14+ for older service types, or transient
        // BG-restriction states). Swallow the failure — the service still
        // runs in the background until Android kills it, which is far
        // better than crashing the entire app process at launch.
        val foregrounded = ensureForegroundNotification()
        if (!foregrounded) {
            Log.w(TAG, "Foreground promotion failed; running as background service")
        }
        installListener()
        warmupSubscribers()
        Telemetry.event(
            "ChatPushService", "created",
            "foregrounded" to foregrounded.toString(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        Telemetry.event("ChatPushService", "destroyed")
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Promote the service to the foreground.
     *
     * Returns `true` on success, `false` if Android refused (e.g.
     * ForegroundServiceStartNotAllowedException — quota exhausted, BG
     * restricted, missing permission). Caller decides how to degrade.
     *
     * letta-mobile-50p2: previously used FOREGROUND_SERVICE_TYPE_DATA_SYNC,
     * which has a 6-hour rolling daily quota on Android 14+. Once the
     * quota was hit, every subsequent startForeground() threw and crashed
     * the process at launch in an unrecoverable loop. Switched to
     * REMOTE_MESSAGING (the correct semantic for a chat push channel)
     * which is quota-exempt, and wrapped the call in try/catch as a
     * defense-in-depth so future quota / restriction surprises degrade
     * gracefully instead of taking the app down.
     */
    private fun ensureForegroundNotification(): Boolean {
        createServiceChannelIfNeeded()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.push_service_notification_title))
            .setContentText(getString(R.string.push_service_notification_text))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
                )
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is the
            // common case, but we catch broadly because IllegalStateException
            // and SecurityException can also surface here on niche OEM ROMs.
            Log.e(TAG, "startForeground failed", t)
            Telemetry.error("ChatPushService", "startForeground.failed", t)
            false
        }
    }

    private fun createServiceChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(SERVICE_CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            SERVICE_CHANNEL_ID,
            getString(R.string.push_service_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.push_service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun installListener() {
        timelineRepository.ingestedListener = object : IngestedMessageListener {
            override suspend fun onMessageIngested(
                conversationId: String,
                serverId: String,
                messageType: String?,
                contentPreview: String?,
            ) {
                // Suppress notification if the user is currently looking at
                // this conversation.
                if (currentConversationTracker.current == conversationId) {
                    Telemetry.event(
                        "ChatPushService", "suppressedForegroundConv",
                        "conversationId" to conversationId,
                    )
                    return
                }

                val agentName = try {
                    // Best-effort: look up the conversation's agent for a nice title.
                    val conv = conversationApi.getConversation(conversationId)
                    agentRepository.agents.value.firstOrNull { it.id == conv.agentId }?.name.orEmpty()
                } catch (_: Exception) {
                    ""
                }

                notificationPublisher.publish(
                    ChannelNotification(
                        agentId = "",
                        agentName = agentName,
                        conversationId = conversationId,
                        conversationSummary = null,
                        messageId = serverId,
                        messagePreview = contentPreview.orEmpty(),
                    ),
                )
            }
        }
    }

    private fun warmupSubscribers() {
        // Pre-create subscribers for the agent's top conversations so streaming
        // kicks in before the user opens any chat screen.
        //
        // letta-mobile-qv6d: limit reduced from 20 → WARMUP_CONVERSATION_COUNT
        // (5). Each warmed conversation runs a permanent runStreamSubscriber
        // coroutine that polls /v1/conversations/{id}/stream on the idle
        // backoff ladder forever. At 20 convs and the prior 5s cap that
        // produced ~4 RPS of background 400 traffic against
        // letta.oculair.ca per device — saturating the OkHttp dispatcher
        // (default maxRequestsPerHost = 5) and starving foreground SSE
        // sends (Emmanuel's letta-mobile-kxsv hang).
        //
        // 5 conversations covers the realistic "I might tap any of these
        // recents next" window. Anything older incurs a one-shot getOrCreate
        // hydrate on first open (~500ms — imperceptible if the user just
        // tapped) and starts its own subscriber from there.
        warmupJob?.cancel()
        warmupJob = scope.launch {
            try {
                val conversations = conversationApi.listConversations(
                    limit = WARMUP_CONVERSATION_COUNT,
                    order = "desc",
                    orderBy = "last_message_at",
                )
                // Parallelize getOrCreate — each call blocks on an HTTP
                // hydrate (~500ms). Sequential iteration was taking 10+ seconds
                // to reach the most-recently-active conversation on the server
                // (we observed conv-1598043a-* not hydrated even ~15s into
                // warmup despite being first in the list). Parallel async
                // starts every hydrate simultaneously. loopsMutex inside
                // TimelineRepository still serializes the critical sections.
                // letta-mobile-mge5.
                coroutineScope {
                    conversations.map { c ->
                        async {
                            try {
                                timelineRepository.getOrCreate(c.id)
                            } catch (t: Throwable) {
                                Log.w(TAG, "warmup getOrCreate failed for ${c.id}", t)
                            }
                        }
                    }.awaitAll()
                }
                Telemetry.event(
                    "ChatPushService", "warmup.complete",
                    "conversationCount" to conversations.size,
                )
            } catch (t: Throwable) {
                Telemetry.error("ChatPushService", "warmup.failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "ChatPushService"
        private const val SERVICE_CHANNEL_ID = "letta-chat-push-service"
        private const val FOREGROUND_NOTIFICATION_ID = 7531

        // letta-mobile-qv6d: see warmupSubscribers() for rationale.
        private const val WARMUP_CONVERSATION_COUNT = 5

        fun start(context: Context) {
            // On Android 13+, POST_NOTIFICATIONS is runtime-granted. We still
            // start the service — the foreground service requires a
            // notification, but user can dismiss/disable the channel.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                // Defer — we'll be restarted after the user grants permission
                // (see MainActivity). Without POST_NOTIFICATIONS the service
                // can still run but the notification is suppressed, which
                // breaks the foreground contract on 13+.
                Log.w(TAG, "POST_NOTIFICATIONS not granted; deferring ChatPushService start")
                return
            }
            val intent = Intent(context, ChatPushService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
