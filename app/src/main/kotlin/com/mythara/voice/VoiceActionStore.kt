package com.mythara.voice

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide signal that "the user wants Lumi to listen NOW".
 *
 * Today's sole producer is [com.mythara.MainActivity] when Android
 * delivers it an `ACTION_ASSIST` / `ACTION_VOICE_COMMAND` /
 * `ACTION_WEB_SEARCH` (i.e. the user set Mythara as the default
 * Digital Assistant app and triggered it — Pixel Buds touch-and-hold,
 * "Hey Google" hot-word replacement, or the assist gesture). The
 * consumer is [com.mythara.ui.chat.ChatViewModel], which kicks off a
 * one-shot SpeechRecognition listen and routes the final transcript
 * back into the agent loop via [com.mythara.ui.chat.ChatViewModel.submit].
 *
 * Buffer is small with DROP_OLDEST so a burst of taps (Pixel Buds can
 * double-fire when worn loosely) collapses to "listen once" rather
 * than queuing up several listen turns.
 */
@Singleton
class VoiceActionStore @Inject constructor() {

    /** Reason a voice action was requested; surfaced for debug/logging.
     *
     *  [RosePress] is the press-and-hold gesture on the floating rose
     *  amulet (Capability Expansion v2). Functionally identical to
     *  [AssistIntent] at the ChatViewModel level — both fire a one-shot
     *  SpeechRecognition listen — but kept distinct so audit logs can
     *  tell the user how they invoked the agent. */
    enum class Source {
        AssistIntent,
        VoiceCommandIntent,
        WebSearchIntent,
        External,
        RosePress,
    }

    data class VoiceTrigger(val source: Source, val tsMillis: Long = System.currentTimeMillis())

    /**
     * Buffered channel rather than a SharedFlow: we want the trigger
     * to wait if there's no consumer yet (user is on Settings / auth
     * gate / not even in foreground). When ChatScreen later attaches
     * its collector, it picks up the pending trigger and runs STT.
     *
     * Buffer = 4 with DROP_OLDEST so a burst (Pixel Buds can fire
     * twice when worn loosely) collapses to one listen, not a queue.
     */
    private val channel = Channel<VoiceTrigger>(
        capacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val triggers: Flow<VoiceTrigger> = channel.receiveAsFlow()

    fun fire(source: Source) {
        channel.trySend(VoiceTrigger(source = source))
    }
}
