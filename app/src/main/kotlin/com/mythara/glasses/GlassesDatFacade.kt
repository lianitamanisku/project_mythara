package com.mythara.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.removeStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.DeviceSessionError
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.removeDisplay
import com.meta.wearable.dat.display.types.DisplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Thin façade over the Meta DAT SDK so the rest of Mythara doesn't
 * directly import `com.meta.wearable.dat.*`.
 *
 * ## Why this exists
 *
 * Meta DAT lives on GitHub Packages and needs a personal-access token
 * with `read:packages` scope to pull (`github_token=...` in
 * `local.properties`, gitignored — see `settings.gradle.kts`).
 *
 * Wrapping the SDK behind this single object keeps DAT-specific types
 * out of every other v3 file: [GlassesGestureRouter], [GlassesScreenStore],
 * [GlassesConnectionService], the photo ingester, the face pipeline,
 * the GlassesMemoryScreen UI — none of them import DAT classes.
 *
 * ## Lifecycle expected by [GlassesConnectionService]
 *
 *  1. [initializeIfAvailable] on `Application.onCreate` (or lazily).
 *  2. [startRegistration] from a user-driven Settings action when the
 *     user wants to pair Mythara with Meta AI.
 *  3. [startSession] after [connectionState] reports `Paired`. The FGS
 *     calls this as soon as it starts.
 *  4. [render] for every [GlassesScreenStore] transition.
 *  5. [capturePhoto] from [photo.GlassesPhotoCapture].
 *  6. [stopSession] on FGS teardown.
 *
 * ## Display button callbacks
 *
 * The DAT display DSL takes per-button `onClick` lambdas. [GlassesScreenRenderer]
 * wires each one to [publishEvent] so the rest of the app can subscribe
 * to [events] without touching DAT.
 *
 * ## DatResult ergonomics
 *
 * The SDK's `DatResult<T, E : DatError>` exposes `fold(onSuccess, onFailure(err, throwable))`
 * and `onFailure { err, _ -> ... }` — error-typed two-arg lambdas. The
 * single-arg `getOrElse { throwable -> ... }` form gives back a Throwable,
 * NOT the typed DatError. We use `fold` whenever we want the typed error
 * (so we can read `.description`).
 */
object GlassesDatFacade {

    private const val TAG = "Mythara/GlassesDAT"

    /**
     * Master kill-switch for the glasses display path.
     *
     * Flipped to `false` 2026-05-16 — every code-side gate is green
     * (registration REGISTERED, both DAT permissions Granted, device
     * compat=COMPATIBLE, retry path clean) but every session attempt
     * dies at the on-glasses DWA-version handshake:
     *
     *   DAT:CORE:SessionChannel: DAM session started but DWA did not
     *   report its version
     *   → START_ERROR_DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED
     *
     * which translates to "Mythara's on-glasses DWA bundle was never
     * deployed to the glasses hardware". That's a deployment-pipeline
     * gap at Meta's Wearables Developer Center, not something fixable
     * from inside this APK — the DAT v0.7 program's DWA-publish flow
     * for third-party Display Glasses apps appears to be gated /
     * incomplete for our developer account right now.
     *
     * While disabled:
     *   - Application.onCreate still calls [initializeIfAvailable] so
     *     the SDK boots cleanly (no startup regressions).
     *   - [startSession] short-circuits with a parked-message so the
     *     panel never hangs the user on a "STARTING -> STOPPED"
     *     loop.
     *   - The panel shows a single "parked" banner instead of the
     *     pairing / permission / session ladder.
     *
     * To re-enable: flip to `true`, ensure the Wearables Developer
     * Center project has a deployable DWA bundle for `com.mythara.debug`,
     * then run the panel flow again.
     */
    const val DISPLAY_PATH_ENABLED = false

