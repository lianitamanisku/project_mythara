package com.mythara.mic

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide coordinator for the single Android `AudioRecord`
 * resource. The app has multiple mic clients (Observe, Lumi-listen
 * wake-word, continuous voice chat) and Android only allows one to
 * hold the mic at a time. Before this broker existed, conflicts
 * surfaced as cryptic init failures after the user toggled — the
 * broker catches them upstream so the UI can disable conflicting
 * affordances and explain *which* mode is currently using the mic.
 *
 * V1 is cooperative — no preemption. The currently-active client
 * keeps the mic until it releases voluntarily; later acquires fail
 * fast. A future v2 could add priority preemption (e.g.
 * push-to-talk briefly bumps Observe) if real usage demands it.
 *
 * Push-to-talk in [com.mythara.ui.chat.MicButton] is intentionally NOT
 * brokered, but it DOES honour the [muted] kill-switch below.
 *
 * Mute: [muted] is the global speech-to-text kill-switch, backed by
 * [SpeechMuteStore]. While muted, [acquire] always fails and any
 * held mic is force-released, so every brokered client stops; the
 * PTT button reads [muted] directly to disable itself.
 */
@Singleton
class MicBroker @Inject constructor(
    private val muteStore: SpeechMuteStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Identifies the kind of long-lived mic owner. */
    enum class Client {
        /** ObserveForegroundService — passive learning loop. */
        OBSERVE,
        /** LumiListenerService — always-on "Hey Lumi" wake-word. */
        LUMI_LISTEN,
        /** ChatScreen continuous voice mode (Pixel Soda). */
        CONTINUOUS_CHAT,
    }

    private val _owner = MutableStateFlow<Client?>(null)
    /** Currently-acquired client, or null if the mic is free. */
    val owner: StateFlow<Client?> = _owner.asStateFlow()

    private val _muted = MutableStateFlow(false)
    /** Global speech-to-text mute. When true, every STT path is off. */
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    init {
        // Hydrate from disk + follow live edits. Muting force-releases
        // whatever currently holds the mic.
        scope.launch {
            muteStore.mutedFlow().collect { persisted ->
                _muted.value = persisted
                if (persisted) forceRelease()
            }
        }
    }

    /**
     * Flip the global speech-to-text mute. Persisted via
     * [SpeechMuteStore]; the change propagates back through the
     * observer above, so [muted] and the UI stay consistent.
     */
    fun setMuted(value: Boolean) {
        scope.launch { muteStore.setMuted(value) }
    }

    /**
     * Try to acquire the mic for [client]. Returns true if acquired
     * (or already held by this client — idempotent). Returns false
     * if speech-to-text is muted, or another client holds it.
     */
    @Synchronized
    fun acquire(client: Client): Boolean {
        if (_muted.value) {
            Log.d(TAG, "acquire by $client refused — speech-to-text muted")
            return false
        }
        val current = _owner.value
        return if (current == null || current == client) {
            if (current == null) Log.d(TAG, "acquire by $client")
            _owner.value = client
            true
        } else {
            Log.d(TAG, "acquire by $client refused — owned by $current")
            false
        }
    }

    /**
     * Release the mic, but only if [client] is the current owner —
     * a stale release call from a previously-stopped client doesn't
     * accidentally yank the mic out from under a fresh acquirer.
     */
    @Synchronized
    fun release(client: Client) {
        if (_owner.value == client) {
            Log.d(TAG, "release by $client")
            _owner.value = null
        } else {
            Log.d(TAG, "release by $client ignored — current owner is ${_owner.value}")
        }
    }

    /** Force the mic free regardless of owner — used when muting. */
    @Synchronized
    private fun forceRelease() {
        if (_owner.value != null) {
            Log.d(TAG, "mic force-released — speech-to-text muted")
            _owner.value = null
        }
    }

    /** Human-readable label for use in UI error messages. */
    fun describe(client: Client): String = when (client) {
        Client.OBSERVE -> "Observe mode"
        Client.LUMI_LISTEN -> "Lumi wake-word listener"
        Client.CONTINUOUS_CHAT -> "continuous voice chat"
    }

    companion object {
        private const val TAG = "Mythara/MicBroker"
    }
}
