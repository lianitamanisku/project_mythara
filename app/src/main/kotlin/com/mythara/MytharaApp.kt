package com.mythara

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.mythara.agent.AutoReplyDispatcher
import com.mythara.agent.NotificationImageIngestor
import com.mythara.agent.queue.PendingReplyKickScheduler
import com.mythara.agent.queue.PendingReplyQueue
import com.mythara.lifeline.LifelineScheduler
import com.mythara.lifeline.MediaStoreObserver
import com.mythara.health.HealthLearningScheduler
import com.mythara.health.HrCorrelationScheduler
import com.mythara.mcp.McpRegistry
import com.mythara.memory.HeartbeatSyncer
import com.mythara.reminders.ReminderAlarmScheduler
import com.mythara.sensors.SensorLearningScheduler
import com.mythara.analytics.ContactAnalyticsScheduler
import com.mythara.agent.SelfOrganizerScheduler
import com.mythara.growth.GrowthScheduler
import com.mythara.memory.MemorySyncScheduler
import com.mythara.persona.PersonaScheduler
import com.mythara.persona.PersonaSettings
import com.mythara.voice.QuickTalkNotification
import com.mythara.voice.QuickTalkSettings
import com.mythara.wear.WatchClusterDataPusher
import com.mythara.wear.WatchPhoneStatusRelay
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
    @Inject lateinit var settingsStore: com.mythara.data.SettingsStore
    @Inject lateinit var growthScheduler: GrowthScheduler
    @Inject lateinit var memorySyncScheduler: MemorySyncScheduler
    @Inject lateinit var selfOrganizerScheduler: SelfOrganizerScheduler
    @Inject lateinit var quickTalkNotification: QuickTalkNotification
    @Inject lateinit var quickTalkSettings: QuickTalkSettings
    @Inject lateinit var personaScheduler: PersonaScheduler
    @Inject lateinit var personaSettings: PersonaSettings
    @Inject lateinit var autoReplyDispatcher: AutoReplyDispatcher
    @Inject lateinit var notificationImageIngestor: NotificationImageIngestor
    @Inject lateinit var crossAppPersonObserver: com.mythara.people.CrossAppPersonObserver
    @Inject lateinit var contactAnalyticsScheduler: ContactAnalyticsScheduler
    @Inject lateinit var pendingReplyQueue: PendingReplyQueue
    @Inject lateinit var pendingReplyKickScheduler: PendingReplyKickScheduler
    @Inject lateinit var lifelineScheduler: LifelineScheduler
    @Inject lateinit var mediaStoreObserver: MediaStoreObserver
    @Inject lateinit var heartbeatSyncer: HeartbeatSyncer
    @Inject lateinit var mcpRegistry: McpRegistry
    @Inject lateinit var sensorLearningScheduler: SensorLearningScheduler
    @Inject lateinit var reminderAlarmScheduler: ReminderAlarmScheduler
    @Inject lateinit var healthLearningScheduler: HealthLearningScheduler
    @Inject lateinit var hrCorrelationScheduler: HrCorrelationScheduler
    @Inject lateinit var watchNextTaskRelay: com.mythara.wear.WatchNextTaskRelay
    @Inject lateinit var watchPhoneStatusRelay: WatchPhoneStatusRelay
    @Inject lateinit var watchClusterDataPusher: WatchClusterDataPusher
    @Inject lateinit var healthConnectHrPoller: com.mythara.health.HealthConnectHrPoller

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

        // Capability Expansion v3 — Meta DAT SDK initialization runs
        // through the GlassesDatFacade so a missing SDK dep doesn't
        // crash app startup. The facade no-ops + logs when DAT isn't
        // wired (default for the initial commit while GITHUB_TOKEN is
        // being configured); once the user uncomments the mwdat deps
        // in app/build.gradle.kts and fills in the facade bodies,
        // initialize() actually pairs with Meta AI.
        com.mythara.glasses.GlassesDatFacade.initializeIfAvailable(this)

        // Seed the API status dots in MytharaStatusBar based on
        // configured credentials so the user sees blue/yellow on
        // first launch (instead of grey-until-first-call). Decay
        // logic in ApiStatusStore re-flips to red on a real
        // failure or grey on connectivity loss.
        kotlinx.coroutines.GlobalScope.launch {
            runCatching {
                val snap = settingsStore.snapshot()
                if (!snap.apiKey.isNullOrBlank()) {
                    com.mythara.ui.system.ApiStatusStore.markMinimaxOnline()
                }
                if (!snap.geminiKey.isNullOrBlank()) {
                    com.mythara.ui.system.ApiStatusStore.markImageOnline()
                }
            }
        }

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
        // Always-on cross-app person observer — every messaging
        // notification (Teams, WhatsApp, SMS, Slack, …) auto-adds
        // the sender to the People list (below favourites) and
        // merges aliases of the same human across apps. The
        // observer skips self (matching MeProfileStore aliases)
        // and brand/system senders. Behaviour-event side-channel
        // feeds the daily summariser for pattern derivation.
        crossAppPersonObserver.start()
        // NOTE: LockscreenIslandService.start() lives in
        // MainActivity.onCreate — Application.onCreate runs in
        // BACKGROUND context per Android 12+'s FGS lifecycle
        // rules, and startForegroundService from there throws
        // ForegroundServiceStartNotAllowedException → app crash
        // on every cold launch. Activity.onCreate IS a foreground
        // entry, so starting the service there is the safe path.
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
        // Reminder alarms — observes TaskDb and registers
        // AlarmManager exact-wake intents for every scheduled task.
        // BootReceiver also calls rescheduleAll() on device boot
        // because AlarmManager doesn't survive reboots.
        reminderAlarmScheduler.start()
        // Calendar pre-announcer — schedules AlarmManager exact alarms
        // 3 minutes before every upcoming calendar event. The periodic
        // worker re-scans every 15 min to catch newly-added events;
        // alarms fire independently so survive process kill / reboot.
        // Whole feature is a no-op until the user flips
        // CalendarPreAnnounceStore.enabled.
        com.mythara.calendar.CalendarPreAnnounceWorker.ensureScheduled(this)
        // Health Connect snapshot worker — pulls last-24h aggregates
        // every 6h on charging, lands semantic vault rows tagged
        // topic:health. Silently no-ops when no permissions granted
        // or Health Connect SDK unavailable. The vault's existing
        // semantic sync ships these to peers via the memory repo.
        healthLearningScheduler.start()
        // HR correlation worker — every hour, looks for heart-rate
        // spikes (typically from a paired Pixel Watch) and attributes
        // each to whichever contact pinged the user in the preceding
        // 5-min window. Per-contact correlation rows feed the
        // contact-analytics builder so "Boss pings consistently
        // correlate with HR spikes" becomes a learned relationship
        // signal that flavors auto-replies + persona insights.
        hrCorrelationScheduler.start()
        // Watch-face NEXT-TASK relay — replaces the old "last agent
        // message" mirror with a forward-looking card. The wrist now
        // always shows whichever scheduled task is coming up next,
        // re-evaluated every minute as time moves forward AND on
        // every task DB change (new schedule, cancel, fire).
        watchNextTaskRelay.start()
        // Periodic 15-min full watch resync (insight line, cluster
        // data, phone status). Backstops the in-process relays so
        // the wrist stays fresh even when the app's been sleeping.
        com.mythara.wear.WatchSyncWorker.ensureScheduled(this)
        // Watch-face phone-status relay — publishes the phone's battery
        // level to the watch (WFF can't read the peer device's battery).
        watchPhoneStatusRelay.start()
        // Watch companion data — pushes a snapshot of recent tasks +
        // favorite people/insights to the watch app's Tasks/People lists.
        watchClusterDataPusher.start()
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
        // v6 — Google Health (Health Connect) as the PRIMARY in-app HR
        // source. Foreground-gated: poll only while the app is visible
        // (the Living-Rose backdrop + bottom rose amulet breathe with
        // HR; in the background the wallpaper service has its own HR
        // path). Both start()/stop() are idempotent.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_START -> healthConnectHrPoller.start()
                    androidx.lifecycle.Lifecycle.Event.ON_STOP -> healthConnectHrPoller.stop()
                    else -> Unit
                }
            },
        )
    }
}
