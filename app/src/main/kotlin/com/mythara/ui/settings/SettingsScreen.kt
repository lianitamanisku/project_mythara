package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(WindowInsets.systemBars.asPaddingValues())
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("${Glyph.LeftArrow} back", color = MytharaColors.FgMute)
            }
            Spacer(Modifier.height(1.dp))
            Text(
                text = "${Glyph.DiamondFilled} settings",
                color = MytharaColors.Charple,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(Modifier.height(20.dp))
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
        AccessibilityPanel()

        Spacer(Modifier.height(16.dp))
        NotificationAccessPanel()

        Spacer(Modifier.height(16.dp))
        RecallPanel()

        Spacer(Modifier.height(16.dp))
        MemorySyncPanel()

        Spacer(Modifier.height(16.dp))
        LumiListenerPanel()

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onOpenAbout) {
                Text("${Glyph.DiamondOutline} about Mythara  ${Glyph.Arrow}", color = MytharaColors.FgMute)
            }
        }

        Spacer(Modifier.height(40.dp))
    }
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