    /** Scope for the registration / session / display / stream state
     *  collectors. Cancelled-and-recreated only on full process death;
     *  the [stopSession] path tears down individual collectors with
     *  [Job.cancel] rather than killing the scope. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var initializedOnce = false
    private var session: DeviceSession? = null
    private var stream: Stream? = null
    private var display: Display? = null

    private var registrationJob: Job? = null
    private var registrationErrorJob: Job? = null
    private var sessionStateJob: Job? = null
    private var sessionErrorJob: Job? = null
    private var streamStateJob: Job? = null
    private var displayStateJob: Job? = null

    private val _connectionState = MutableStateFlow(GlassesConnectionState.NotInitialized)
    val connectionState: StateFlow<GlassesConnectionState> = _connectionState.asStateFlow()

    /** Latest registration-side error description (or null if none).
     *  The panel surfaces this verbatim so the user sees the SDK's
     *  actual complaint — e.g. "Meta AI app not installed",
     *  "INCOMPATIBLE_SDK_LEVEL". */
    private val _lastRegistrationError = MutableStateFlow<String?>(null)
    val lastRegistrationError: StateFlow<String?> = _lastRegistrationError.asStateFlow()

    /** Latest session-creation error from a [startSession] attempt.
     *  Mirrors the typed [DeviceSessionError] description so the panel
     *  can show "no eligible device found", "DAT app on glasses needs
     *  update", etc — without that the start-session button silently
     *  fails. */
    private val _lastSessionError = MutableStateFlow<String?>(null)
    val lastSessionError: StateFlow<String?> = _lastSessionError.asStateFlow()

    /** True when the most recent session error was specifically
     *  DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED. The panel uses this to
     *  decide whether to show an "update glasses" action button that
     *  calls [openDATGlassesAppUpdate]. */
    private val _glassesAppUpdateRequired = MutableStateFlow(false)
    val glassesAppUpdateRequired: StateFlow<Boolean> = _glassesAppUpdateRequired.asStateFlow()

    /** Per-device DAT permission status for CAMERA + MICROPHONE. The SDK
     *  exposes these separately from Android runtime permissions —
     *  granting Mythara android.permission.CAMERA does NOT grant DAT
     *  camera access on the glasses; the user has to confirm via the
     *  Stella companion app for each. When EITHER is Denied the device
     *  often kills the session during the initial handshake with the
     *  generic SESSION_ENDED_BY_DEVICE error (no specific second-error
     *  case — the device just hangs up). The panel gates "start session"
     *  on both being Granted. */
    enum class DatPermission { Unknown, Granted, Denied }
    private val _cameraPermission = MutableStateFlow(DatPermission.Unknown)
    val cameraPermission: StateFlow<DatPermission> = _cameraPermission.asStateFlow()
    private val _microphonePermission = MutableStateFlow(DatPermission.Unknown)
    val microphonePermission: StateFlow<DatPermission> = _microphonePermission.asStateFlow()

    /** Live snapshot of every device the SDK currently sees, with the
     *  full per-device metadata (name, firmware, link state, display-
     *  capability, compatibility). Used by the panel as the canonical
     *  "what does the SDK actually see right now" diagnostic. */
    data class GlassesDeviceInfo(
        val id: String,
        val name: String,
        val firmware: String,
        val linkState: String,
        val displayCapable: Boolean,
        val compatibility: String,
    )
    private val _discoveredDevices = MutableStateFlow<List<GlassesDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<GlassesDeviceInfo>> = _discoveredDevices.asStateFlow()

    /** True when at least one discovered device reports
     *  [DeviceCompatibility.DEVICE_UPDATE_REQUIRED]. The panel uses
     *  this to surface an "update glasses firmware" button that calls
     *  [openFirmwareUpdate]. Distinct from the DAT app update (which
     *  is the user-space runtime on the glasses); firmware is the
     *  underlying device OS / hardware bridge. */
    private val _firmwareUpdateRequired = MutableStateFlow(false)
    val firmwareUpdateRequired: StateFlow<Boolean> = _firmwareUpdateRequired.asStateFlow()

