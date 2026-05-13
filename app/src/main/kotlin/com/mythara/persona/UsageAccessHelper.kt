package com.mythara.persona

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Companion helper for the `PACKAGE_USAGE_STATS` special permission.
 * Unlike runtime permissions (Camera, Location, etc.), the OS won't
 * surface a prompt for this one — the user has to navigate to
 * Settings → Apps → Special app access → Usage access and toggle
 * Mythara on themselves.
 *
 * The check uses [AppOpsManager.checkOpNoThrow] against the
 * `GET_USAGE_STATS` op rather than `ContextCompat.checkSelfPermission`
 * because `PACKAGE_USAGE_STATS` is technically declared but only
 * actually grants access via the special-access flow.
 */
@Singleton
class UsageAccessHelper @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun isGranted(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            ctx.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }
}
