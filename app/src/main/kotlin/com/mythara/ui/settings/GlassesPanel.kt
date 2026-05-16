package com.mythara.ui.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mythara.glasses.GlassesConnectionService
import com.mythara.glasses.GlassesConnectionState
import com.mythara.glasses.GlassesDatFacade
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.launch

/**
 * Settings panel for the Meta Display Glasses integration.
 *
 * Two layers of gating sit in front of the DAT SDK lifecycle:
 *
 *  1. **BLUETOOTH_CONNECT runtime permission.** Without it the SDK can
 *     not reach the Meta companion app even when Stella/Meta AI is
 *     installed and the glasses are paired — registrationState stays
 *     UNAVAILABLE indefinitely. The panel detects this and shows a
 *     "grant Bluetooth permission" button BEFORE the state-driven
 *     pairing flow. After grant, we force-re-init the facade so the
 *     SDK rebuilds its provider observers.
 *
 *  2. **Meta companion app (Stella / Meta AI) installed AND registered.**
 *     The DAT SDK reads registration state from that companion app. The
 *     panel's `Initialized` branch hands off to `startRegistration` so
 *     the companion app drives the user-facing pairing flow.
 *
 * After both gates are passed, the panel surfaces session controls
 * (start/stop) for [GlassesConnectionService].
 *
 * Mirrors the ShizukuPanel visual style.
 */
