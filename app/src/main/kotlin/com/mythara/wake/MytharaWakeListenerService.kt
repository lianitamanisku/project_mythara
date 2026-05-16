package com.mythara.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mythara.MainActivity
import com.mythara.mic.MicBroker
import com.mythara.secret.observe.AudioRecorder
import com.mythara.secret.observe.extract.QuickNoteDetector
import com.mythara.secret.observe.vosk.VoskAsr
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Always-on Vosk listener that watches for "Hey Mythara <query>" utterances
 * and pushes the query directly into the chat agent. Replaces the
 * earlier openWakeWord + SpeechRecognizer two-stage design — the
 * hardware layer can't multiplex AudioRecord clients, so we collapse
 * the two stages into one Vosk pipeline: continuous transcription on
 * a single mic acquisition, regex match for the trigger, submit the
 * tail of the utterance.
 *
 * Privacy:
 *  - No transcript is written to disk by this service. Non-matching
 *    utterances are dropped on the floor; matched queries leave the
 *    device only via the normal MiniMax round-trip in ChatViewModel,
 *    same as a typed message.
 *  - The persistent foreground notification ("Mythara · Listening for
 *    'Hey Mythara'") is mandatory under Android's FGS rules; we keep it
 *    minimal.
 *
 * Mutually exclusive with ObserveForegroundService — Android's
 * AudioRecord is single-client. The UI should refuse to enable both
 * simultaneously; if they race anyway, the second one to call
 * `AudioRecord.startRecording()` fails and lands in State.Error.
 */
@AndroidEntryPoint
class MytharaWakeListenerService : Service() {

    @Inject lateinit var asr: VoskAsr
    @Inject lateinit var store: WakeListenerStore
    @Inject lateinit var micBroker: MicBroker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(), serviceTypeForFgs())
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        store.setState(WakeListenerStore.State.Stopping)
        runCatching { loopJob?.cancel() }
        scope.cancel()
        store.setState(WakeListenerStore.State.Idle)
    }

    private fun startListening() {
        if (loopJob?.isActive == true) return
        if (!asr.isReady()) {
            Log.w(TAG, "Vosk model not ready — open Secret Settings → speech model to download")
            store.setState(WakeListenerStore.State.Error("Vosk model not downloaded"))
            stopSelf()
            return
        }
        store.setState(WakeListenerStore.State.Starting)
        if (!micBroker.acquire(MicBroker.Client.LUMI_LISTEN)) {
            val owner = micBroker.owner.value?.let { micBroker.describe(it) } ?: "another mode"
            store.setState(WakeListenerStore.State.Error("Microphone busy — $owner is using it"))
            stopSelf()
            return
        }
        val recorder = AudioRecorder()
        if (!recorder.start()) {
            micBroker.release(MicBroker.Client.LUMI_LISTEN)
            store.setState(
                WakeListenerStore.State.Error("AudioRecord init failed"),
            )
            recorder.release()
            stopSelf()
            return
        }
        val recognizer = runCatching { asr.newRecognizer() }.getOrElse {
            recorder.release()
            store.setState(WakeListenerStore.State.Error("Vosk recognizer init: ${it.message}"))
            stopSelf()
            return
        }

        val buf = ShortArray(recorder.readFrameSamples)
        loopJob = scope.launch {
            store.setState(WakeListenerStore.State.Listening)
            Log.d(TAG, "Mythara always-listen up — Vosk loop running")
            try {
                while (isActive) {
                    val n = recorder.read(buf)
                    if (n <= 0) continue
                    val isFinal = recognizer.acceptWaveForm(buf, n)
                    if (isFinal) {
                        val text = asr.parseText(recognizer.result)
                        if (text.isNotBlank()) handleTranscript(text)
                    }
                }
                // Drain on graceful stop so a query the user just
                // finished saying isn't lost.
                val tail = asr.parseText(recognizer.finalResult)
                if (tail.isNotBlank()) handleTranscript(tail)
            } catch (t: Throwable) {
                Log.e(TAG, "listener loop crashed: ${t.message}", t)
                store.setState(
                    WakeListenerStore.State.Error(t.message ?: t.javaClass.simpleName),
                )
            } finally {
                runCatching { recognizer.close() }
                recorder.stop()
                recorder.release()
                micBroker.release(MicBroker.Client.LUMI_LISTEN)
                Log.d(TAG, "Mythara always-listen stopped")
            }
        }
    }

    private fun handleTranscript(text: String) {
        // Privacy: non-matching transcripts must never escape volatile
        // memory — the panel's contract is "Vosk runs locally, nothing
        // is persisted unless it's a 'Hey Mythara <query>' that the user
        // explicitly addressed to the assistant". logcat is a circular
        // buffer that *does* persist long enough for screen-recording
        // / bug-report grabs to capture it, so don't log raw text.
        val query = QuickNoteDetector.detect(text) ?: return
        if (query.isBlank()) return
        // The match itself + the query text are loggable — the user
        // just chose to send these words to MiniMax, so by the time
        // this Log.d runs the data is about to leave the device anyway.
        Log.d(TAG, "wake → query: ${query.take(80)}")
        store.emitWake(query)
    }

    private fun serviceTypeForFgs(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Mythara always-listen",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mythara is listening for 'Hey Mythara'."
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, MytharaWakeListenerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Mythara")
            .setContentText("Listening for 'Hey Mythara …'")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(tap)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "Mythara/Wake"
        private const val CHANNEL_ID = "mythara.wake.lumi.listen"
        private const val NOTIF_ID = 0x77ABBB
        const val ACTION_STOP = "com.mythara.wake.STOP_LISTEN"
    }
}