    private var devicesJob: Job? = null
    private var perDeviceMetadataJobs = mutableMapOf<DeviceIdentifier, Job>()

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<GlassesEvent> = _events.asSharedFlow()

    fun isAvailable(): Boolean = initializedOnce

    /** Force a re-initialize after a runtime permission grant
     *  (BLUETOOTH_CONNECT) or after the user installs/launches the
     *  Meta companion app. The DAT SDK builds its provider observers
     *  during [initializeIfAvailable]; if BT_CONNECT was missing or
     *  the companion app wasn't reachable at init time, the
     *  registration-state collector never wires up correctly and
     *  stays at UNAVAILABLE even after the underlying condition
     *  flips. We call `Wearables.reset()` first so the SDK drops its
     *  own internal "already initialized" guard — without this, the
     *  subsequent `Wearables.initialize` fails with "Wearables has
     *  been already initialized" and the SDK keeps using the broken
     *  observers from the first attempt. */
    fun reinitialize(context: Context) {
        runCatching { Wearables.reset() }
            .onFailure { Log.w(TAG, "Wearables.reset threw: ${it.message}") }
        initializedOnce = false
        registrationJob?.cancel(); registrationJob = null
        registrationErrorJob?.cancel(); registrationErrorJob = null
        _lastRegistrationError.value = null
        _connectionState.value = GlassesConnectionState.NotInitialized
        initializeIfAvailable(context)
    }

    /** Idempotent SDK boot. Safe to call from Application.onCreate even
     *  before the user has installed Meta AI — registrationState will
     *  remain `AVAILABLE` until they kick off [startRegistration]. */
    fun initializeIfAvailable(context: Context) {
        if (initializedOnce) return
        initializedOnce = true
        runCatching {
            Wearables.initialize(context.applicationContext).onFailure { error, _ ->
                Log.w(TAG, "Wearables.initialize failed: ${error.description}")
                _connectionState.value = GlassesConnectionState.Error
            }
        }.onFailure {
            Log.w(TAG, "Wearables.initialize threw: ${it.message}")
            _connectionState.value = GlassesConnectionState.Error
            return
        }
        _connectionState.value = GlassesConnectionState.Initialized
        observeRegistration()
    }

    /** Surface registration UI through the Meta AI app. Called from a
     *  Settings panel button — the user comes back into Mythara via the
     *  callback URI scheme declared in AndroidManifest. */
    fun startRegistration(activity: Activity) {
        runCatching { Wearables.startRegistration(activity) }
            .onFailure { Log.w(TAG, "startRegistration threw: ${it.message}") }
    }

    fun startUnregistration(activity: Activity) {
        runCatching { Wearables.startUnregistration(activity) }
            .onFailure { Log.w(TAG, "startUnregistration threw: ${it.message}") }
    }

    /** Launch the in-Stella flow that updates the DAT app running on
     *  the glasses themselves. Surface this when [glassesAppUpdateRequired]
     *  flips true — the SDK refuses to open a session against an outdated
     *  on-glasses DAT app and reports DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED. */
    suspend fun openDATGlassesAppUpdate(activity: Activity) {
        runCatching { Wearables.openDATGlassesAppUpdate(activity) }
            .onFailure { Log.w(TAG, "openDATGlassesAppUpdate threw: ${it.message}") }
        // Optimistically clear the flag — if it's still required after
        // the user returns, the next startSession attempt will re-flip it.
        _glassesAppUpdateRequired.value = false
    }

    /** Launch Stella's firmware-update flow for the glasses hardware
     *  itself. Distinct from openDATGlassesAppUpdate — that updates
     *  the DAT user-space runtime, this updates the underlying device
     *  OS. Used when any device reports
     *  [DeviceCompatibility.DEVICE_UPDATE_REQUIRED]. */
    suspend fun openFirmwareUpdate(activity: Activity) {
        runCatching { Wearables.openFirmwareUpdate(activity) }
            .onFailure { Log.w(TAG, "openFirmwareUpdate threw: ${it.message}") }
    }

