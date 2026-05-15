package com.mythara.services

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.mythara.branding.MytharaLiveWallpaperService
import java.io.File

/**
 * ADB-triggerable wallpaper applier. Lets the operator drop a Mythara
 * branding image onto the device's home + lockscreen with one shell
 * command (no third-party gallery / picker dance):
 *
 *   adb shell am broadcast \
 *     -a com.mythara.action.APPLY_WALLPAPER \
 *     -n com.mythara.debug/com.mythara.services.WallpaperApplyReceiver \
 *     --es path /sdcard/Pictures/mythara_wallpaper.png \
 *     --es target both
 *
 * `path` may be a filesystem path OR a content:// URI. `target` is one
 * of `home`, `lock`, `both` (default `both`). Decoded via
 * [BitmapFactory], handed to [WallpaperManager.setBitmap] with the
 * matching `which` flag(s). Errors are logged; the receiver never
 * crashes the host process. Exported so adb can reach it without the
 * app being in foreground.
 *
 * The application holds [android.permission.SET_WALLPAPER] (declared in
 * the manifest); no runtime grant required.
 */
class WallpaperApplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) {
            Log.w(TAG, "ignoring unknown action ${intent.action}")
            return
        }
        val targetArg = (intent.getStringExtra(EXTRA_TARGET) ?: "both").lowercase()

        // Live-wallpaper handoff. setWallpaperComponent() requires the
        // system-only SET_WALLPAPER_COMPONENT signature permission, so
        // we go through the user-facing chooser instead — which also
        // lets the user see the preview before committing. The chooser
        // is pre-loaded with our component so they only need to tap
        // "Set wallpaper" once. `path` extra is ignored in this mode.
        if (targetArg == "live") {
            launchLiveWallpaperPicker(context)
            return
        }

        val pathArg = intent.getStringExtra(EXTRA_PATH)
        if (pathArg.isNullOrBlank()) {
            Log.w(TAG, "missing required extra '$EXTRA_PATH'")
            return
        }
        val whichFlags = when (targetArg) {
            "home" -> WallpaperManager.FLAG_SYSTEM
            "lock" -> WallpaperManager.FLAG_LOCK
            "both" -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            else -> {
                Log.w(TAG, "unknown target '$targetArg'; defaulting to both")
                WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
            }
        }

        val bitmap = runCatching {
            // Accept either content:// URIs or raw filesystem paths.
            // Filesystem paths are the common case for adb-pushed PNGs.
            if (pathArg.startsWith("content://") || pathArg.startsWith("file://")) {
                val uri = Uri.parse(pathArg)
                context.contentResolver.openInputStream(uri).use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } else {
                val f = File(pathArg)
                if (!f.exists()) {
                    Log.e(TAG, "file does not exist: $pathArg")
                    return
                }
                BitmapFactory.decodeFile(f.absolutePath)
            }
        }.getOrElse {
            Log.e(TAG, "failed to decode bitmap from '$pathArg': ${it.message}", it)
            return
        }

        if (bitmap == null) {
            Log.e(TAG, "BitmapFactory returned null for '$pathArg'")
            return
        }

        runCatching {
            val wm = WallpaperManager.getInstance(context)
            // Pass `null` for visibleCropHint to let the system center-
            // crop, and `true` for `allowBackup` so it survives a backup-
            // restore. The `which` arg gates home / lock / both.
            wm.setBitmap(bitmap, null, true, whichFlags)
            Log.i(TAG, "wallpaper applied (${bitmap.width}x${bitmap.height}, target=$targetArg)")
        }.onFailure {
            Log.e(TAG, "WallpaperManager.setBitmap failed: ${it.message}", it)
        }
    }

    /**
     * Launch the system live-wallpaper preview pre-loaded with our
     * MytharaLiveWallpaperService component. The user taps "Set
     * wallpaper" in the preview to actually apply.
     *
     * Uses ACTION_CHANGE_LIVE_WALLPAPER (Android 4.0+) — every Pixel
     * + every Wear-OS-paired phone we ship to has this. Includes
     * NEW_TASK because the receiver isn't an Activity context.
     */
    private fun launchLiveWallpaperPicker(context: Context) {
        runCatching {
            val component = ComponentName(context, MytharaLiveWallpaperService::class.java)
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "launched live wallpaper picker for ${component.flattenToShortString()}")
        }.onFailure {
            Log.e(TAG, "live wallpaper picker failed: ${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "Mythara/WallpaperApply"
        const val ACTION = "com.mythara.action.APPLY_WALLPAPER"
        const val EXTRA_PATH = "path"
        const val EXTRA_TARGET = "target"
    }
}
