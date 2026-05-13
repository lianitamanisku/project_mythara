package com.mythara.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import com.mythara.voice.QuickTalkSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuickTalkPanelViewModel @Inject constructor(
    private val store: QuickTalkSettings,
) : ViewModel() {
    val enabled: StateFlow<Boolean> = store.enabledFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { store.setEnabled(value) }
    }
}

/**
 * Settings panel that exposes the "talk-to-Lumi persistent
 * notification" toggle. When on, MytharaApp's cold-start hook posts
 * an ongoing notification with a Talk action that fires the same
 * ACTION_ASSIST path Pixel Buds touch-and-hold uses; tapping
 * anywhere on the notification opens Mythara with the mic listening.
 *
 * Notification IS independent of the foreground service used while
 * agent work is in flight — it persists across idle periods so the
 * user always has a one-tap entry point.
 */
@Composable
fun QuickTalkPanel(vm: QuickTalkPanelViewModel = hiltViewModel()) {
    val enabled by vm.enabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MytharaColors.Surface)
            .border(1.dp, MytharaColors.SurfaceHigh, RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "${Glyph.DiamondOutline} talk to lumi (persistent)",
            style = MaterialTheme.typography.labelLarge.copy(color = MytharaColors.FgMute),
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { vm.setEnabled(!enabled) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (enabled) Glyph.CircleFilled else Glyph.CircleOutline,
                color = if (enabled) MytharaColors.Charple else MytharaColors.FgMute,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                text = "show 'talk to lumi' notification",
                color = MytharaColors.Fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "${Glyph.AccentBar} when on, a persistent notification stays in your status bar — tap it from anywhere and Mythara opens with the mic already listening. Same path as a Pixel Buds long-press, but always one tap away. Off by default to keep your shade clean.",
            color = MytharaColors.FgDim,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
