package com.mythara.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.data.OnboardingStore
import com.mythara.ui.canvas.CanvasController
import com.mythara.wake.WakeListenerStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Thin HiltViewModel that exposes app-wide event streams to MytharaRoot.
 * Today only the Mythara wake-query flow lives here; future bus-style
 * signals (push notification taps, intent-extra hooks) can ride along
 * without bloating AuthViewModel.
 *
 * Composables consume via `hiltViewModel<RootViewModel>()`. Hilt scopes
 * this to the Activity, which is exactly the lifetime we want for the
 * single MytharaRoot composition.
 *
 * Also surfaces the first-run onboarding flag so MytharaRoot can pivot
 * to the OnboardingScreen before the AuthGate on a fresh install.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    store: WakeListenerStore,
    onboardingStore: OnboardingStore,
    /**
     * Process-wide [CanvasController] singleton. Exposed here so
     * MytharaRoot can observe `canvasController.navigationRequest`
     * and auto-pivot to the Canvas route whenever the agent's
     * `render_canvas` tool fires with `auto_navigate=true`.
     */
    val canvasController: CanvasController,
) : ViewModel() {
    val wakeQueries: SharedFlow<WakeListenerStore.WakeQuery> = store.wakeQueries

    /**
     * Null while we're still reading the DataStore (one-frame race on
     * cold start); false → show onboarding; true → skip. Composables
     * render a blank Bg surface until this resolves so we don't flash
     * the AuthGate for a moment before flipping to onboarding.
     */
    val onboardingCompleted: StateFlow<Boolean?> = onboardingStore.completedFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
