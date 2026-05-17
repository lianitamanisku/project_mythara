package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mythara.minimax.Region
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import kotlinx.coroutines.launch

/**
 * Settings — region picker, API key field (validated against `/models`),
 * model picker. The validate button is the only network-side action; if
 * it returns 2xx we mark the key OK and persist; otherwise we surface
 * the mapped error inline.
 *
 * UX layout follows Crush's panel rhythm: one bordered section per
 * concern, headers in dim mono, body in body-medium mono.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onOpenPeople: () -> Unit = {},
    /** Navigator to the full-screen audit viewer (Phase K). Surfaced
     *  from the AuditLogPanel's "view full" link. */
    onOpenAudit: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Phase B — MytharaScaffold provides the header (← back / ◆
    // settings) + brand background. This Column owns the content
    // body only. systemBars insets stay so deep scrollback isn't
    // hidden under the gesture-nav home pill.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        AutopilotPanel()

        Spacer(Modifier.height(16.dp))
        EnterpriseAutopilotPanel()

        Spacer(Modifier.height(16.dp))
        FavoritesPanel()

        Spacer(Modifier.height(16.dp))
        AutoTriagePanel()

        Spacer(Modifier.height(16.dp))
        ProcessCallNotificationsPanel()

        Spacer(Modifier.height(16.dp))
        CalendarPreAnnouncePanel()

        Spacer(Modifier.height(16.dp))
        WatchSyncPanel()

        Spacer(Modifier.height(16.dp))
        AutoReplyPrefixPanel()

        Spacer(Modifier.height(16.dp))
        UserAliasesPanel()

        Spacer(Modifier.height(16.dp))
        UserNamePanel()

        Spacer(Modifier.height(16.dp))
        AutoLockPanel()

        Spacer(Modifier.height(16.dp))
        Panel("region") {
            Region.entries.forEach { r ->
                RadioRow(
                    label = r.label,
                    selected = state.region == r,
                    onSelect = { scope.launch { vm.setRegion(r) } },
                )
            }
            Text(
                text = "${Glyph.AccentBar} keys from minimax.io and minimaxi.com are not interchangeable.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Panel("api key") {
            var input by remember { mutableStateOf(state.apiKey ?: "") }
            LaunchedEffect(state.apiKey) { input = state.apiKey.orEmpty() }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("eyJ…", color = MytharaColors.FgDim) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { scope.launch { vm.saveAndValidate(input) } },
                    enabled = !state.validating && input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Charple,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text(if (state.validating) "${Glyph.Ellipsis} validating" else "${Glyph.Check} validate")
                }
                state.validation?.let { v ->
                    val color = if (v.ok) MytharaColors.Julep else MytharaColors.Sriracha
                    Text(
                        text = "${if (v.ok) Glyph.Check else Glyph.Cross} ${v.message}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Panel("gemini vision key (optional)") {
            var geminiInput by remember { mutableStateOf(state.geminiKey ?: "") }
            LaunchedEffect(state.geminiKey) { geminiInput = state.geminiKey.orEmpty() }
            OutlinedTextField(
                value = geminiInput,
                onValueChange = { geminiInput = it },
                singleLine = true,
                placeholder = { Text("AIza…", color = MytharaColors.FgDim) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { scope.launch { vm.saveAndValidateGemini(geminiInput) } },
                        enabled = !state.geminiValidating && geminiInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple,
                            contentColor = MytharaColors.Fg,
                        ),
                    ) {
                        Text(if (state.geminiValidating) "${Glyph.Ellipsis} validating" else "${Glyph.Check} validate")
                    }
                    if (!state.geminiKey.isNullOrBlank()) {
                        Spacer(Modifier.padding(start = 8.dp))
                        TextButton(onClick = { scope.launch { vm.clearGeminiKey() } }) {
                            Text("${Glyph.Cross} clear", color = MytharaColors.FgMute)
                        }
                    }
                }
                state.geminiValidation?.let { v ->
                    val color = if (v.ok) MytharaColors.Julep else MytharaColors.Sriracha
                    Text(
                        text = "${if (v.ok) Glyph.Check else Glyph.Cross} ${v.message}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(
                text = "${Glyph.AccentBar} when set, take_photo sends captured images to gemini-2.5-flash (dedicated vision) instead of MiniMax-VL-01. Get a free-tier key at aistudio.google.com/app/apikey. Encrypted at rest with the same Keystore-backed AEAD as your MiniMax key.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
                modifier = Modifier.padding(top = 6.dp),
            )

            // Routing preference toggle — lets a user with a Gemini
            // key flip the cascade so the cloud Gemini call runs
            // FIRST and the on-device Gemma is the fallback. Default
            // (off) prioritises on-device for privacy + zero cost.
            Spacer(Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.preferCloudVision.let { on ->
                        if (on) Glyph.CircleFilled else Glyph.CircleOutline
                    },
                    color = if (state.preferCloudVision) MytharaColors.Charple else MytharaColors.FgDim,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable {
                        scope.launch { vm.setPreferCloudVision(!state.preferCloudVision) }
                    },
                )
                Spacer(Modifier.padding(end = 6.dp))
                Text(
                    text = "prefer cloud Gemini over on-device Gemma",
                    color = MytharaColors.Fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "${Glyph.AccentBar} off (default) = on-device Gemma 4 E2B runs first; private + free, cloud only as fallback. on = cloud Gemini runs first when a key is configured; higher caption accuracy at the cost of an API call per photo. MiniMax-VL is the final fallback in both modes.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Panel("elevenlabs voice (optional)") {
            var elInput by remember { mutableStateOf(state.elevenLabsKey ?: "") }
            LaunchedEffect(state.elevenLabsKey) { elInput = state.elevenLabsKey.orEmpty() }

            OutlinedTextField(
                value = elInput,
                onValueChange = { elInput = it },
                singleLine = true,
                placeholder = { Text("sk_…", color = MytharaColors.FgDim) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MytharaColors.Fg,
                    unfocusedTextColor = MytharaColors.Fg,
                    focusedBorderColor = MytharaColors.Charple,
                    unfocusedBorderColor = MytharaColors.SurfaceHigh,
                    cursorColor = MytharaColors.Charple,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            // Voice dropdown — populated from /v1/voices after validate.
            // Until the list arrives the button is dimmed and shows the
            // current voice id verbatim; once we have the list we render
            // names with category hints like 'Rachel · premade'.
            var voiceMenuOpen by remember { mutableStateOf(false) }
            val voices = state.elevenLabsVoices
            val selectedVoice = voices.firstOrNull { it.voiceId == state.elevenLabsVoiceId }
            val selectedLabel = when {
                selectedVoice != null -> selectedVoice.name
                state.elevenLabsVoicesLoading -> "${Glyph.Ellipsis} loading voices"
                voices.isEmpty() -> "voice · ${state.elevenLabsVoiceId.take(8)}…"
                else -> "select a voice"
            }
            Box {
                Button(
                    onClick = { if (voices.isNotEmpty()) voiceMenuOpen = true },
                    enabled = voices.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Surface,
                        contentColor = MytharaColors.Fg,
                        disabledContainerColor = MytharaColors.Surface,
                        disabledContentColor = MytharaColors.FgDim,
                    ),
                ) {
                    Text("$selectedLabel  ${Glyph.Arrow}")
                }
                DropdownMenu(expanded = voiceMenuOpen, onDismissRequest = { voiceMenuOpen = false }) {
                    voices.forEach { v ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${v.name} · ${v.category ?: "voice"}",
                                    color = MytharaColors.Fg,
                                )
                            },
                            onClick = {
                                scope.launch { vm.setElevenLabsVoiceId(v.voiceId) }
                                voiceMenuOpen = false
                            },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            scope.launch {
                                vm.saveAndValidateElevenLabs(elInput)
                            }
                        },
                        enabled = !state.elevenLabsValidating && elInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Charple,
                            contentColor = MytharaColors.Fg,
                        ),
                    ) {
                        Text(if (state.elevenLabsValidating) "${Glyph.Ellipsis} validating" else "${Glyph.Check} save & validate")
                    }
                    if (!state.elevenLabsKey.isNullOrBlank()) {
                        Spacer(Modifier.padding(start = 8.dp))
                        TextButton(onClick = { scope.launch { vm.clearElevenLabsKey() } }) {
                            Text("${Glyph.Cross} clear", color = MytharaColors.FgMute)
                        }
                    }
                }
                state.elevenLabsValidation?.let { v ->
                    val color = if (v.ok) MytharaColors.Julep else MytharaColors.Sriracha
                    Text(
                        text = "${if (v.ok) Glyph.Check else Glyph.Cross} ${v.message}",
                        color = color,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Toggle row — only meaningful when a key is set; otherwise
            // dim it so the user knows what step they're missing.
            val toggleEnabled = !state.elevenLabsKey.isNullOrBlank()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        if (toggleEnabled) scope.launch { vm.setUseElevenLabs(!state.useElevenLabs) }
                    },
                    enabled = toggleEnabled,
                ) {
                    Text(
                        text = if (state.useElevenLabs) Glyph.CircleFilled else Glyph.CircleOutline,
                        color = when {
                            !toggleEnabled -> MytharaColors.FgDim
                            state.useElevenLabs -> MytharaColors.Charple
                            else -> MytharaColors.FgMute
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.padding(end = 6.dp))
                    Text(
                        text = "use elevenlabs for Mythara's voice",
                        color = if (toggleEnabled) MytharaColors.Fg else MytharaColors.FgDim,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(
                text = "${Glyph.AccentBar} when on, Mythara speaks via the ElevenLabs hosted voice (model eleven_turbo_v2_5) instead of the on-device Android TTS. Get a key at elevenlabs.io/app/settings/api-keys with at least 'Voices — Read' + 'Text to Speech' permissions. The voice dropdown is loaded from /v1/voices after a successful save & validate — pick any voice in your library. Falls back to Android TTS automatically if a call fails (network down, over quota).",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Panel("on-device voice (supertonic-2)") {
            val sState by vm.supertonicState.collectAsState()
            val sProgress by vm.supertonicProgress.collectAsState()
            Text(
                text = when (sState) {
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Installed ->
                        "${Glyph.Check} installed — used when ElevenLabs is off / not configured"
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Downloading ->
                        "${Glyph.Ellipsis} downloading ${(sProgress * 100).toInt()}%"
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Failed ->
                        "${Glyph.Cross} download failed — check connection and try again"
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Idle ->
                        "${Glyph.CircleOutline} not installed"
                },
                color = when (sState) {
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Installed -> MytharaColors.Julep
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Downloading -> MytharaColors.Citron
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Failed -> MytharaColors.Sriracha
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Idle -> MytharaColors.FgDim
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(top = 6.dp))
            // Voice picker — only rendered when the model is
            // installed, since the voice .json files come with the
            // initial download. Defaults to whatever's stored in
            // SettingsStore (M1 on first install). Tts.speak()
            // reads from the same store on every call so changes
            // take effect on the next utterance — no restart.
            if (sState == com.mythara.mic.supertonic.SupertonicModelStore.State.Installed) {
                Spacer(Modifier.padding(top = 6.dp))
                var voiceMenuOpen by remember { mutableStateOf(false) }
                val voices = remember { vm.availableSupertonicVoices() }
                Box {
                    Button(
                        onClick = { voiceMenuOpen = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MytharaColors.Surface,
                            contentColor = MytharaColors.Fg,
                        ),
                    ) {
                        Text("voice · ${labelForVoice(state.supertonicVoice)}  ${Glyph.Arrow}")
                    }
                    DropdownMenu(
                        expanded = voiceMenuOpen,
                        onDismissRequest = { voiceMenuOpen = false },
                    ) {
                        voices.forEach { id ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = labelForVoice(id) +
                                            if (id == state.supertonicVoice) "  ${Glyph.Check}" else "",
                                        color = if (id == state.supertonicVoice)
                                            MytharaColors.Charple else MytharaColors.Fg,
                                    )
                                },
                                onClick = {
                                    scope.launch { vm.setSupertonicVoice(id) }
                                    voiceMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.padding(top = 6.dp))
            Row {
                when (sState) {
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Installed -> {
                        Button(
                            onClick = { vm.testSupertonicVoice() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Fg,
                            ),
                        ) {
                            Text("${Glyph.DiamondFilled} test voice")
                        }
                        Spacer(Modifier.padding(end = 6.dp))
                        TextButton(onClick = { vm.removeSupertonicVoice() }) {
                            Text("${Glyph.Cross} remove (~270 MB)", color = MytharaColors.Sriracha)
                        }
                    }
                    com.mythara.mic.supertonic.SupertonicModelStore.State.Downloading -> {
                        Text(
                            "${Glyph.Ellipsis} please wait — large files",
                            color = MytharaColors.FgMute,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> {
                        Button(
                            onClick = { vm.installSupertonicVoice() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MytharaColors.Charple,
                                contentColor = MytharaColors.Fg,
                            ),
                        ) {
                            Text("${Glyph.DiamondFilled} install voice (~270 MB)")
                        }
                    }
                }
            }
            val testResult by vm.testVoiceResult.collectAsState()
            testResult?.let { res ->
                Text(
                    text = res.message,
                    color = if (res.ok) MytharaColors.Julep else MytharaColors.Sriracha,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                text = "${Glyph.AccentBar} Supertonic-2 is a 66M-param multilingual on-device TTS (en/ko/es/pt/fr). When installed, it's used in place of the bundled Android TTS whenever ElevenLabs isn't configured. Better prosody, fully offline, ~270 MB one-time download. Falls back to Android TTS if synthesis fails.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Panel("model") {
            var open by remember { mutableStateOf(false) }
            Box {
                Button(
                    onClick = { open = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MytharaColors.Surface,
                        contentColor = MytharaColors.Fg,
                    ),
                ) {
                    Text("${state.model}  ${Glyph.Arrow}")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    state.supportedModels.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m, color = MytharaColors.Fg) },
                            onClick = {
                                scope.launch { vm.setModel(m) }
                                open = false
                            },
                        )
                    }
                }
            }
            Text(
                text = "${Glyph.AccentBar} default is the cheapest function-calling model. switch up only when you need more.",
                style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgDim),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        QuickTalkPanel()

        Spacer(Modifier.height(16.dp))
        PersonaPanel()

        Spacer(Modifier.height(16.dp))
        AssistantDefaultPanel()

        Spacer(Modifier.height(16.dp))
        AccessibilityPanel()

        Spacer(Modifier.height(16.dp))
        MessageImportPanel()

        Spacer(Modifier.height(16.dp))
        AuditLogPanel(onOpenFull = onOpenAudit)

        Spacer(Modifier.height(16.dp))
        RestrictedAppsPanel()

        Spacer(Modifier.height(16.dp))
        ConfirmationPanel()

        Spacer(Modifier.height(16.dp))
        AllowlistPanel()

        Spacer(Modifier.height(16.dp))
        NotificationAccessPanel()

        Spacer(Modifier.height(16.dp))
        SkillsPanel()

        Spacer(Modifier.height(16.dp))
        McpServersPanel()

        Spacer(Modifier.height(16.dp))
        RerunOnboardingPanel()

        Spacer(Modifier.height(16.dp))
        RecallPanel()

        Spacer(Modifier.height(16.dp))
        MemorySyncPanel()

        Spacer(Modifier.height(16.dp))
        WakeListenerPanel()

        // Capability Expansion v2 — SSH bridge to the Android 15
        // Linux Terminal Debian VM. Configured here so the agent's
        // `linux_vm` tool can reach it.
        Spacer(Modifier.height(16.dp))
        LinuxBridgePanel()

        // Capability Expansion v2 — Shizuku state + setup prompt.
        // Required by the cosmetic-tweak tools (`apply_cosmetic` /
        // `list_cosmetic_options`); degrades gracefully when not
        // installed.
        Spacer(Modifier.height(16.dp))
        ShizukuPanel()

        // Capability Expansion v3 — Meta Display Glasses pairing +
        // session control. Surfaces current DAT connection state,
        // registration button, start/stop FGS for the live session.
        Spacer(Modifier.height(16.dp))
        GlassesPanel()

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onOpenPeople) {
                Text(
                    "${Glyph.DiamondFilled} people & analytics  ${Glyph.Arrow}",
                    color = MytharaColors.Charple,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onOpenAbout) {
                Text("${Glyph.DiamondOutline} about Mythara  ${Glyph.Arrow}", color = MytharaColors.FgMute)
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

/** Human-readable label for a Supertonic voice id. Pure UI; the
 *  engine + storage layer use the raw id (F1/M1/etc.). */
private fun labelForVoice(id: String): String = when (id) {
    "F1" -> "Female 1"
    "F2" -> "Female 2"
    "F3" -> "Female 3"
    "F4" -> "Female 4"
    "F5" -> "Female 5"
    "M1" -> "Male 1"
    "M2" -> "Male 2"
    "M3" -> "Male 3"
    "M4" -> "Male 4"
    "M5" -> "Male 5"
    else -> id
}

@Composable
private fun Panel(title: String, body: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} $title",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))
        body()
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSelect) {
            Text(
                text = "${if (selected) Glyph.CircleFilled else Glyph.CircleOutline}  $label",
                color = if (selected) MytharaColors.Charple else MytharaColors.Fg,
            )
        }
    }
}

