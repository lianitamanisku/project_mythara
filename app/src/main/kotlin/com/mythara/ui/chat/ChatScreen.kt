package com.mythara.ui.chat

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.JetBrainsMono
import com.mythara.ui.theme.MytharaColors
import com.mythara.ui.theme.MytharaWordmark

/**
 * Main chat surface. Pulls state from [ChatViewModel] and renders the
 * Crush-styled timeline + composer + (when the assistant is streaming)
 * a live bubble that grows token-by-token.
 *
 * Push-to-talk + TTS land in M3; for M2 we use a plain text composer so
 * MiniMax integration is testable end-to-end before adding voice on top.
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit = {},
    vm: ChatViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val insets = WindowInsets.systemBars.asPaddingValues()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MytharaColors.Bg)
            .padding(insets),
    ) {
        ChatHeader(onOpenSettings = onOpenSettings, thinking = ui.thinking)

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            if (ui.items.isEmpty() && ui.streaming.isNullOrEmpty()) {
                EmptyStateHero(thinking = ui.thinking)
            } else {
                Transcript(items = ui.items, streaming = ui.streaming)
            }

            ui.errorBanner?.let { msg ->
                Banner(text = msg, color = MytharaColors.Sriracha, onDismiss = vm::dismissError,
                    align = Alignment.TopCenter)
            }
            if (ui.needsApiKey) {
                Banner(
                    text = "${Glyph.AccentBar} paste your MiniMax API key in Settings to start chatting.",
                    color = MytharaColors.Mustard, onDismiss = vm::dismissMissingKey,
                    align = Alignment.TopCenter,
                )
            }
        }

        Composer(onSubmit = vm::submit, enabled = !ui.thinking)
    }
}

@Composable
private fun ChatHeader(onOpenSettings: () -> Unit, thinking: Boolean) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${if (thinking) Glyph.Ellipsis else Glyph.DiamondFilled} mythara",
            style = MaterialTheme.typography.labelLarge.copy(
                color = MytharaColors.Charple, fontWeight = FontWeight.Bold,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MytharaColors.Surface)
                    .border(1.dp, MytharaColors.SurfaceHigh, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${Glyph.DiamondOutline} Assistive",
                    style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.FgMute),
                )
            }
            Spacer(Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MytharaColors.Surface)
                    .border(1.dp, MytharaColors.SurfaceHigh, CircleShape)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${Glyph.DiamondOutline} settings",
                    style = MaterialTheme.typography.labelMedium.copy(color = MytharaColors.FgMute),
                )
            }
        }
    }
}

@Composable
private fun EmptyStateHero(thinking: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MytharaWordmark(shimmer = thinking || true, fontSize = 44.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "${Glyph.AccentBar} field intelligence in your pocket.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = MytharaColors.FgDim, letterSpacing = 1.sp,
            ),
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = "type a message ${Glyph.Arrow}",
            style = MaterialTheme.typography.bodySmall.copy(color = MytharaColors.FgMute),
        )
    }
}

@Composable
private fun Transcript(items: List<ChatViewModel.ChatItem>, streaming: String?) {
    val listState = rememberLazyListState()
    LaunchedEffect(items.size, streaming) {
        val target = items.size + if (!streaming.isNullOrEmpty()) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is ChatViewModel.ChatItem.UserText -> TextBubble(role = "you", text = item.text, isUser = true)
                is ChatViewModel.ChatItem.AssistantText -> TextBubble(role = "mythara", text = item.text, isUser = false)
                is ChatViewModel.ChatItem.Thought -> ThoughtBubble(item)
                is ChatViewModel.ChatItem.Tool -> ToolCallBubble(item)
            }
        }
        if (!streaming.isNullOrEmpty()) {
            item("streaming") {
                TextBubble(role = "mythara", text = streaming + Glyph.AccentBar, isUser = false)
            }
        }
    }
}

@Composable
private fun TextBubble(role: String, text: String, isUser: Boolean) {
    val bg = if (isUser) MytharaColors.SurfaceMid else MytharaColors.Surface
    val border = if (isUser) MytharaColors.Charple else MytharaColors.SurfaceHigh
    val align = if (isUser) Alignment.End else Alignment.Start
    val accent = if (isUser) MytharaColors.Charple else MytharaColors.Bok

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Text(
            text = "${Glyph.DiamondFilled} $role",
            style = MaterialTheme.typography.labelMedium.copy(color = accent),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(text = text, color = MytharaColors.Fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Composer(onSubmit: (String) -> Unit, enabled: Boolean) {
    var draft by remember { mutableStateOf("") }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Mic button — push-to-talk via SpeechRecognizer. Partials stream into
        // the draft field; final fires submit() so the voice path lands the same
        // turn as text without a separate "send" tap.
        MicButton(
            onPartial = { draft = it },
            onFinal = {
                draft = it
                if (enabled && draft.isNotBlank()) {
                    onSubmit(draft); draft = ""
                }
            },
            onError = { /* surface later via VM event channel */ },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(MytharaColors.Surface)
                .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(22.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = enabled,
                singleLine = false,
                cursorBrush = SolidColor(MytharaColors.Charple),
                textStyle = TextStyle(
                    color = MytharaColors.Fg,
                    fontFamily = JetBrainsMono,
                    fontSize = 14.sp,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                "tap mic or type…",
                                color = MytharaColors.FgDim,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        inner()
                    }
                },
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (enabled && draft.isNotBlank()) MytharaColors.Charple else MytharaColors.Surface)
                .border(1.dp, if (enabled && draft.isNotBlank()) MytharaColors.Charple else MytharaColors.SurfaceHigh, CircleShape)
                .clickable(enabled = enabled && draft.isNotBlank()) {
                    onSubmit(draft)
                    draft = ""
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (enabled) Glyph.Arrow else Glyph.Ellipsis,
                color = if (enabled && draft.isNotBlank()) MytharaColors.Fg else MytharaColors.FgDim,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun Banner(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
    align: Alignment,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, color, RoundedCornerShape(8.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}
