package com.mythara.agent.tools

import android.content.Context
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.ui.canvas.CanvasController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `render_canvas` — push an HTML render to the Canvas surface.
 *
 * The agent's visual channel. When text alone underserves the user —
 * an image you generated, an explainer card, a mini-game, a breath
 * pacer — render to the Canvas instead.
 *
 * Modes (delivery):
 *   - `mode=inline`: HTML body passed directly. Up to ~32 KB.
 *   - `mode=file`: HTML written to filesDir/canvas/<uuid>.html.
 *
 * Templates (skeleton wrapping):
 *   - `template=blank`: no wrapper. Agent owns `<html>` + `<head>`.
 *   - `template=tailwind` (default): wraps in a `<!doctype html>`
 *     skeleton that pre-loads Tailwind Play CDN (~85 KB) + DaisyUI
 *     styled core (~140 KB) + Mythara theme overrides. Agent
 *     writes ONLY the body content using Tailwind utilities and
 *     DaisyUI components.
 *   - `template=preact-tailwind`: same as tailwind PLUS Preact +
 *     HTM (~5 KB combined) so the agent can write JSX-shaped
 *     components without a build step.
 *
 * All templates load from `file:///android_asset/canvas/` (the
 * baseURL CanvasScreen already configures), so no CDN fetches
 * happen at render time — fully offline.
 *
 * Set `retain=true` to keep the render after the user navigates
 * away. Set `auto_navigate=false` to suppress the UI pivot to
 * Canvas (default true).
 */
