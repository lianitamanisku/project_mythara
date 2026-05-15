package com.mythara.resonance

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the two phone→watch Resonance signals over the Wearable Data
 * Layer:
 *
 *  - `RESONANCE_AVAIL` — flips the watch's toggle-dot visibility based
 *    on whether the user has enabled Resonance in the secret menu.
 *    Sent every time `ResonanceSettings.enabled` changes.
 *  - `RESONANCE_STATE` — `"active"` when a session starts;
 *    `"ended"` when it stops. The watch buzzes a short confirm haptic
 *    on either, and clears its local active flag on `"ended"` so the
 *    pad collapses if the phone ended the session for any reason
 *    (cap timer, headphone removal, audio-focus loss, …).
 *
 * Mirrors the small fire-and-forget MessageClient pattern used by
 * `WatchClusterDataPusher` / `WatchInsightPusher` — payloads are tiny
 * ASCII strings, no batching, failures logged + ignored.
 *
 * The wire path constants live in `MytharaWearListenerService` (phone)
 * and `WearPaths` (watch); they're kept in lockstep manually since
 * `:wear` is a separate Gradle module without a shared constants
 * artifact.
 */
@Singleton
class ResonanceWatchNotifier @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /** Tell the watch whether the user has enabled Resonance in the
     *  secret menu — gates the toggle-dot's visibility. */
    fun pushAvailability(enabled: Boolean) {
        push(PATH_AVAIL, if (enabled) "1" else "0")
    }

    /** Tell the watch a session is active or has ended — drives the
     *  short confirm haptic + collapses the pad on "ended". */
    fun pushState(active: Boolean) {
        push(PATH_STATE, if (active) "active" else "ended")
    }

    private fun push(path: String, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val nodeClient = Wearable.getNodeClient(ctx)
        val msgClient = Wearable.getMessageClient(ctx)
        nodeClient.connectedNodes
            .addOnSuccessListener { nodes ->
                for (node in nodes) {
                    msgClient.sendMessage(node.id, path, bytes)
                        .addOnFailureListener { e ->
                            Log.w(TAG, "send $path to ${node.displayName} failed: ${e.message}")
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "could not list connected nodes for $path: ${e.message}")
            }
    }

    companion object {
        private const val TAG = "Mythara/ResonanceNotif"

        // Phone → watch resonance paths (mirror wear/.../WearPaths).
        const val PATH_AVAIL = "/mythara/resonance/avail"
        const val PATH_STATE = "/mythara/resonance/state"
    }
}
