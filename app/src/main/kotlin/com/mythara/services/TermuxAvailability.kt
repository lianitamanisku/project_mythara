package com.mythara.services

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that introspects whether Termux (the F-Droid terminal app)
 * is reachable for the agent's `termux_exec` / `termux_api` tools.
 *
 * Why this matters: Mythara's existing `run_shell` runs inside the
 * app's sandbox UID — that's enough for toybox + a chunk of GNU, but
 * it CAN'T `apt install <pkg>` (no Debian userland), can't open a real
 * TTY, and can't reach the `termux-api` Android-platform bridge tools
 * (clipboard, camera, location, TTS, vibrator, sensors). Termux gives
 * us all of that — for free — via its `com.termux.RUN_COMMAND` intent.
 *
 * The catch is that RUN_COMMAND is gated by Termux's own property
 * file (`allow-external-apps=true` in `~/.termux/termux.properties`)
 * which Android's PackageManager can't introspect. So we can only
 * detect:
 *
 *   1. Whether the Termux package is installed at all.
 *   2. Whether the Termux:API companion is installed (separate APK,
 *      provides the `termux-*` binaries that the `termux_api` tool
 *      wraps).
 *   3. Whether the user has clicked "Verify" in the Settings panel —
 *      that fires a benign `echo mythara-ready` through RUN_COMMAND
 *      and proves end-to-end that the property file is configured.
 *
 * We persist the last successful verification timestamp so the panel
 * can show "verified at HH:mm" instead of asking the user to verify
 * on every visit. Re-verification is cheap (single echo).
 */
@Singleton
class TermuxAvailability @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {

    /** Lifecycle states the panel + tools branch on. */
    enum class State {
        /** Termux package not installed — install from F-Droid. */
        NotInstalled,

        /** Termux is installed but the user hasn't clicked Verify yet
         *  (or the last verification was cleared). The agent can still
         *  attempt `termux_exec`; the panel just doesn't show a green
         *  light. */
        NeedsVerification,

        /** Verified, but Termux:API companion is missing — `termux_exec`
         *  works for arbitrary shell commands; `termux_api` won't because
         *  the `termux-*` binaries it wraps aren't present. */
        ReadyMissingApi,

        /** Both packages installed, last verification succeeded, every
         *  Termux tool path is live. */
        Ready,
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Compute the current state. Called from the panel on resume + by
     *  the verify button after marking success. Pure read; no IO. */
    fun state(pm: PackageManager = ctx.packageManager): State {
        if (!isInstalled(pm, TERMUX_PKG)) return State.NotInstalled
        val verified = prefs.getLong(KEY_LAST_VERIFIED, 0L) > 0L
        if (!verified) return State.NeedsVerification
        val apiInstalled = isInstalled(pm, TERMUX_API_PKG)
        return if (apiInstalled) State.Ready else State.ReadyMissingApi
    }

    /** Stamp now() into prefs — call after a verify ping echoed back
     *  the expected token. */
    fun markVerified() {
        prefs.edit().putLong(KEY_LAST_VERIFIED, System.currentTimeMillis()).apply()
    }

    /** Wipe the verification flag. Surface this from the panel so a
     *  user who reinstalled Termux can force a re-verify. */
    fun clearVerified() {
        prefs.edit().remove(KEY_LAST_VERIFIED).apply()
    }

    /** Epoch-ms of last successful verification, or null if never. */
    fun lastVerifiedAt(): Long? = prefs.getLong(KEY_LAST_VERIFIED, 0L).takeIf { it > 0L }

    /** Convenience for tools that want to short-circuit before
     *  attempting an exec on devices where Termux isn't installed
     *  at all. */
    fun isInstalled(): Boolean = isInstalled(ctx.packageManager, TERMUX_PKG)

    /** True when the installed Termux package came from the Google
     *  Play Store. The Play Store edition is a stripped-down build
     *  that DOESN'T ship `RunCommandService` — meaning `termux_exec`
     *  will always fail with "service not found" no matter how the
     *  user configures termux.properties. The fix is to uninstall
     *  the Play variant + install the F-Droid build, which is the
     *  only maintained release.
     *
     *  Returns false when Termux isn't installed at all, OR when
     *  it's installed from F-Droid / GitHub / sideload. */
    fun isPlayStoreVariant(): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx.packageManager.getInstallSourceInfo(TERMUX_PKG).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstallerPackageName(TERMUX_PKG)
        }
        installer == "com.android.vending"
    }.getOrDefault(false)

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(pkg, 0)
    }.isSuccess

    companion object {
        const val TERMUX_PKG = "com.termux"
        const val TERMUX_API_PKG = "com.termux.api"

        /** Where Termux installs its binaries — `/data/data/com.termux
         *  /files/usr/bin/<name>`. Both tools resolve bare command names
         *  against this prefix. Required because Termux's RUN_COMMAND
         *  rejects relative paths and won't search $PATH for us. */
        const val TERMUX_BIN_DIR = "/data/data/com.termux/files/usr/bin"

        private const val PREFS = "mythara_termux_availability"
        private const val KEY_LAST_VERIFIED = "last_verified_ms"
    }
}
