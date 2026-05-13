package com.mythara

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
