package com.mythara.persona

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Daily worker that pulls 24h of usage stats and asks
 * [PersonaBuilder] to write persona-trait records into the
 * vault. Gated by both the user's persona-toggle preference
 * AND the Usage-Access special permission — fails fast if
 * either is missing.
 *
 * Schedule:
 *  - 24h cadence
 *  - RequiresBatteryNotLow (we're not in a hurry)
 *  - 1h initial delay so we don't fire instantly on first
 *    app launch before the user has any meaningful data
 */
@HiltWorker
class PersonaWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val builder: PersonaBuilder,
    private val settings: PersonaSettings,
    private val accessHelper: UsageAccessHelper,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        if (!settings.enabledFlow().first()) {
            Log.d(TAG, "persona disabled — skipping daily pull")
            return Result.success()
        }
        if (!accessHelper.isGranted()) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted; skipping")
            return Result.success() // not a failure — user just hasn't granted yet
        }
        val report = runCatching { builder.buildDaily() }.getOrElse { e ->
            Log.w(TAG, "buildDaily threw", e)
            return Result.retry()
        }
        Log.d(TAG, "persona daily: ok=${report.ok} wrote=${report.recordsWritten} msg=${report.message ?: "-"}")
        return Result.success()
    }

    companion object {
        private const val TAG = "Mythara/Persona"
        const val UNIQUE_PERIODIC = "mythara_persona_daily"
    }
}

@Singleton
class PersonaScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<PersonaWorker>(Duration.ofHours(24))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(Duration.ofHours(1))
            .build()
        wm.enqueueUniquePeriodicWork(
            PersonaWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    fun stop() {
        wm.cancelUniqueWork(PersonaWorker.UNIQUE_PERIODIC)
    }
}