    /** Re-query both DAT-side permissions and mirror into the state
     *  flows. Should be called after a Stella permission prompt
     *  completes, and at facade init. */
    suspend fun refreshDatPermissions() {
        refreshOne(Permission.CAMERA, _cameraPermission)
        refreshOne(Permission.MICROPHONE, _microphonePermission)
    }

    /** Back-compat alias — older callers asked for just camera. */
    suspend fun refreshCameraPermission() = refreshDatPermissions()

    private suspend fun refreshOne(
        perm: Permission,
        out: MutableStateFlow<DatPermission>,
    ) {
        Wearables.checkPermissionStatus(perm).fold(
            onSuccess = { status ->
                out.value = when (status) {
                    PermissionStatus.Granted -> DatPermission.Granted
                    PermissionStatus.Denied -> DatPermission.Denied
                    else -> DatPermission.Unknown
                }
                Log.d(TAG, "DAT ${perm.name} permission -> ${out.value}")
            },
            onFailure = { err, _ ->
                Log.w(TAG, "checkPermissionStatus(${perm.name}) failed: ${err.description}")
                out.value = DatPermission.Unknown
            },
        )
    }

    /** Build a session against a display-capable device, then attach the
     *  camera stream + display capability. Returns true once both
     *  capabilities have reported their STARTED state. */
    suspend fun startSession(): Boolean {
        if (!DISPLAY_PATH_ENABLED) {
            val msg = "glasses display path is parked (see DISPLAY_PATH_ENABLED docstring); " +
                "no session will be opened until Meta's DWA-publish gap is closed"
            Log.i(TAG, msg)
            _lastSessionError.value = "PARKED: $msg"
            return false
        }
        if (!initializedOnce) {
            Log.w(TAG, "startSession before initializeIfAvailable — no-op")
            return false
        }
        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            Log.i(TAG, "startSession skipped — registration state is ${Wearables.registrationState.value}")
            return false
        }
        // Don't trust a non-null `session` reference alone — when the
        // device kills a session asynchronously (via session.errors
        // emitting then state -> STOPPED), our refs can stay set even
        // though the SDK considers the session dead. Verify against
        // the live state.value; treat STOPPED/CLOSED as "not active"
        // and proceed to open a fresh session.
        val activeState = session?.state?.value
        if (session != null && activeState != null &&
            activeState != DeviceSessionState.STOPPED &&
            activeState != DeviceSessionState.STOPPING
        ) {
            Log.d(TAG, "startSession called but session already active (state=$activeState)")
            return true
        }
        if (session != null) {
            Log.d(TAG, "startSession — clearing stale dead session ref (state=$activeState)")
            cleanupSessionRefs()
        }

        _lastSessionError.value = null

        // Confirm both DAT-side permissions (camera + microphone) before
        // opening a session. The session can reach `STARTING` even
        // without them, but the device often kills it during the
        // handshake with the generic SESSION_ENDED_BY_DEVICE error and
        // no specific second-error case is emitted.
        refreshDatPermissions()
        if (_cameraPermission.value != DatPermission.Granted ||
            _microphonePermission.value != DatPermission.Granted
        ) {
            val msg = "DAT permissions — camera=${_cameraPermission.value}, " +
                "mic=${_microphonePermission.value}; both must be Granted via Stella"
            Log.w(TAG, msg)
            _lastSessionError.value = "PERMISSION_DENIED: $msg"
            return false
        }

