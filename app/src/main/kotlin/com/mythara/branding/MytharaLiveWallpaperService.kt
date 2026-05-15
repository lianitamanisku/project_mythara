package com.mythara.branding

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.util.Log

/**
 * Mythara live wallpaper.
 *
 * Composition mirrors the static wallpaper that the
 * [com.mythara.services.WallpaperApplyReceiver] applies, with one
 * twist: the rose at canvas-centre is *animated*. It rotates very
 * slowly (one full revolution every 90 seconds) and its cyan hexagon
 * nucleus pulses opacity at a calm breath rate (~12 cycles per
 * minute), so the brand mark reads as a living amulet rather than a
 * static logo. Everything else — gradient, node-graph mesh, warrior
 * silhouette, "MYTHARA 1.0" wordmark, backronym tagline with bold
 * cyan capital anchors — is pre-baked into a single bundled bitmap
 * (`R.drawable.wallpaper_static_layers`) so the per-frame work stays
 * cheap: one bitmap blit + a handful of polygon fills per tick.
 *
 * Refresh rate is throttled to ~12 fps. The motion is so slow that
 * higher rates would just burn battery without any perceivable
 * difference. The engine pauses entirely when the wallpaper isn't
 * visible (during fullscreen apps, screen off, etc.) — Android calls
 * [Engine.onVisibilityChanged] for us.
 *
 * Apply via the existing applier:
 *
 *   adb shell am broadcast \\
 *     -a com.mythara.action.APPLY_WALLPAPER \\
 *     -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \\
 *     --es target live
 *
 * which fires `ACTION_CHANGE_LIVE_WALLPAPER` pre-loaded with this
 * service — the user taps "Set wallpaper" once.
 */
class MytharaLiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = MytharaEngine()

    private inner class MytharaEngine : Engine() {
        private val renderer = WallpaperRenderer(this@MytharaLiveWallpaperService)
        private val handler = Handler(Looper.getMainLooper())
        private val tick = Runnable { drawFrame() }
        private var visible = false
        private var startMs = 0L

        override fun onCreate(holder: SurfaceHolder?) {
            super.onCreate(holder)
            startMs = System.currentTimeMillis()
            // Bundled assets are loaded lazily by the renderer the
            // first time it sees a non-zero surface size, so there's
            // nothing to do here yet.
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder?,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.setSize(width, height)
            drawFrame()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                drawFrame()
            } else {
                handler.removeCallbacks(tick)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            handler.removeCallbacks(tick)
            renderer.release()
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val t = System.currentTimeMillis() - startMs
                    renderer.render(canvas, t)
                }
            } catch (e: Exception) {
                Log.w(TAG, "drawFrame failed: ${e.message}")
            } finally {
                if (canvas != null) {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
            handler.removeCallbacks(tick)
            // ~12 fps. The rose rotation step at this cadence is
            // 360° / 90s / 12fps ≈ 0.33° per frame — smooth enough
            // for a slow rotation, cheap on battery.
            if (visible) handler.postDelayed(tick, 80)
        }
    }

    companion object {
        private const val TAG = "Mythara/LiveWP"
    }
}
