package com.mythara.agent.tools

import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.ui.canvas.CanvasController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `update_canvas` — push a JavaScript snippet that updates the
 * currently-rendered Canvas in place.
 *
 * Use this for incremental changes — e.g. after the agent's move in
 * a tic-tac-toe game, update the relevant cell without re-rendering
 * the whole board. Cheaper than [RenderCanvasTool] and preserves
 * any in-page state the user has accumulated.
 *
 * The snippet is `eval`d in the page's main world via
 * `webView.evaluateJavascript()`. Don't post sensitive data through
 * this path — it shows up in webView console.
 */
@Singleton
class UpdateCanvasTool @Inject constructor(
    private val controller: CanvasController,
) : Tool {
    override val name = "update_canvas"
    override val description =
        "Run a JS snippet against the current Canvas render to update it in place (no re-load)."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("js", buildJsonObject {
                put("type", "string")
                put("description", "JavaScript snippet to evaluate against the current Canvas WebView.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("js"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val js = args["js"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (js.isBlank()) return ToolResult.fail("js must be a non-empty JavaScript snippet")
        // Log the snippet so update_canvas misuses are visible in
        // logcat.
        android.util.Log.d(
            "Mythara/Canvas",
            "update_canvas jsLen=${js.length} preview=${js.take(240).replace('\n', '·')}",
        )
        // Defensive: detect "setup" patterns that should have been
        // in render_canvas. update_canvas runs in a different scope
        // from the initial page load, so calls like
        // `mythara.three.boot()` / `new THREE.Scene()` /
        // `new THREE.WebGLRenderer()` would just leak undefined refs
        // into the global, then the next `scene.add(...)` blows up
        // with "Cannot read properties of undefined". Catch + redirect.
        val isSetupAttempt =
            js.contains("mythara.three.boot") ||
                js.contains("new THREE.Scene") ||
                js.contains("new THREE.PerspectiveCamera") ||
                js.contains("new THREE.WebGLRenderer") ||
                js.contains("scene = ") || js.contains("scene=") ||
                js.contains("renderer = ") || js.contains("renderer=")
        if (isSetupAttempt) {
            android.util.Log.w(
                "Mythara/Canvas",
                "update_canvas REFUSED: snippet looks like scene setup. Put it in render_canvas instead.",
            )
            return ToolResult.fail(
                "setup_in_update_canvas: this snippet looks like Three.js scene setup " +
                    "(boot / new Scene / new WebGLRenderer / scene assignment). update_canvas " +
                    "runs in a different JS scope from the initial page load — the scene/" +
                    "camera/renderer never reach your snippet. " +
                    "FIX: call render_canvas(template='webgl', html='...') with the WHOLE setup " +
                    "(host div + <script> that calls mythara.three.boot + scene.add + " +
                    "renderer.setAnimationLoop) in ONE call. Use update_canvas only for " +
                    "incremental tweaks AFTER the scene exists (e.g. mesh.material.color.set(...)).",
            )
        }
        controller.updateJs(js)
        return ToolResult.ok("queued ${js.length}-char js snippet")
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
}
