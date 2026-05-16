package com.mythara.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import androidx.core.app.NotificationManagerCompat

/**
 * Single dedicated screen for managing every permission Mythara
 * holds — both standard runtime permissions (mic, camera, contacts,
 * etc.) AND Android's "special" permissions (notification listener,
 * accessibility service, default assistant, exact alarm, etc.) that
 * live behind separate Settings deep-links.
 *
 * For runtime permissions: tap → standard permission prompt. Once
 * granted/denied the row updates automatically when the screen
 * resumes (we re-check on lifecycle ON_RESUME).
 *
 * For special permissions: tap → opens the system Settings page for
 * the relevant toggle. We can't programmatically request these — the
 * user has to flip a switch in Settings — but we deep-link straight
 * to the right page so it's two taps from here.
 *
 * Currently-granted permissions show a Bok-bordered row + green tick;
 * not-granted ones show a Charple-bordered row + a "tap to enable"
 * button. The user can therefore see the full surface of permissions
 * Mythara CAN hold + flip individual ones on/off without having to
 * dig into Settings → Apps → Mythara → Permissions.
 */
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh tick so the rows re-evaluate on every onResume. Updated
    // both when the lifecycle bounces (user came back from a system
    // settings page) and when the launched permission contract
    // returns.
    var refresh by remember { mutableStateOf(0) }
    DisposableObserver(lifecycleOwner) {
        if (it == Lifecycle.Event.ON_RESUME) refresh++
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> refresh++ }

    val items = remember(refresh) {
        permissionItems(context)
    }
    val granted by derivedStateOf { items.count { it.isGranted } }
    val total by derivedStateOf { items.size }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = "PERMISSIONS",
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = MytharaColors.Fg, letterSpacing = 3.sp,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${Glyph.AccentBar} every permission Mythara can hold, in one place. " +
                    "Tap any row to grant, revoke, or change. $granted of $total enabled.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
            )
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.key }) { perm ->
                PermissionRow(
                    item = perm,
                    onTap = {
                        when (perm.kind) {
                            PermKind.Runtime -> {
                                if (perm.isGranted) {
                                    // Android doesn't let an app
                                    // revoke its own runtime
                                    // permissions — only the system
                                    // Settings page can. Deep-link
                                    // there with the misleading
                                    // "tap to revoke" copy
                                    // disambiguated by the row's
                                    // own action label below.
                                    openAppSettings(context)
                                } else {
                                    // Standard system overlay prompt
                                    // — appears as a modal dialog
                                    // over Mythara, no Settings
                                    // page involved.
                                    permLauncher.launch(perm.runtimePerms.toTypedArray())
                                }
                            }
                            PermKind.Special -> {
                                // Special permissions can't be
                                // toggled programmatically — open
                                // the relevant Settings page.
                                perm.specialIntent?.let { intent ->
                                    runCatching { context.startActivity(intent) }
                                        .onFailure { openAppSettings(context) }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    item: PermissionItem,
    onTap: () -> Unit,
) {
    val accent = if (item.isGranted) MytharaColors.Bok else MytharaColors.Charple
    val borderWidth = if (item.isGranted) 1.5.dp else 1.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(borderWidth, accent, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${if (item.isGranted) Glyph.DiamondFilled else Glyph.DiamondOutline} ${item.title}",
                color = if (item.isGranted) MytharaColors.Bok else MytharaColors.Fg,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = if (item.isGranted) "GRANTED" else "OFF",
                color = if (item.isGranted) MytharaColors.Bok else MytharaColors.FgMute,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.subtitle,
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        // Action copy honesty:
        //   - Granted runtime / special perms can ONLY be revoked
        //     in the system Settings page — apps can't revoke
        //     their own perms. Say so explicitly.
        //   - Not-granted runtime perms get the standard system
        //     overlay prompt (popup dialog, no Settings page).
        //   - Not-granted special perms always need a Settings
        //     deep-link (notification listener, accessibility,
        //     etc. live behind toggles only the user can flip).
        TextButton(onClick = onTap) {
            Text(
                text = when {
                    item.isGranted -> "tap to manage in Settings"
                    item.kind == PermKind.Runtime -> "tap to grant (system popup)"
                    else -> "tap to enable in Settings"
                },
                color = accent,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

// ─── Data model ──────────────────────────────────────────────────────

private enum class PermKind { Runtime, Special }

private data class PermissionItem(
    val key: String,
    val title: String,
    val subtitle: String,
    val kind: PermKind,
    val isGranted: Boolean,
    val runtimePerms: List<String> = emptyList(),
    val specialIntent: Intent? = null,
)

private fun permissionItems(ctx: Context): List<PermissionItem> {
    val pkg = ctx.packageName
    fun rt(perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
        }

    val list = mutableListOf<PermissionItem>()

    list += PermissionItem(
        key = "mic",
        title = "microphone",
        subtitle = "voice chat, push-to-talk, ambient Observe mode",
        kind = PermKind.Runtime,
        isGranted = rt(Manifest.permission.RECORD_AUDIO),
        runtimePerms = listOf(Manifest.permission.RECORD_AUDIO),
    )
    list += PermissionItem(
        key = "camera",
        title = "camera",
        subtitle = "the take_photo agent tool, face-tracking on the Face screen",
        kind = PermKind.Runtime,
        isGranted = rt(Manifest.permission.CAMERA),
        runtimePerms = listOf(Manifest.permission.CAMERA),
    )
    list += PermissionItem(
        key = "contacts",
        title = "contacts",
        subtitle = "match notification senders to people, address-book lookup",
        kind = PermKind.Runtime,
        isGranted = rt(Manifest.permission.READ_CONTACTS),
        runtimePerms = listOf(Manifest.permission.READ_CONTACTS),
    )
    list += PermissionItem(
        key = "calendar",
        title = "calendar",
        subtitle = "read events, schedule, pre-announce upcoming items",
        kind = PermKind.Runtime,
        isGranted = rt(Manifest.permission.READ_CALENDAR) &&
            rt(Manifest.permission.WRITE_CALENDAR),
        runtimePerms = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        ),
    )
    list += PermissionItem(
        key = "sms-read",
        title = "read SMS",
        subtitle = "context for incoming messages from people",
        kind = PermKind.Runtime,
        isGranted = rt(Manifest.permission.READ_SMS),
        runtimePerms = listOf(Manifest.permission.READ_SMS),
    )
    list += PermissionItem(
        key = "sms-send",
        title = "send SMS",
        subtitle = "the send_text agent tool, auto-replies",
        kind = PermKind.Runtime,
        isGranted = rt(Manifest.permission.SEND_SMS),
        runtimePerms = listOf(Manifest.permission.SEND_SMS),
    )
    list += PermissionItem(
        key = "phone",
        title = "phone (place call)",
        subtitle = "the place_call agent tool",
        kind = PermKind.Runtime,
        isGranted = rt(Manifest.permission.CALL_PHONE),
        runtimePerms = listOf(Manifest.permission.CALL_PHONE),
    )
    list += PermissionItem(
        key = "location",
        title = "location",
        subtitle = "weather lookups, location-aware responses",
        kind = PermKind.Runtime,
        isGranted = rt(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            rt(Manifest.permission.ACCESS_FINE_LOCATION),
        runtimePerms = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += PermissionItem(
            key = "notifications-post",
            title = "post notifications",
            subtitle = "Mythara's own QuickTalk + reminder notifications",
            kind = PermKind.Runtime,
            isGranted = rt(Manifest.permission.POST_NOTIFICATIONS),
            runtimePerms = listOf(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    // Special permissions — can't be requested programmatically, only
    // toggled in system Settings. We deep-link to the right page.

    list += PermissionItem(
        key = "notif-listener",
        title = "notification access",
        subtitle = "see (and triage) incoming notifications from other apps",
        kind = PermKind.Special,
        isGranted = NotificationManagerCompat
            .getEnabledListenerPackages(ctx)
            .contains(pkg),
        specialIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
    )
    list += PermissionItem(
        key = "accessibility",
        title = "accessibility service",
        subtitle = "screen-reading + (when enabled) automation taps/swipes",
        kind = PermKind.Special,
        isGranted = isAccessibilityEnabled(ctx),
        specialIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )
    list += PermissionItem(
        key = "exact-alarm",
        title = "exact alarms",
        subtitle = "wake at the exact second a reminder fires",
        kind = PermKind.Special,
        isGranted = canScheduleExactAlarms(ctx),
        specialIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.fromParts("package", pkg, null)
            }
        } else null,
    )
    list += PermissionItem(
        key = "default-assist",
        title = "default assistant app",
        subtitle = "Pixel Buds long-press / squeeze-to-assist routes here",
        kind = PermKind.Special,
        // No reliable system API to query default-assistant; tap-to-
        // open the picker so the user can verify visually.
        isGranted = false,
        specialIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
    )
    list += PermissionItem(
        key = "default-launcher",
        title = "default home / launcher",
        subtitle = "Mythara takes the home gesture (chat IS your home screen)",
        kind = PermKind.Special,
        isGranted = false,    // same — open picker to verify
        specialIntent = Intent(Settings.ACTION_HOME_SETTINGS),
    )
    list += PermissionItem(
        key = "battery",
        title = "ignore battery optimisation",
        subtitle = "keeps Mythara's foreground service alive in deep doze",
        kind = PermKind.Special,
        isGranted = isIgnoringBatteryOpt(ctx),
        specialIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else null,
    )
    list += PermissionItem(
        key = "overlay",
        title = "draw over other apps",
        subtitle = "lock-screen + cross-app Dynamic Island overlay (mirrors the in-app pill)",
        kind = PermKind.Special,
        isGranted = com.mythara.services.LockscreenIslandService.canRender(ctx),
        specialIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}"),
        ),
    )
    // Keystroke learning — the most privacy-sensitive opt-in
    // Mythara has. Default OFF; the row's `isGranted` reflects
    // whether the underlying Accessibility service is on (which
    // is the precondition); the actual KeyLearnStore toggle
    // lives in Settings → Resonance & Learning so it gets the
    // proper async-aware Switch UI rather than the install-style
    // row this screen renders.
    list += PermissionItem(
        key = "keystroke-learn",
        title = "always-learn from keystrokes (sensitive!)",
        subtitle = "default OFF · turn on in Settings → Resonance & Learning. " +
            "Requires Accessibility above. Passwords + banking/payment apps always blocked.",
        kind = PermKind.Special,
        // isGranted shows accessibility-enabled state — the
        // store toggle lives elsewhere (see subtitle).
        isGranted = isAccessibilityEnabled(ctx),
        specialIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    return list
}

// ─── Special-permission status helpers ───────────────────────────────

private fun isAccessibilityEnabled(ctx: Context): Boolean {
    val enabled = Settings.Secure.getString(
        ctx.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val pkg = ctx.packageName
    // Match any service inside our package — we don't hard-code the
    // class name here so future renames don't silently break the
    // status check.
    return enabled.split(':').any {
        ComponentName.unflattenFromString(it)?.packageName == pkg
    }
}

private fun canScheduleExactAlarms(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    return am.canScheduleExactAlarms()
}

private fun isIgnoringBatteryOpt(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun openAppSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", ctx.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
        )
    }
}

@Composable
private fun DisposableObserver(
    owner: androidx.lifecycle.LifecycleOwner,
    onEvent: (Lifecycle.Event) -> Unit,
) {
    androidx.compose.runtime.DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> onEvent(e) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
