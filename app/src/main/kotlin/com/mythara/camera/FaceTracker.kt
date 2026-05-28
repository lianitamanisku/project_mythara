package com.mythara.camera

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Front-camera face tracker for the Face avatar screen.
 *
 * Runs a CameraX [ImageAnalysis] stream off the front camera through
 * ML Kit's on-device face detector and publishes a smoothed [Pose] —
 * head euler angles + per-eye open probability — as a [StateFlow] the
 * Face screen drives its point-cloud head from.
 *
 * Bound to [ProcessLifecycleOwner] (same pattern as [CameraCapture]),
 * so [bind] / [unbind] are cheap to call from a Compose
 * `DisposableEffect`: the camera runs ONLY while the Face screen is on
 * screen, and there's no preview surface — the avatar IS the
 * viewfinder. ML Kit's model is bundled, so this is fully on-device.
 */
@Singleton
class FaceTracker @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val pickupDetector: PhonePickupDetector,
) {
    /**
     * Smoothed head pose. Angle fields are normalised to roughly
     * [-1, 1] (clamped); eye fields are [0, 1] (1 = fully open).
     * [present] is false when no face is in frame.
     */
    data class Pose(
        val present: Boolean = false,
        val yaw: Float = 0f,
        val pitch: Float = 0f,
        val roll: Float = 0f,
        val leftEyeOpen: Float = 1f,
        val rightEyeOpen: Float = 1f,
    )

    private val _pose = MutableStateFlow(Pose())
    val pose: StateFlow<Pose> = _pose.asStateFlow()

    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                // classification gives us the eye-open probabilities the
                // avatar blinks from; landmarks/contours aren't needed.
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.2f)
                .build(),
        )
    }
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var provider: ProcessCameraProvider? = null
    @Volatile private var analysis: ImageAnalysis? = null
    @Volatile private var bound = false
    private var missCount = 0

    /**
     * Face-ID-style power-save gate. CameraX always streams the
     * sensor at native rate (we can't easily change that without a
     * second `IDLE` ImageAnalysis target), so we throttle on the
     * software side: while no face is in frame, drop incoming
     * frames so ML Kit's detector only fires at ~[IDLE_TARGET_HZ]
     * instead of the sensor's full 30 fps. Once a face appears we
     * release the gate immediately and run every frame for smooth
     * head-pose tracking, the same way Face ID on iOS slows its
     * IR projector when nothing's there.
     *
     * Net effect: roughly 10× drop in CPU + GPU + thermals while
     * the screen is open but the user isn't looking — matches the
     * user's "save battery while idle" ask without a separate
     * power-management layer.
     */
    @Volatile private var lastDetectedNs: Long = 0L

    /** Start the front-camera stream. Idempotent. */
    fun bind() {
        if (bound) return
        bound = true
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: run {
                bound = false
                return@addListener
            }
            provider = p
            val ia = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            analysis = ia
            runCatching {
                p.unbind(ia)
                p.bindToLifecycle(
                    ProcessLifecycleOwner.get(),
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    ia,
                )
                Log.d(TAG, "front-camera face tracking bound")
            }.onFailure {
                Log.w(TAG, "bind failed: ${it.message}")
                bound = false
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    /** Stop the stream + reset the pose. Idempotent. */
    fun unbind() {
        if (!bound) return
        bound = false
        runCatching { analysis?.let { provider?.unbind(it) } }
        analysis = null
        missCount = 0
        _pose.value = Pose()
        Log.d(TAG, "front-camera face tracking unbound")
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        // Face-ID-style throttle. While no face is currently tracked,
        // skip incoming frames so the detector runs at
        // [IDLE_TARGET_HZ] instead of the sensor's full ~30 fps.
        // As soon as we DO detect a face we record the timestamp and
        // run every frame, so head-pose tracking stays smooth. Frame
        // dropping is critical to do BEFORE the InputImage allocation
        // + detector.process call — that's where the GPU + CPU cost
        // lives.
        val nowNs = System.nanoTime()
        val present = _pose.value.present
        val tracking = present || (nowNs - lastDetectedNs) < ACTIVE_HOLD_NS
        if (!tracking) {
            val sinceLastNs = nowNs - lastDetectedNs
            if (sinceLastNs < IDLE_FRAME_INTERVAL_NS) {
                // Skip this frame — close the proxy so CameraX can
                // recycle the buffer, then bail.
                proxy.close()
                return
            }
            // Stamp the slot so the next IDLE_FRAME_INTERVAL_NS of
            // frames also skip; this one we'll actually analyze.
            lastDetectedNs = nowNs
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (face == null) {
                    // tolerate a few empty frames before declaring "no
                    // face" — ML Kit drops the odd frame even with a
                    // face plainly in view.
                    if (++missCount >= MISS_LIMIT && _pose.value.present) {
                        _pose.value = _pose.value.copy(present = false)
                    }
                    return@addOnSuccessListener
                }
                missCount = 0
                // Stamp the active-hold window so we keep running at
                // full rate even between successful detections, as
                // long as we've seen a face within the past
                // ACTIVE_HOLD_NS. Prevents an isolated dropped frame
                // from dropping us back to idle-rate mid-tracking.
                lastDetectedNs = System.nanoTime()
                // Refresh the pickup detector's active-window timer
                // so the camera stays bound while a face is actually
                // in front of the lens. Without this, the 8 s window
                // would close mid-stare and we'd have to wait for
                // another pickup gesture to re-engage.
                pickupDetector.extendWindow()
                val cur = _pose.value
                val first = !cur.present
                // ML Kit euler angles are degrees. NOTE: signs assume the
                // avatar should MIRROR the user (mirror-image tracking);
                // flip a divisor's sign here if it ever reads reversed.
                val yaw = (face.headEulerAngleY / YAW_RANGE).coerceIn(-1f, 1f)
                val pitch = (face.headEulerAngleX / PITCH_RANGE).coerceIn(-1f, 1f)
                val roll = (-face.headEulerAngleZ / ROLL_RANGE).coerceIn(-1f, 1f)
                val le = face.leftEyeOpenProbability ?: 1f
                val re = face.rightEyeOpenProbability ?: 1f
                _pose.value = Pose(
                    present = true,
                    // snap straight to the first reading, then EMA-smooth
                    yaw = if (first) yaw else ema(cur.yaw, yaw, ANGLE_SMOOTH),
                    pitch = if (first) pitch else ema(cur.pitch, pitch, ANGLE_SMOOTH),
                    roll = if (first) roll else ema(cur.roll, roll, ANGLE_SMOOTH),
                    leftEyeOpen = if (first) le else ema(cur.leftEyeOpen, le, EYE_SMOOTH),
                    rightEyeOpen = if (first) re else ema(cur.rightEyeOpen, re, EYE_SMOOTH),
                )
            }
            .addOnFailureListener { Log.w(TAG, "detect failed: ${it.message}") }
            .addOnCompleteListener { proxy.close() }
    }

    private fun ema(old: Float, new: Float, a: Float) = old * (1f - a) + new * a

    companion object {
        private const val TAG = "Mythara/FaceTracker"
        // Divisors normalise a comfortable head-turn range to [-1, 1];
        // anything past that clamps.
        private const val YAW_RANGE = 32f
        private const val PITCH_RANGE = 26f
        private const val ROLL_RANGE = 32f
        private const val ANGLE_SMOOTH = 0.35f // EMA weight toward each new reading
        private const val EYE_SMOOTH = 0.6f    // snappier so blinks register
        private const val MISS_LIMIT = 6       // empty frames before "no face"

        /** Face-ID-style idle detector rate. While no face is in
         *  frame we throttle the analyzer down to this many faces-
         *  per-second to spare CPU + GPU + thermals. 3 Hz is fast
         *  enough that the gather animation can start within ~330 ms
         *  of the user appearing — well under the perception
         *  threshold for "the screen noticed me". */
        private const val IDLE_TARGET_HZ = 3
        private const val IDLE_FRAME_INTERVAL_NS = 1_000_000_000L / IDLE_TARGET_HZ

        /** Hold the analyzer in full-rate mode for this long after
         *  the last successful detection, so a brief miss (looking
         *  down, hand across face) doesn't drop us back to idle-rate
         *  mid-tracking. Pose.present already tolerates MISS_LIMIT
         *  frames; this stays well below that so the hold expires
         *  only after the pose has officially gone idle too. */
        private const val ACTIVE_HOLD_NS = 1_500_000_000L
    }
}