@Singleton
class RenderCanvasTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: CanvasController,
) : Tool {
    override val name = "render_canvas"
    override val description =
        "Render HTML to Mythara's Canvas surface. Default template wraps the agent's HTML " +
            "with Tailwind v3 + DaisyUI + the Mythara theme (data-theme='mythara'), so the " +
            "agent writes ONLY body content using Tailwind utility classes + DaisyUI " +
            "components (`btn`, `card`, `badge`, `stat`, `alert`, `chat`, etc.). " +
            "Templates: 'tailwind' (default), 'preact-tailwind' (adds Preact + HTM for " +
            "interactive state via the `html\\`...\\`` template literal), 'webgl' (adds " +
            "Three.js r147 for 3D / shader playgrounds — use `mythara.three.boot('#stage')` " +
            "boilerplate-free), 'webgl-preact' (everything). 'blank' for custom <html>. " +
            "All assets are bundled — no CDN fetches at render time."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("html", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "HTML for the body (or full document if template=blank). " +
                        "JS bridge `window.mythara.sendInput(json)` is available.",
                )
            })
            put("template", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "'tailwind' (default, ~580 KB Tailwind+DaisyUI) | 'preact-tailwind' " +
                        "(+~18 KB Preact+HTM for state) | 'webgl' (+~600 KB Three.js r147 " +
                        "as window.THREE + mythara.three.boot helper) | 'webgl-preact' " +
                        "(everything) | 'blank' (no wrapper). All assets bundled offline.",
                )
            })
            put("mode", buildJsonObject {
                put("type", "string")
                put("description", "'inline' (default, <= 32 KB) or 'file' (for larger).")
            })
            put("retain", buildJsonObject {
                put("type", "boolean")
                put("description", "Keep render across navigation. Default false.")
            })
            put("auto_navigate", buildJsonObject {
                put("type", "boolean")
                put("description", "Auto-pivot the UI to Canvas. Default true.")
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("html"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        val rawHtml = args["html"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (rawHtml.isBlank()) return ToolResult.fail("html must be a non-empty body")
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull()?.lowercase() ?: "inline"
        val template = args["template"]?.jsonPrimitive?.contentOrNull()?.lowercase() ?: "tailwind"
        val retain = args["retain"]?.jsonPrimitive?.booleanOrNull() ?: false
        val autoNavigate = args["auto_navigate"]?.jsonPrimitive?.booleanOrNull() ?: true

        // Diagnostic — log template + body preview so we can see why
        // a WebGL render came out blank (most common cause: the
        // agent didn't pick template="webgl" so THREE wasn't loaded).
        val usesThree = rawHtml.contains("THREE.") || rawHtml.contains("mythara.three")
        val templateLoadsThree = template == "webgl" || template == "webgl-preact"
        android.util.Log.d(
            "Mythara/Canvas",
            "render template=$template mode=$mode bodyLen=${rawHtml.length} " +
                "usesThree=$usesThree templateLoadsThree=$templateLoadsThree" +
                if (usesThree && !templateLoadsThree)
                    " — MISMATCH: body uses THREE but template doesn't load it"
                else "",
        )
        android.util.Log.d(
            "Mythara/Canvas",
            "body preview: ${rawHtml.take(280).replace('\n', '·')}",
        )

        val wrapped = when (template) {
            "blank" -> rawHtml
            "tailwind" -> wrapTailwind(rawHtml, withPreact = false, withThree = false)
            "preact-tailwind" -> wrapTailwind(rawHtml, withPreact = true, withThree = false)
            "webgl" -> wrapTailwind(rawHtml, withPreact = false, withThree = true)
            "webgl-preact" -> wrapTailwind(rawHtml, withPreact = true, withThree = true)
            else -> return ToolResult.fail(
                "template must be 'tailwind' | 'preact-tailwind' | 'webgl' | 'webgl-preact' | 'blank'",
            )
        }

        return when (mode) {
            "inline" -> {
                controller.render(
                    CanvasController.Render(
                        mode = CanvasController.RenderMode.Inline,
                        payload = wrapped,
                        retain = retain,
                    ),
                    autoNavigate = autoNavigate,
                )
                ToolResult.ok(
                    "rendered ${wrapped.length} chars inline (template=$template, retain=$retain, nav=$autoNavigate)",
                )
            }
            "file" -> {
                runCatching {
                    val dir = File(context.filesDir, "canvas").apply { mkdirs() }
                    val file = File(dir, "${UUID.randomUUID()}.html")
                    file.writeText(wrapped)
                    controller.render(
                        CanvasController.Render(
                            mode = CanvasController.RenderMode.File,
                            payload = file.absolutePath,
                            retain = retain,
                        ),
                        autoNavigate = autoNavigate,
                    )
                    ToolResult.ok("wrote ${file.name} (${wrapped.length} chars, template=$template) and rendered")
                }.getOrElse { ToolResult.fail("file write failed: ${it.message}") }
            }
            else -> ToolResult.fail("mode must be 'inline' or 'file'")
        }
    }

    /** Compose a full HTML document around the agent's body content.
     *  Loads the bundled Tailwind Play CDN + DaisyUI styled core +
     *  the Mythara theme overlay. Optionally adds Preact + HTM for
     *  interactive components. Optionally adds Three.js r147 (UMD,
     *  ~594 KB) for WebGL / shader playgrounds — bound to a single
     *  global `THREE` so the agent's body script can do
     *  `new THREE.Scene()` directly. */
    private fun wrapTailwind(body: String, withPreact: Boolean, withThree: Boolean): String = buildString {
        append("""<!doctype html>
<html lang="en" data-theme="mythara">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <title>Mythara Canvas</title>
  <link rel="stylesheet" href="daisyui.css">
  <link rel="stylesheet" href="canvas.css">
  <script src="tailwind.min.js"></script>
  <script>
    // Tailwind Play CDN config — extend the theme with Mythara's
    // palette so `bg-mythara-charple`, `text-mythara-bok`, etc. work
    // alongside DaisyUI's `btn-primary` / `bg-base-200` shorthand.
    if (window.tailwind) {
      tailwind.config = {
        darkMode: 'class',
        theme: {
          extend: {
            fontFamily: {
              mono: ['JetBrains Mono', 'ui-monospace', 'SF Mono', 'Menlo', 'monospace'],
            },
            colors: {
              mythara: {
                bg:       '#1B1A22',
                surface:  '#26252E',
                surfaceMid: '#3A3943',
                surfaceHi:  '#4E4D58',
                fg:       '#E8E6F0',
                fgMute:   '#A8A4AB',
                fgDim:    '#605F6B',
                charple:  '#6B50FF',
                bok:      '#68FFD6',
                lavender: '#9B86FF',
                julep:    '#00FFB2',
                citron:   '#E8FF27',
                mustard:  '#F5EF34',
                malibu:   '#00A4FF',
                sriracha: '#EB4268',
              },
            },
          },
        },
      };
    }
  </script>
""")
        if (withPreact) {
            append("""  <script src="preact.min.js"></script>
  <script src="preact-hooks.min.js"></script>
  <script src="htm.min.js"></script>
  <script>
    // Expose a tiny no-build JSX shim. Bindings are reachable
    // TWO ways so whichever style the agent reaches for works:
    //   1. window.mythara.{h, render, html, useState, ...}
    //   2. top-level globals: h, render, html, useState, ...
    // The second path matters because the natural code shape
    // the LLM emits is `const { useState } = preact;` /
    // `const h = html;` — direct globals — rather than
    // destructuring from window.mythara. Field-tested: 1 prod
    // failure on a mood-selector render that used top-level
    // refs and crashed with "html is not defined".
    const __mh = htm.bind(preact.h);
    // Top-level globals — `useState` / `html` work without a prefix.
    window.html = __mh;
    window.h = preact.h;
    window.render = preact.render;
    window.Fragment = preact.Fragment;
    window.useState = preactHooks.useState;
    window.useEffect = preactHooks.useEffect;
    window.useReducer = preactHooks.useReducer;
    window.useRef = preactHooks.useRef;
    window.useMemo = preactHooks.useMemo;
    window.useCallback = preactHooks.useCallback;
    // window.mythara.* — namespaced access.
    window.mythara = window.mythara || {};
    Object.assign(window.mythara, {
      h: preact.h,
      render: preact.render,
      Fragment: preact.Fragment,
      html: __mh,
      useState: preactHooks.useState,
      useEffect: preactHooks.useEffect,
      useReducer: preactHooks.useReducer,
      useRef: preactHooks.useRef,
      useMemo: preactHooks.useMemo,
      useCallback: preactHooks.useCallback,
    });
    // ALSO patch hooks onto `preact` itself — the natural code
    // shape `const { useState } = preact` would otherwise fail
    // because preact's core bundle doesn't ship hooks; they live
    // on preactHooks. Field-tested: model produced exactly that
    // destructure on every webgl-preact render.
    Object.assign(preact, {
      useState: preactHooks.useState,
      useEffect: preactHooks.useEffect,
      useReducer: preactHooks.useReducer,
      useRef: preactHooks.useRef,
      useMemo: preactHooks.useMemo,
      useCallback: preactHooks.useCallback,
    });
  </script>
""")
        }
        if (withThree) {
            // Three.js r147 (last UMD release) loads as a window
            // global. The agent can `new THREE.Scene()`, build
            // geometries / materials / lights, and render via
            // `THREE.WebGLRenderer`. ShaderMaterial covers the
            // shader-playground use case (vertex + fragment GLSL
            // inline). For Mythara's dark canvas we pre-set
            // `renderer.setClearColor(0x1B1A22)` if the agent's
            // script doesn't override it (helper exposed on
            // `window.mythara.three.boot(canvas)` below).
            append("""  <script src="three.min.js"></script>
  <script>
    // Tiny boot helper so the agent doesn't have to retype the
    // boilerplate every time. Usage from the body script:
    //   const { scene, camera, renderer } = mythara.three.boot('#stage');
    //   scene.add(new THREE.Mesh(geom, mat));
    //   renderer.setAnimationLoop(() => renderer.render(scene, camera));
    window.mythara = window.mythara || {};
    window.mythara.three = {
      boot(selector, opts = {}) {
        const host = typeof selector === 'string'
          ? document.querySelector(selector)
          : selector;
        if (!host) throw new Error('mythara.three.boot: host not found: ' + selector);
        const w = opts.width  ?? host.clientWidth  ?? window.innerWidth;
        const h = opts.height ?? host.clientHeight ?? window.innerHeight;
        const scene = new THREE.Scene();
        scene.background = new THREE.Color(opts.bg ?? 0x1B1A22);
        const camera = new THREE.PerspectiveCamera(
          opts.fov ?? 60, w / h, opts.near ?? 0.1, opts.far ?? 1000,
        );
        camera.position.set(...(opts.cameraPos ?? [0, 0, 5]));
        const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
        renderer.setPixelRatio(window.devicePixelRatio || 1);
        renderer.setSize(w, h, false);
        renderer.setClearColor(opts.bg ?? 0x1B1A22);
        host.appendChild(renderer.domElement);
        // Resize on viewport changes.
        const onResize = () => {
          const nw = host.clientWidth || window.innerWidth;
          const nh = host.clientHeight || window.innerHeight;
          camera.aspect = nw / nh;
          camera.updateProjectionMatrix();
          renderer.setSize(nw, nh, false);
        };
        window.addEventListener('resize', onResize);
        return { scene, camera, renderer, dispose() {
          window.removeEventListener('resize', onResize);
          renderer.dispose();
          renderer.domElement.remove();
        }};
      },
    };
  </script>
""")
        }
        // Body skeleton — `<div id="root">` is ALWAYS present (even
        // when the agent's body doesn't mention it) so calling
        // `render(vnode, document.getElementById('root'))` never
        // resolves to null. The agent can either:
        //   1. Put structural HTML directly under the wrapper (no
        //      Preact involvement — content lands inside #root,
        //      Preact ignores it).
        //   2. Mount a Preact tree into #root via render(...) —
        //      Preact takes over and replaces children.
        // The wrapper class still drives the dark background +
        // safe-area inset styling from canvas.css.
        append("""</head>
<body class="bg-mythara-bg text-mythara-fg">
  <div class="mythara-stage">
    <div id="root" class="w-full h-full">
""")
        append(body)
        append("""    </div>
  </div>
</body>
</html>
""")
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun JsonPrimitive.booleanOrNull(): Boolean? = runCatching { content.toBoolean() }.getOrNull()
}
