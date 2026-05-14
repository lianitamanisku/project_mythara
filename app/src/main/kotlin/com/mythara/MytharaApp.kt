package com.mythara

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mythara.agent.AutoReplyDispatcher
import com.mythara.agent.NotificationImageIngestor
import com.mythara.agent.queue.PendingReplyKickScheduler
import com.mythara.agent.queue.PendingReplyQueue
import com.mythara.lifeline.LifelineScheduler
import com.mythara.lifeline.MediaStoreObserver
import com.mythara.mcp.McpRegistry
import com.mythara.memory.HeartbeatSyncer
import com.mythara.sensors.SensorLearningScheduler
import com.mythara.analytics.ContactAnalyticsScheduler
import com.mythara.agent.SelfOrganizerScheduler
import com.mythara.growth.GrowthScheduler
import com.mythara.memory.MemorySyncScheduler
import com.mythara.persona.PersonaScheduler
import com.mythara.persona.PersonaSettings
import com.mythara.voice.QuickTalkNotification
import com.mythara.voice.QuickTalkSettings
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry. Hosts the Hilt SingletonComponent, the WorkManager
 * configuration (so HiltWorker-annotated workers get their dependencies),
 * and the GrowthScheduler bootstrap that registers the nightly + weekly
 * self-learning cadences on first launch.
 *
 * The scheduler is idempotent — calling start() on every boot is safe
 * (UPDATE policy on unique periodic work). No need for a separate
 * BootReceiver: WorkManager survives reboots on its own.
 */
@HiltAndroidApp
class MytharaApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var growthScheduler: GrowthScheduler
    @Inject lateinit var memorySyncScheduler: MemorySyncScheduler
    @Inject lateinit var selfOrganizerScheduler: SelfOrganizerScheduler
    @Inject lateinit var quickTalkNotification: QuickTalkNotification
    @Inject lateinit var quickTalkSettings: QuickTalkSettings
    @Inject lateinit var personaScheduler: PersonaScheduler
    @Inject lateinit var personaSettings: PersonaSettings
    @Inject lateinit var autoReplyDispatcher: AutoReplyDispatcher
    @Inject lateinit var notificationImageIngestor: NotificationImageIngestor
    @Inject lateinit var contactAnalyticsScheduler: ContactAnalyticsScheduler
    @Inject lateinit var pendingReplyQueue: PendingReplyQueue
    @Inject lateinit var pendingReplyKickScheduler: PendingReplyKickScheduler
    @Inject lateinit var lifelineScheduler: LifelineScheduler
    @Inject lateinit var mediaStoreObserver: MediaStoreObserver
    @Inject lateinit var heartbeatSyncer: HeartbeatSyncer
    @Inject lateinit var mcpRegistry: McpRegistry
    @Inject lateinit var sensorLearningScheduler: SensorLearningScheduler

    // App-scoped supervisor for fire-and-forget process-level
    // coroutines (settings-flow observers etc.). Cancelled implicitly
    // when the process dies; we don't need explicit teardown for an
    // application-scoped object.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        growthScheduler.start()
        memorySyncScheduler.start()
        selfOrganizerScheduler.start()
        // Persistent reply queue — has to start BEFORE AutoReplyDispatcher
        // because the dispatcher's enqueue() calls land in this queue.
        // start() also runs the cold-boot recovery sweep that resets any
        // IN_FLIGHT rows orphaned by the previous process dying mid-turn.
        pendingReplyQueue.start()
        // Periodic safety-net worker — wakes the process every ~30 min
        // and asks the queue to drain anything that's been sitting in
        // PENDING because the process was killed since the row was
        // enqueued. Covers the gap where MytharaApp.onCreate isn't
        // triggered for a while (no notifications, app not opened).
        pendingReplyKickScheduler.start()
        // Auto-reply dispatcher subscribes to the global notification
        // stream at boot. It self-gates on AutopilotStore +
        // EnterpriseAutopilotStore + FavoritesStore — flipping any of
        // those off pauses auto-replies without stopping the listener.
        // Routes every "should auto-reply" decision into the
        // pendingReplyQueue above rather than firing the agent directly.
        autoReplyDispatcher.start()
        // Notification-image ingestor — the dispatcher enqueues
        // attached images here; the ingestor processes them one at
        // a time with a 30s gap, drops forwards/memes/ads, persists
        // genuine personal-moment learnings into the vault.
        notificationImageIngestor.start()
        // Daily Gemma rebuild of every per-contact profile so the
        // People screen stays current as conversations + imports
        // accumulate. The builder itself self-gates so the actual
        // LLM cost is only paid when a contact's sample grew or 24h
        // has passed since their last inference.
        contactAnalyticsScheduler.start()
        // Life timeline — scan MediaStore for new camera photos and
        // caption them via Gemini. The observer fires live on every
        // new photo; the periodic worker is the nightly catch-up so
        // photos taken while the process was dead get captioned the
        // next time we're charging on Wi-Fi.
        lifelineScheduler.start()
        mediaStoreObserver.start()
        // 5-minute heartbeat — fires memory sync + cross-device task
        // pickup on a coroutine timer. Self-gates when sync is off.
        heartbeatSyncer.start()
        // MCP registry — observes config DataStore + maintains a live
        // snapshot of every tool the configured MCP servers expose.
        // ToolRegistry merges these into its tool list on demand.
        mcpRegistry.start()
        // Periodic sensor snapshot → LearningVault. Cluster-wide
        // sensor pattern learning ("ambient light around 14:00 is
        // usually 800 lux"), shipped via the existing semantic
        // sync to peer devices.
        sensorLearningScheduler.start()
        // Reflect the user's persistent-talk-notification preference
        // on every cold start (and follow live toggles while the
        // process is alive). Observing the Flow rather than reading
        // once means flipping the toggle in Settings posts/cancels
        // the notification immediately without a process restart.
        appScope.launch {
            quickTalkSettings.enabledFlow().collect { enabled ->
                if (enabled) quickTalkNotification.show()
                else quickTalkNotification.cancel()
            }
        }
        // Mirror the persona-collection toggle. start() is idempotent
        // (UPDATE policy on the unique periodic work); stop() cancels
        // any pending run.
        appScope.launch {
            personaSettings.enabledFlow().collect { enabled ->
                if (enabled) personaScheduler.start()
                else personaScheduler.stop()
            }
        }
    }
}
