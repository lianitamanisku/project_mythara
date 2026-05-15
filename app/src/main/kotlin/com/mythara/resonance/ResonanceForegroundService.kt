package com.mythara.resonance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mythara.MainActivity
import com.mythara.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that hosts the Resonance closed loop + audio
 * engine while a session is active. Exists for three reasons:
 *
 *  1. Lifecycle. The render coroutine + the 30s analyzer poll need to
 *     keep running with the screen off. A bound `@Singleton` would die
 *     when the OS reclaims the process; a foreground service won't.
 *  2. Hard-stop ownership. The headphone-removal broadcast
 *     ([HeadphoneNoisyReceiver]) can ONLY be received via a
 *     dynamically-registered receiver, so the FGS owns it. Audio focus
 *     loss (incoming call, system priority audio) also funnels through
 *     here.
 *  3. Audit trail. The persistent notification gives the user a clear
 *     "tones are playing" signal + a one-tap stop action.
 *
 * FGS type: `specialUse` only — Resonance never holds the mic itself
 * (the analyzer's brief acoustic sample goes through `MicBroker.acquire
 * (RESONANCE)` which is its own gate). Permission
 * `FOREGROUND_SERVICE_SPECIAL_USE` + the subType property in the
 * manifest cover Android 14+ FGS-type enforcement.
 *
 * Modelled on [com.mythara.secret.observe.ObserveForegroundService] —
 * neutral notification copy, START_NOT_STICKY (no auto-resume of audio
 * after a process kill).
 */
@AndroidEntryPoint
class ResonanceForegroundService : Service() {

    @Inject lateinit var loop: ResonanceLoop
    @Inject lateinit var audioEngine: ResonanceAudioEngine

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var phaseWatchJob: Job? = null
    private var noisyReceiver: HeadphoneNoisyReceiver? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss — incoming call, another priority
                // app took over. Hard stop the session.
                Log.d(TAG, "audio focus LOSS → resonance hard stop")
                loop.stop()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Brief interruption (e.g. a notification) — duck.
                audioEngine.setVolumeCap(DUCK_VOLUME)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                audioEngine.setVolumeCap(DUCK_VOLUME)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Restore the standard cap; the loop's de-escalation
                // (if it had stepped down) will resume from here.
                audioEngine.setVolumeCap(ResonanceAudioEngine.DEFAULT_VOLUME_CAP)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        // Headphone-removal hard stop — must be dynamic; manifest
        // declarations of this broadcast are silently dropped.
        noisyReceiver = HeadphoneNoisyReceiver(onBecomingNoisy = { loop.stop() }).also {
            registerReceiver(it, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        // Auto-stop the service when the loop reaches Idle — keeps the
        // FGS slot free + the persistent notification accurate.
        phaseWatchJob = scope.launch {
            var sawNonIdle = false
            loop.phase.collect { p ->
                if (p != ResonanceLoop.Phase.Idle) sawNonIdle = true
                if (p == ResonanceLoop.Phase.Idle && sawNonIdle) {
                    Log.d(TAG, "loop reached Idle — stopping FGS")
                    abandonFocus()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startInForeground()
                requestFocus()
                val protoName = intent.getStringExtra(EXTRA_PROTOCOL)
                val protocol = protoName?.let {
                    runCatching { ResonanceCommand.Protocol.valueOf(it) }.getOrNull()
                }
                Log.d(TAG, "ACTION_START protocol=${protocol?.name ?: "auto"}")
                loop.start(protocol)
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP")
                loop.stop()
            }
        }
        // NOT sticky: a background auto-restart would resume audio
        // unexpectedly after a kill, which is exactly what we don't want.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        runCatching { noisyReceiver?.let { unregisterReceiver(it) } }
        noisyReceiver = null
        abandonFocus()
        scope.cancel()
    }

    // ---------------------------------------------------------------- focus

    private fun requestFocus() {
        val am = audioManager ?: return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false) // we self-duck via setVolumeCap
                .build()
            focusRequest = req
            val r = am.requestAudioFocus(req)
            Log.d(TAG, "requestAudioFocus → $r")
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    // ---------------------------------------------------------------- notification

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires explicit FGS-type at startForeground.
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, ResonanceForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mythara")
            .setContentText("Resonance session running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .addAction(0, "stop", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "Mythara/ResonanceFGS"
        private const val CHANNEL_ID = "mythara_resonance"
        private const val NOTIF_ID = 0x7E50

        /** Volume cap while another stream wants to duck us. */
        private const val DUCK_VOLUME = 0.10f

        const val ACTION_START = "com.mythara.resonance.START"
        const val ACTION_STOP = "com.mythara.resonance.STOP"
        /** Optional protocol name (matches `ResonanceCommand.Protocol.name`). */
        const val EXTRA_PROTOCOL = "protocol"

        fun start(ctx: Context, protocol: ResonanceCommand.Protocol?) {
            val intent = Intent(ctx, ResonanceForegroundService::class.java)
                .setAction(ACTION_START)
                .apply { protocol?.let { putExtra(EXTRA_PROTOCOL, it.name) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            val intent = Intent(ctx, ResonanceForegroundService::class.java)
                .setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Resonance session",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