@Composable
fun GlassesPanel() {
    val ctx = LocalContext.current
    val state by GlassesDatFacade.connectionState.collectAsState()
    val regError by GlassesDatFacade.lastRegistrationError.collectAsState()
    val sessionError by GlassesDatFacade.lastSessionError.collectAsState()
    val needsGlassesAppUpdate by GlassesDatFacade.glassesAppUpdateRequired.collectAsState()
    val cameraPerm by GlassesDatFacade.cameraPermission.collectAsState()
    val micPerm by GlassesDatFacade.microphonePermission.collectAsState()
    val discoveredDevices by GlassesDatFacade.discoveredDevices.collectAsState()
    val needsFirmwareUpdate by GlassesDatFacade.firmwareUpdateRequired.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // DAT-side permission launchers — wrap Stella's per-permission UI
    // behind single button taps. Results drop back into the facade's
    // permission flows so the panel re-renders.
    val datPermLauncher = rememberLauncherForActivityResult(
        Wearables.RequestPermissionContract(),
    ) { _ ->
        coroutineScope.launch { GlassesDatFacade.refreshDatPermissions() }
    }
    // First permission that's not Granted — drives the button label
    // and the launch payload. CAMERA prompted before MICROPHONE.
    val missingPerm: Permission? = when {
        cameraPerm != GlassesDatFacade.DatPermission.Granted -> Permission.CAMERA
        micPerm != GlassesDatFacade.DatPermission.Granted -> Permission.MICROPHONE
        else -> null
    }

    // Runtime BLUETOOTH_CONNECT (API 31+) check + request. Without
    // this, every state path is moot — the SDK can't see anything.
    var btGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        btGranted = granted
        if (granted) {
            // Re-initialize the SDK so it rebuilds its provider
            // observers now that BT_CONNECT is live.
            GlassesDatFacade.reinitialize(ctx)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} meta display glasses — POV photos, neural-band PTT, on-glasses cards",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(6.dp))

        if (!GlassesDatFacade.DISPLAY_PATH_ENABLED) {
            Text(
                text = "${Glyph.Dot} state: parked",
                color = MytharaColors.FgMute,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${Glyph.AccentBar} Glasses pairing + permissions are wired end-to-end on " +
                    "Mythara's side (registration, DAT camera/mic perms, device discovery, retry on " +
                    "tap), but every session attempt dies at Meta's on-glasses DWA-version handshake — " +
                    "the SDK's `DAM session started but DWA did not report its version` log line. " +
                    "That points at a deployment-pipeline gap on Meta's Wearables Developer Center " +
                    "(no DWA bundle published for this app's package + signature), not anything " +
                    "fixable from inside this APK. Parked here until that's resolved upstream — flip " +
                    "GlassesDatFacade.DISPLAY_PATH_ENABLED to re-enable.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            return@Column
        }

        if (!btGranted) {
            // GATE 1 — Bluetooth permission. Show this BEFORE any
            // DAT state because the SDK is blind without it.
            Text(
                text = "${Glyph.Dot} state: bluetooth permission required",
                color = MytharaColors.Mustard,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${Glyph.AccentBar} Mythara needs Bluetooth permission to talk to the Meta " +
                    "companion app on your phone. The DAT SDK reports `UNAVAILABLE` until this " +
                    "is granted, even when the glasses are physically paired.",
                color = MytharaColors.FgDim,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MytharaColors.Charple,
                    contentColor = MytharaColors.Bg,
                ),
            ) { Text("grant bluetooth permission") }
            return@Column
        }

        val statusColor = when (state) {
            GlassesConnectionState.SessionActive -> MytharaColors.Bok
            GlassesConnectionState.Paired -> MytharaColors.Bok
            GlassesConnectionState.Error -> MytharaColors.Mustard
            GlassesConnectionState.Disconnected -> MytharaColors.Mustard
            else -> MytharaColors.Charple
        }
        Text(
            text = "${Glyph.Dot} state: ${state.name}",
            color = statusColor,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        when (state) {
            GlassesConnectionState.NotInitialized -> {
                Text(
                    text = "${Glyph.AccentBar} The Meta companion app (Stella) reports the SDK as " +
                        "UNAVAILABLE. The most common cause is that Developer Mode isn't enabled in " +
                        "Stella — open the Stella app → Settings → About → tap the version 5× to " +
                        "expose the developer menu, then enable it. Confirm the glasses appear in " +
                        "Stella's device list, then tap retry.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!regError.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${Glyph.Dot} sdk reports: $regError",
                        color = MytharaColors.Mustard,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { GlassesDatFacade.reinitialize(ctx) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Bg,
                    ),
                ) { Text("retry") }
            }
            GlassesConnectionState.Initialized -> {
                Text(
                    text = "${Glyph.AccentBar} Ready to pair Mythara with your glasses. " +
                        "Tap below — the Meta companion app will open to walk you through registration.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        (ctx as? Activity)?.let { GlassesDatFacade.startRegistration(it) }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Bg,
                    ),
                ) { Text("register glasses") }
            }
            GlassesConnectionState.Paired -> {
                Text(
                    text = "${Glyph.AccentBar} Paired with the Meta companion app. Start a session " +
                        "to wake the glasses display + camera stream — Mythara will hold the " +
                        "session alive in the background until you stop it or disconnect the glasses.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!sessionError.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${Glyph.Dot} last attempt: $sessionError",
                        color = MytharaColors.Mustard,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (sessionError!!.contains("NO_ELIGIBLE", ignoreCase = true) &&
                        !needsGlassesAppUpdate
                    ) {
                        Text(
                            text = "${Glyph.AccentBar} Likely cause: missing or invalid " +
                                "mwdat_application_id / mwdat_client_token. Register an app at " +
                                "https://wearables.developer.meta.com/, drop both values into " +
                                "local.properties, then rebuild + reinstall Mythara.",
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (needsGlassesAppUpdate) {
                        Text(
                            text = "${Glyph.AccentBar} Likely cause: the DAT runtime ON THE " +
                                "GLASSES HARDWARE is outdated. Tap below to deeplink into " +
                                "Stella's App Connections page, then inside Stella find Mythara " +
                                "→ tap update → wait for the OTA to physically propagate to the " +
                                "glasses (can take a few minutes; glasses must be on, charged, " +
                                "and in BT range). Don't tap start session again until Stella " +
                                "shows the update as complete.",
                            color = MytharaColors.FgDim,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (discoveredDevices.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    discoveredDevices.forEach { d ->
                        Text(
                            text = "${Glyph.Dot} ${d.name} · fw ${d.firmware} · " +
                                "${d.linkState} · displayCapable=${d.displayCapable} · " +
                                "compat=${d.compatibility}",
                            color = if (d.compatibility == "COMPATIBLE") MytharaColors.Bok
                                else MytharaColors.Mustard,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (needsFirmwareUpdate) {
                    Text(
                        text = "${Glyph.AccentBar} A device reports DEVICE_UPDATE_REQUIRED — the " +
                            "glasses firmware (not the DAT app) is out of date. Tap below to launch " +
                            "Stella's firmware-update flow.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (missingPerm != null && !needsGlassesAppUpdate && !needsFirmwareUpdate) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${Glyph.Dot} dat permissions — camera: ${cameraPerm.name}, " +
                            "mic: ${micPerm.name}",
                        color = MytharaColors.Mustard,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "${Glyph.AccentBar} The device kills sessions with the generic " +
                            "SESSION_ENDED_BY_DEVICE if either DAT permission isn't Granted via " +
                            "Stella (separate from Android runtime perms). Grant both — the button " +
                            "below cycles through whichever is missing.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        needsGlassesAppUpdate -> Button(
                            onClick = {
                                (ctx as? Activity)?.let { act ->
                                    coroutineScope.launch {
                                        GlassesDatFacade.openDATGlassesAppUpdate(act)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Bg,
                            ),
                        ) { Text("update glasses app") }
                        needsFirmwareUpdate -> Button(
                            onClick = {
                                (ctx as? Activity)?.let { act ->
                                    coroutineScope.launch {
                                        GlassesDatFacade.openFirmwareUpdate(act)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Bg,
                            ),
                        ) { Text("update glasses firmware") }
                        missingPerm != null -> Button(
                            onClick = { datPermLauncher.launch(missingPerm) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Bg,
                            ),
                        ) {
                            Text(
                                if (missingPerm == Permission.CAMERA) "grant glasses camera"
                                else "grant glasses microphone",
                            )
                        }
                        else -> Button(
                            onClick = { GlassesConnectionService.start(ctx) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Bg,
                            ),
                        ) { Text("start session") }
                    }
                    OutlinedButton(
                        onClick = {
                            (ctx as? Activity)?.let { GlassesDatFacade.startUnregistration(it) }
                        },
                    ) { Text("unregister") }
                }
            }
            GlassesConnectionState.SessionActive -> {
                Text(
                    text = "${Glyph.AccentBar} Session live. Glasses display is rendering Mythara's " +
                        "Root card. Tap-tap on the neural band → photo. Press-and-hold → PTT.",
                    color = MytharaColors.Bok,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { GlassesConnectionService.stop(ctx) },
                    ) { Text("stop session") }
                    OutlinedButton(
                        onClick = {
                            (ctx as? Activity)?.let { GlassesDatFacade.startUnregistration(it) }
                        },
                    ) { Text("unregister") }
                }
            }
            GlassesConnectionState.Disconnected -> {
                Text(
                    text = "${Glyph.AccentBar} Session ended — usually because the glasses were folded, " +
                        "Bluetooth dropped, or another app took the device.",
                    color = MytharaColors.FgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!sessionError.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${Glyph.Dot} last error: $sessionError",
                        color = MytharaColors.Mustard,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (needsGlassesAppUpdate) {
                    Text(
                        text = "${Glyph.AccentBar} The DAT runtime ON THE GLASSES HARDWARE is " +
                            "outdated. Tap below to deeplink into Stella's App Connections page, " +
                            "then inside Stella: find Mythara → tap update → wait for the OTA to " +
                            "physically propagate to the glasses (glasses must be on, charged, " +
                            "and in BT range — the progress bar can take a few minutes). Don't " +
                            "tap restart session until Stella shows the update as complete.",
                        color = MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        needsGlassesAppUpdate -> Button(
                            onClick = {
                                (ctx as? Activity)?.let { act ->
                                    coroutineScope.launch {
                                        GlassesDatFacade.openDATGlassesAppUpdate(act)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Bg,
                            ),
                        ) { Text("update glasses app") }
                        needsFirmwareUpdate -> Button(
                            onClick = {
                                (ctx as? Activity)?.let { act ->
                                    coroutineScope.launch {
                                        GlassesDatFacade.openFirmwareUpdate(act)
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Bg,
                            ),
                        ) { Text("update glasses firmware") }
                        missingPerm != null -> Button(
                            onClick = { datPermLauncher.launch(missingPerm) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Bg,
                            ),
                        ) {
                            Text(
                                if (missingPerm == Permission.CAMERA) "grant glasses camera"
                                else "grant glasses microphone",
                            )
                        }
                        else -> Button(
                            onClick = { GlassesConnectionService.start(ctx) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Bg,
                            ),
                        ) { Text("restart session") }
                    }
                }
            }
            GlassesConnectionState.Error -> {
                Text(
                    text = "${Glyph.AccentBar} The DAT SDK reported an error during initialization. " +
                        "Check that the Meta companion app is up to date.",
                    color = MytharaColors.Mustard,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { GlassesDatFacade.reinitialize(ctx) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Bg,
                    ),
                ) { Text("retry") }
            }
        }
    }
}
