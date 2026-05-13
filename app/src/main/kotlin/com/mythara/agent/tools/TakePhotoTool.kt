package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.camera.CameraCapture
import com.mythara.minimax.VisionService
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `take_photo` — capture a single still from the device's camera and
 * save it to private app storage. The tool returns JSON with the path,
 * dimensions, byte size, and lens used. A follow-up `vision_query`
 * tool (M5+) will route the saved image through MiniMax-VL-01 for
 * "what's in this photo" prompts.
 *
 * Model usage patterns:
 *  - "Take a picture of this" → call with default args (back lens)
 *  - "Take a selfie" → `lens: "front"`
 *
 * Failure modes the model needs to understand:
 *  - `permission_denied` — CAMERA not granted; user must allow it
 *  - `not_ready` — camera couldn't be opened (Mythara likely
 *    backgrounded, or another app is holding the camera)
 *  - `capture_failed` — CameraX returned a hardware/driver error
 *
 * Read-only-ish — captures don't modify external state, only write a
 * file to Mythara's private filesDir. ConfirmationGate is therefore
 * not required; the user is implicitly consenting by asking for a
 * photo. We disclose the saved path in the tool result so the model
 * can mention it if relevant ("I saved it at .../photos/...jpg").
 */
@Singleton
class TakePhotoTool @Inject constructor(
    private val capture: CameraCapture,
    private val vision: VisionService,
) : Tool {

    @Serializable
    data class Response(
        val path: String,
        val widthPx: Int,
        val heightPx: Int,
        val sizeBytes: Long,
        val lens: String,
        val captureTimeMs: Long,
        /** Free-text description from the vision backend. Null when vision failed. */
        val description: String? = null,
        /** Error code if vision call failed; null on success. */
        val visionError: String? = null,
        /** Which vision backend handled the call: "gemini" | "minimax-vl". */
        val visionBackend: String? = null,
    )

    override val name: String = "take_photo"

    override val description: String =
        "Capture one photo using the phone's camera AND get a vision-model description of what's in it. " +
            "Returns the file path, dimensions, lens used, and a short description. " +
            "Use when the user asks 'take a picture of this', 'take a selfie', or 'what do you see?'. " +
            "Pass a `prompt` argument to focus the description on a specific question " +
            "(e.g. 'is the person in this picture wearing a helmet?')."

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "lens",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Which camera to use. 'back' for the rear camera (default; what the user is pointing the phone at), 'front' for the selfie camera.",
                        )
                        put(
                            "enum",
                            kotlinx.serialization.json.JsonArray(
                                listOf(JsonPrimitive("back"), JsonPrimitive("front")),
                            ),
                        )
                    },
                )
                put(
                    "prompt",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "What to look for / answer about the photo. Optional; defaults to a generic short description. Be specific when the user's request implies a question — e.g. 'count the people' or 'read the text on the sign'.",
                        )
                    },
                )
            },
        )
        put("required", kotlinx.serialization.json.JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val lensArg = (args["lens"] as? JsonPrimitive)?.content?.lowercase()
        val lens = when (lensArg) {
            "front", "selfie" -> CameraCapture.Lens.Front
            else -> CameraCapture.Lens.Back
        }
        val promptArg = (args["prompt"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        return when (val r = capture.capture(lens)) {
            is CameraCapture.Result.Ok -> {
                // Vision pass — always run unless the photo couldn't be
                // read. We surface the result inside the SAME tool
                // result so the agent loop sees the description on the
                // very next iteration (no chained-tool round-trip
                // required).
                val visionOutcome = runCatching {
                    vision.describeImage(
                        imageFile = File(r.path),
                        prompt = promptArg ?: VisionService.DEFAULT_PROMPT,
                    )
                }.getOrElse { e ->
                    VisionService.Outcome(
                        ok = false,
                        text = e.message ?: e.javaClass.simpleName,
                        code = "threw",
                    )
                }
                val response = Response(
                    path = r.path,
                    widthPx = r.widthPx,
                    heightPx = r.heightPx,
                    sizeBytes = r.sizeBytes,
                    lens = r.lens,
                    captureTimeMs = r.captureTimeMs,
                    description = visionOutcome.text.takeIf { visionOutcome.ok },
                    visionError = if (visionOutcome.ok) null else visionOutcome.code,
                    visionBackend = visionOutcome.backend,
                )
                ToolResult(ok = true, output = JSON.encodeToString(Response.serializer(), response))
            }
            is CameraCapture.Result.Fail -> ToolResult(
                ok = false,
                output = """{"error":"${r.code}","detail":${JsonPrimitive(r.detail)}}""",
            )
        }
    }

    companion object {
        private val JSON = Json { encodeDefaults = false; prettyPrint = false }
    }
}