        // Wait for a CONNECTED display-capable device to actually exist
        // in the SDK's view before invoking createSession. AutoDeviceSelector
        // fails fast with NO_ELIGIBLE_DEVICE if `Wearables.devices` is empty
        // OR if metadata for the known devices hasn't loaded yet — both
        // common in the moments right after registration completes. The
        // wait-loop also gives us a chance to log what the SDK actually
        // sees so future "no eligible" failures point at a real cause.
        val targetId: DeviceIdentifier? = withTimeoutOrNull(8_000L) {
            var found: DeviceIdentifier? = null
            while (found == null) {
                val ids = Wearables.devices.value
                Log.d(TAG, "createSession: ${ids.size} discovered device(s) so far")
                for (id in ids) {
                    val metadataFlow = Wearables.devicesMetadata[id]
                    val device = metadataFlow?.value
                    if (device != null) {
                        Log.d(
                            TAG,
                            "  device: name=${device.name} link=${device.linkState} " +
                                "displayCapable=${device.isDisplayCapable()} compat=${device.compatibility}",
                        )
                        if (device.linkState == LinkState.CONNECTED && device.isDisplayCapable()) {
                            found = id
                            break
                        }
                    } else {
                        Log.d(TAG, "  device: $id — metadata not loaded yet")
                    }
                }
                if (found == null) kotlinx.coroutines.delay(500L)
            }
            found
        }
        if (targetId == null) {
            val seen = Wearables.devices.value.size
            val msg = "no display-capable CONNECTED device after 8s ($seen device(s) discovered)"
            Log.w(TAG, "createSession: $msg")
            _lastSessionError.value = "NO_ELIGIBLE_DEVICE: $msg"
            return false
        }

        var newSession: DeviceSession? = null
        Wearables.createSession(SpecificDeviceSelector(targetId)).fold(
            onSuccess = { newSession = it },
            onFailure = { err, _ ->
                Log.w(TAG, "createSession failed: ${err.name}: ${err.description}")
                _lastSessionError.value = "${err.name}: ${err.description}"
            },
        )
        val s = newSession ?: return false
        session = s

        // Observe session state + errors. Display + stream are added
        // only after we see DeviceSessionState.STARTED.
        sessionErrorJob = scope.launch {
            s.errors.collect { err ->
                Log.w(TAG, "session.error: ${err.name}: ${err.description}")
                _lastSessionError.value = "${err.name}: ${err.description}"
                if (err == DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED) {
                    _glassesAppUpdateRequired.value = true
                }
            }
        }
        sessionStateJob = scope.launch {
            s.state.collect { state ->
                Log.d(TAG, "session.state -> $state")
                when (state) {
                    DeviceSessionState.STARTED -> {
                        if (stream == null) attachStream(s)
                        if (display == null) attachDisplay(s)
                    }
                    DeviceSessionState.STOPPED -> {
                        _connectionState.value = GlassesConnectionState.Disconnected
                        // Without this the session/stream/display refs
                        // stay set after the device kills the session,
                        // so the next startSession tap short-circuits
                        // with "session already active" — even though
                        // it's dead. Cleaning here is idempotent.
                        cleanupSessionRefs()
                        return@collect
                    }
                    else -> Unit
                }
            }
        }

