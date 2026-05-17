package com.mythara.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes a 128-D L2-normalised face embedding from a cropped face.
 *
 * Backed by MobileFaceNet (TensorFlow Lite) — small (~5 MB), fast
 * (~30 ms per inference on Pixel 10's NPU), and gives state-of-the-
 * art identity matching at 112×112 input. Weights are lazily loaded
 * from `filesDir/face/mobilefacenet.tflite`; downloader logic lives
 * in [FaceEmbedderModelDownloader].
 *
 * Public API:
 *   • [embed] takes a bitmap + face bbox and returns a FloatArray(128)
 *     or null when the model isn't available.
 *   • [isReady] — true iff the .tflite file exists + the interpreter
 *     has been initialised. Background code paths can short-circuit
 *     when false (analysis worker just no-ops; UI surfaces a "model
 *     downloading" hint).
 *
 * Cosine similarity between two embeddings is the identity score;
 * see [ContactFaceMatcher] for the matching logic.
 */
@Singleton
class FaceEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var modelFile: File? = null
    @Volatile private var activeBackend: Backend = Backend.None
    // Keep delegate refs alive so Interpreter doesn't read a freed
    // native pointer. Closed lazily when the embedder is re-init'd.
    @Volatile private var nnapiDelegate: NnApiDelegate? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null
    /** Embedding dim of the loaded model, read from its output
     *  tensor shape. Different MobileFaceNet variants emit 128-D
     *  or 192-D vectors; both work because we store length-prefixed
     *  blobs and the matcher only requires that query + stored
     *  embeddings have the same size. */
    @Volatile private var loadedEmbeddingDim: Int = EMBEDDING_DIM

    /** Which TFLite backend the interpreter is currently bound to.
     *  Surfaced via [backendName] so the People panel + Settings can
     *  show "running on NPU" / "GPU" / "CPU" for diagnostics. */
    enum class Backend { None, Nnapi, Gpu, Cpu }

    fun backendName(): String = when (activeBackend) {
        Backend.Nnapi -> "NNAPI (NPU)"
        Backend.Gpu -> "GPU"
        Backend.Cpu -> "CPU"
        Backend.None -> "uninitialised"
    }

    fun isReady(): Boolean {
        if (interpreter != null) return true
        val file = modelLocation()
        if (!file.exists()) return false
        // Try NNAPI → GPU → CPU in that order. The first one whose
        // Interpreter constructor succeeds wins; the others (and
        // their delegates) are torn down so they don't leak.
        return tryInitBackend(Backend.Nnapi, file) ||
            tryInitBackend(Backend.Gpu, file) ||
            tryInitBackend(Backend.Cpu, file)
    }

    private fun tryInitBackend(backend: Backend, file: File): Boolean = runCatching {
        // Each attempt allocates its own delegate; close any leftover
        // from a previous failed try before we try again.
        closeDelegates()
        val opts = Interpreter.Options()
        when (backend) {
            Backend.Nnapi -> {
                val d = NnApiDelegate()
                nnapiDelegate = d
                opts.addDelegate(d)
                // NNAPI doesn't support all ops; if it can't compile
                // the model graph at runtime, the Interpreter ctor
                // throws and we fall through to GPU.
            }
            Backend.Gpu -> {
                if (!CompatibilityList().isDelegateSupportedOnThisDevice) {
                    Log.i(TAG, "GPU delegate unsupported on this device")
                    return@runCatching false
                }
                val d = GpuDelegate()
                gpuDelegate = d
                opts.addDelegate(d)
            }
            Backend.Cpu -> {
                // 4 threads matches the typical face-analysis worker
                // concurrency; more wastes context on a model this
                // small.
                opts.numThreads = 4
            }
            Backend.None -> return@runCatching false
        }
        val itp = Interpreter(file, opts)
        val outShape = itp.getOutputTensor(0).shape()
        val dim = outShape.lastOrNull { it > 1 } ?: EMBEDDING_DIM
        interpreter = itp
        modelFile = file
        loadedEmbeddingDim = dim
        activeBackend = backend
        Log.i(TAG, "interpreter ready · backend = ${backendName()} · dim = $dim")
        true
    }.getOrElse {
        Log.w(TAG, "$backend init failed: ${it.message}")
        // Tear down whichever delegate we had attached so the next
        // attempt doesn't double-attach.
        closeDelegates()
        false
    }

    private fun closeDelegates() {
        runCatching { nnapiDelegate?.close() }
        runCatching { gpuDelegate?.close() }
        nnapiDelegate = null
        gpuDelegate = null
    }

    /**
     * Crop the face out of [bitmap] using [box], resize to 112×112,
     * normalise to [-1, 1], run MobileFaceNet, L2-normalise the
     * output. Returns a 128-D FloatArray or null on failure.
     */
    fun embed(bitmap: Bitmap, box: Rect): FloatArray? {
        if (!isReady()) return null
        val tflite = interpreter ?: return null

        val crop = runCatching {
            Bitmap.createBitmap(
                bitmap,
                box.left.coerceAtLeast(0),
                box.top.coerceAtLeast(0),
                (box.right - box.left).coerceAtMost(bitmap.width - box.left),
                (box.bottom - box.top).coerceAtMost(bitmap.height - box.top),
            ).let { Bitmap.createScaledBitmap(it, INPUT_DIM, INPUT_DIM, true) }
        }.getOrNull() ?: return null

        val input = ByteBuffer.allocateDirect(INPUT_DIM * INPUT_DIM * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_DIM * INPUT_DIM)
        crop.getPixels(pixels, 0, INPUT_DIM, 0, 0, INPUT_DIM, INPUT_DIM)
        for (p in pixels) {
            // Normalise to [-1, 1] per MobileFaceNet's training recipe.
            input.putFloat(((p shr 16 and 0xff) - 127.5f) / 128f)
            input.putFloat(((p shr 8 and 0xff) - 127.5f) / 128f)
            input.putFloat(((p and 0xff) - 127.5f) / 128f)
        }
        input.rewind()

        val output = Array(1) { FloatArray(loadedEmbeddingDim) }
        return runCatching {
            tflite.run(input, output)
            l2Normalise(output[0])
        }.getOrElse {
            Log.w(TAG, "tflite.run failed: ${it.message}")
            null
        }
    }

    private fun l2Normalise(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = kotlin.math.sqrt(sum).coerceAtLeast(1e-12f)
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / norm
        return out
    }

    private fun modelLocation(): File = File(context.filesDir, "face/$MODEL_NAME")

    companion object {
        private const val TAG = "Mythara/FaceEmbed"
        const val INPUT_DIM = 112
        const val EMBEDDING_DIM = 128
        const val MODEL_NAME = "mobilefacenet.tflite"

        /** Cosine distance between two L2-normalised embeddings.
         *  Equivalent to (1 - cosine-similarity); lower = more
         *  similar. Range [0, 2]. */
        fun cosineDistance(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return Float.MAX_VALUE
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return 1f - dot
        }
    }
}
