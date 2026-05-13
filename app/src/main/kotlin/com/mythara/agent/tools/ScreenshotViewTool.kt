package com.mythara.agent.tools

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.minimax.VisionService
import com.mythara.services.PhoneControlAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `screenshot_view` — capture the currently-displayed screen and run
 * it through the configured vision model so the agent can SEE
 * what's on-screen, not just read its accessibility text.
 *
 * Use cases:
 *   - WhatsApp / Messages conversations carrying images (the
 *     accessibility tree exposes them only as "Photo" or "Image";
 *     this tool lets the agent actually know what the image is)
 *   - Apps that render text on Canvas / WebView and don't expose
 *     accessibility text (Twitter timeline, banking apps —
 *     though those are HARD-BLOCKED upstream)
 *   - Verifying a UI state before tapping ("am I really on the
 *     send screen?" before invoking tap)
 *
 * The captured bitmap is saved to app-private filesDir and deleted
 * immediately after the vision call returns. Raw screen contents
 * never persist — only the model's text description.
 *
 * Requires the accessibility service to be granted; takeScreenshot
 * is an API 30+ AccessibilityService method that doesn't pop a
 * MediaProjection consent dialog (unlike the alternative path),
 * which is essential for autopilot use on locked screens.
 *
 * The tool is read-only — it never taps, types, or sends. Lives in
 * the [com.mythara.agent.CriticalActionGuard]'s READ_TOOLS allowlist
 * so even when the foreground is a "critical" app (Uber checkout,
 * etc.) the agent can still inspect what's there before deciding.
 */
@Singleton
class ScreenshotViewTool @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val vision: VisionService,
) : Tool {

    override val name: String = "screenshot_view"
    override val description: String =
        "Capture the currently-displayed screen and describe what's on it via the configured vision model. " +
            "Use after open_app or after a UI transition when you need to SEE the screen content beyond what read_screen surfaces — " +
            "specifically: image messages in chat apps (WhatsApp / Messages / Signal), Canvas-rendered text, " +
            "verifying a UI state before tapping. Returns a description + the rough on-screen text from the accessibility tree. " +
            "Read-only — never sends or taps. Always allowed regardless of autopilot state. " +
            "Pair with read_screen for full context: this tool answers \"what does the picture say\" while read_screen " +
            "answers \"which buttons / fields are tappable.\""

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "focus",
                    buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Optional. Specific thing to focus the vision model on (e.g., \"the message attached at the bottom of the chat\", \"the photo above the timestamp\"). Defaults to a general screen-content description when omitted.",
                        )
                    },
                )
            },
        )
        put("required", JsonArray(emptyList()))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val service = PhoneControlAccessibilityService.instance
            ?: return ToolResult(
                ok = false,
                output = """{"error":"accessibility_not_granted","detail":"Enable Mythara in Settings → Accessibility. Without it, screen capture isn't possible."}""",
            )

        val focusArg = (args["focus"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val prompt = buildPrompt(focusArg)

        val bitmap = runCatching { service.takeScreenshotBitmap() }.getOrNull()
            ?: return ToolResult(
                ok = false,
                output = """{"error":"screenshot_failed","detail":"AccessibilityService.takeScreenshot returned null. Device may be below API 30, secure-flagged surface, or hardware buffer rejected."}""",
            )

        // Always sample the accessibility tree's package + a small
        // text excerpt alongside the vision description. Gives the
        // model two grounded signals about the same screen — useful
        // when one is ambiguous.
        val rootPkg = service.currentForegroundPackage().orEmpty()
        val rootTextExcerpt = collectVisibleText(service)

        val file = saveBitmap(bitmap)
        // Recycle bitmap right after persisting to JPEG — the file
        // is what VisionService will read.
        runCatching { bitmap.recycle() }

        try {
            val outcome = runCatching {
                vision.describeImage(imageFile = file, prompt = prompt)
            }.getOrElse { e ->
                Log.w(TAG, "describeImage threw", e)
                VisionService.Outcome(ok = false, text = e.message ?: e.javaClass.simpleName, code = "threw")
            }

            val response = buildJsonObject {
                put("ok", outcome.ok)
                put("foreground_pkg", rootPkg)
                if (rootTextExcerpt.isNotBlank()) {
                    put("accessibility_text_excerpt", rootTextExcerpt.take(MAX_A11Y_TEXT))
                }
                if (outcome.ok) {
                    put("description", outcome.text.trim())
                    outcome.backend?.let { put("vision_backend", it) }
                } else {
                    put("error", outcome.code ?: "vision_failed")
                    put("detail", outcome.text)
                }
            }
            return ToolResult(ok = outcome.ok, output = response.toString())
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun buildPrompt(focusArg: String): String {
        val base = "Describe what's on this Android screen. Identify the app if obvious from layout/branding. " +
            "If there's a chat conversation, summarise the visible messages and call out any images shared inline " +
            "(what's actually in the image, not just \"there is an image\"). " +
            "If there are UI elements to interact with (buttons, fields), name them. " +
            "Keep it under 4 sentences."
        return if (focusArg.isNotEmpty()) "$base\nSpecial focus: $focusArg" else base
    }

    /**
     * Walk the accessibility tree and concatenate visible text /
     * content descriptions. Bounded so we don't ship a JSON dump as
     * part of the tool result.
     */
    private fun collectVisibleText(service: PhoneControlAccessibilityService): String {
        val root = service.currentRootNode() ?: return ""
        val out = StringBuilder()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_A11Y_NODES) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank()) { out.append(text).append(' ') }
            if (desc.isNotBlank() && desc != text) { out.append(desc).append(' ') }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    private suspend fun saveBitmap(bmp: Bitmap): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "screenshots").apply { mkdirs() }
        val file = File(dir, "shot_${System.nanoTime()}.jpg")
        FileOutputStream(file).use { fos ->
            // 80% quality is plenty for vision — bumping higher just
            // makes the upload heavier without improving recognition.
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos)
        }
        file
    }

    companion object {
        private const val TAG = "Mythara/Shot"
        private const val MAX_A11Y_NODES = 300
        private const val MAX_A11Y_TEXT = 1_500
    }
}