        s.start()
        return true
    }

    private fun attachStream(s: DeviceSession) {
        var newStream: Stream? = null
        s.addStream(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24),
        ).fold(
            onSuccess = { newStream = it },
            onFailure = { err, _ -> Log.w(TAG, "addStream failed: ${err.description}") },
        )
        val st = newStream ?: return
        stream = st
        streamStateJob = scope.launch {
            st.state.collect { state -> Log.d(TAG, "stream.state -> $state") }
        }
        st.start().onFailure { err, _ ->
            Log.w(TAG, "stream.start failed: ${err.description}")
        }
    }

    private fun attachDisplay(s: DeviceSession) {
        s.addDisplay().fold(
            onSuccess = { newDisplay ->
                display = newDisplay
                displayStateJob = scope.launch {
                    newDisplay.state.collect { st ->
                        Log.d(TAG, "display.state -> $st")
                        if (st == DisplayState.STARTED) {
                            _connectionState.value = GlassesConnectionState.SessionActive
                            // Push a Root render so the user sees something
                            // the moment the display comes alive.
                            runCatching { render(GlassesScreen.Root) }
                        }
                    }
                }
            },
            onFailure = { err, _ ->
                Log.w(TAG, "addDisplay failed: ${err.description}")
            },
        )
    }

    fun stopSession() {
        runCatching { stream?.stop() }
        runCatching { session?.removeStream() }
        runCatching { session?.removeDisplay() }
        runCatching { session?.stop() }
        cleanupSessionRefs()
        if (Wearables.registrationState.value == RegistrationState.REGISTERED) {
            _connectionState.value = GlassesConnectionState.Paired
        } else {
            _connectionState.value = GlassesConnectionState.Disconnected
        }
    }

    /** Null out cached session/stream/display refs + cancel all the
     *  per-session collector jobs. Used both by [stopSession] (full
     *  shutdown) and by the session.state collector when the SDK
     *  reports STOPPED async (device killed it). Idempotent. */
    private fun cleanupSessionRefs() {
        stream = null
        display = null
        session = null
        streamStateJob?.cancel(); streamStateJob = null
        displayStateJob?.cancel(); displayStateJob = null
        sessionStateJob?.cancel(); sessionStateJob = null
        sessionErrorJob?.cancel(); sessionErrorJob = null
    }

    /** Capture a still from the glasses POV. Returns null when no
     *  stream is attached or the capture fails — caller (the photo
     *  ingester) logs and shows a [GlassesScreen.Error]. */
    suspend fun capturePhoto(): Bitmap? {
        val s = stream ?: run {
            Log.w(TAG, "capturePhoto with no stream attached")
            return null
        }
        if (s.state.value != StreamState.STREAMING && s.state.value != StreamState.STARTED) {
            Log.w(TAG, "capturePhoto while stream is ${s.state.value}")
        }
        var bitmap: Bitmap? = null
        s.capturePhoto().fold(
            onSuccess = { photoData ->
                bitmap = when (photoData) {
                    is PhotoData.Bitmap -> photoData.bitmap
                    is PhotoData.HEIC -> decodeByteBuffer(photoData.data)
                    else -> {
                        Log.w(TAG, "unknown PhotoData subtype ${photoData::class.simpleName}")
                        null
                    }
                }
            },
            onFailure = { err, _ ->
                Log.w(TAG, "capturePhoto failed: ${err.description}")
            },
        )
        return bitmap
    }

    private fun decodeByteBuffer(buf: ByteBuffer): Bitmap? {
        return runCatching {
            val bytes = ByteArray(buf.remaining())
            buf.duplicate().get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** Render a Mythara-level [GlassesScreen] onto the glasses display
     *  via the DAT `sendContent` DSL. Returns true on success. */
    suspend fun render(screen: GlassesScreen): Boolean {
        val d = display ?: run {
            Log.d(TAG, "render(${screen::class.simpleName}) skipped — display not attached")
            return false
        }
        if (d.state.value != DisplayState.STARTED) {
            Log.d(TAG, "render(${screen::class.simpleName}) skipped — display.state=${d.state.value}")
            return false
        }
        var ok = true
        runCatching {
            GlassesScreenRenderer.render(d, screen) { evt -> publishEvent(evt) }
        }.onFailure {
            Log.w(TAG, "renderer threw on $screen: ${it.message}")
            ok = false
        }
        return ok
    }

    /** Fired by [GlassesScreenRenderer] from each DAT-display button's
     *  `onClick` callback. Also reachable from in-app tests for
     *  simulating events without real glasses. */
    fun publishEvent(event: GlassesEvent) {
        _events.tryEmit(event)
    }

    /** Subscribe to `Wearables.devices` + each device's metadata flow
     *  so the panel's diagnostic view + the firmware-update flag stay
     *  live. Idempotent — cancels old subscriptions before re-binding. */
    private fun startObservingDevices() {
        devicesJob?.cancel()
        perDeviceMetadataJobs.values.forEach { it.cancel() }
        perDeviceMetadataJobs.clear()
        // Reset cached snapshot — gets rebuilt from the new subscriptions.
        val cache = mutableMapOf<DeviceIdentifier, GlassesDeviceInfo>()
        devicesJob = scope.launch {
            Wearables.devices.collect { ids ->
                // Stop watching devices that vanished.
                val gone = perDeviceMetadataJobs.keys - ids
                gone.forEach { id ->
                    perDeviceMetadataJobs.remove(id)?.cancel()
                    cache.remove(id)
                }
                // Start watching newly discovered devices.
                ids.forEach { id ->
                    if (id !in perDeviceMetadataJobs) {
                        perDeviceMetadataJobs[id] = scope.launch {
                            Wearables.devicesMetadata[id]?.collect { d: Device ->
                                cache[id] = GlassesDeviceInfo(
                                    id = id.toString(),
                                    name = d.name,
                                    firmware = d.firmwareInfo ?: "unknown",
                                    linkState = d.linkState.name,
                                    displayCapable = d.isDisplayCapable(),
                                    compatibility = d.compatibility.name,
                                )
                                _discoveredDevices.value = cache.values.toList()
                                _firmwareUpdateRequired.value = cache.values.any {
                                    it.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED.name
                                }
                                Log.d(
                                    TAG,
                                    "device metadata: ${d.name} fw=${d.firmwareInfo} " +
                                        "link=${d.linkState} displayCapable=${d.isDisplayCapable()} " +
                                        "compat=${d.compatibility}",
                                )
                            }
                        }
                    }
                }
                _discoveredDevices.value = cache.values.toList()
                if (cache.isEmpty()) _firmwareUpdateRequired.value = false
            }
        }
    }

    private fun observeRegistration() {
        registrationJob?.cancel()
        registrationJob = scope.launch {
            Wearables.registrationState.collect { state ->
                Log.d(TAG, "registrationState -> $state (devMode=${runCatching { Wearables.isDevMode }.getOrNull()})")
                _connectionState.value = when (state) {
                    RegistrationState.REGISTERED -> GlassesConnectionState.Paired
                    RegistrationState.AVAILABLE -> GlassesConnectionState.Initialized
                    RegistrationState.UNAVAILABLE -> GlassesConnectionState.NotInitialized
                    RegistrationState.UNREGISTERING -> GlassesConnectionState.Initialized
                    RegistrationState.REGISTERING -> _connectionState.value
                    else -> _connectionState.value
                }
                // Once REGISTERED, do an initial camera-permission probe
                // so the panel can surface the "grant glasses camera"
                // button without waiting for a failed session attempt,
                // and start observing the per-device metadata stream so
                // the panel can show "device says firmware update needed".
                if (state == RegistrationState.REGISTERED) {
                    runCatching { refreshCameraPermission() }
                    startObservingDevices()
                }
            }
        }
        registrationErrorJob?.cancel()
        registrationErrorJob = scope.launch {
            // The SDK funnels every registration-side failure (Meta AI
            // not installed, incompatible version, failed registration,
            // etc.) through this Flow. We mirror the description into
            // [lastRegistrationError] so the panel can show the user
            // exactly WHY pairing isn't working — instead of just a
            // generic NotInitialized.
            Wearables.registrationErrorStream.collect { err ->
                Log.w(TAG, "registrationError -> ${err.name}: ${err.description}")
                _lastRegistrationError.value = "${err.name}: ${err.description}"
            }
        }
    }
}

/** Lifecycle phases the rest of Mythara can render UI from. */
enum class GlassesConnectionState {
    /** DAT not on classpath OR Wearables.initialize hasn't been called. */
    NotInitialized,
    /** SDK initialized; not yet paired with Meta AI. */
    Initialized,
    /** Paired with Meta AI; glasses discoverable but no active session. */
    Paired,
    /** Active DeviceSession + Stream + Display capability all STARTED. */
    SessionActive,
    /** Session ended (user removed glasses, BT dropped, etc.). */
    Disconnected,
    /** Error state — surface description via GlassesPanel. */
    Error,
}
