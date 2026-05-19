package com.mythara.agent.tools

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.mythara.agent.Tool
import com.mythara.agent.ToolResult
import com.mythara.services.TermuxAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `termux_exec` — run a shell command inside Termux's Debian-grade
 * userland via the `com.termux.RUN_COMMAND` intent.
 *
 * Why this exists when `run_shell` already does shell:
 *   - `run_shell` runs inside Mythara's app sandbox — toybox + GNU
 *     subset, no `apt`, no persistent `$HOME`, no `~/.bashrc`, no
 *     real TTY.
 *   - Termux ships a full GNU userland with `pkg install`, `git`,
 *     `python`, `node`, `npm`, `ssh`, `vim`, etc. Long-lived `$HOME`
 *     in `/data/data/com.termux/files/home`. The agent can run any
 *     of it through one intent dispatch.
 *
 * Result delivery: Termux returns the exec result via a `PendingIntent`
 * we ship with the request. We register a one-shot `BroadcastReceiver`
 * bound to a UUID-suffixed action, suspend on a [CompletableDeferred],
 * and resolve when Termux fires the PI. The receiver is unregistered
 * in `finally` to keep the process tidy.
 *
 * Failure modes:
 *   - Termux not installed → returns `{status:"not_installed", hint:…}`
 *     (no exception thrown so the agent can recover and fall back to
 *     `run_shell`).
 *   - `allow-external-apps=true` not set in `~/.termux/termux.properties`
 *     → Termux drops the request silently; we time out and return
 *     `{status:"timeout", hint:"check allow-external-apps"}`.
 *   - Command exits non-zero → returns `{status:"ok", exitCode:N, …}`
 *     (non-zero exit is data, not an error — same as `run_shell`).
 */
@Singleton
class TermuxExecTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val availability: TermuxAvailability,
) : Tool {
    override val name = "termux_exec"
    override val description =
        "Run a shell command inside Termux's full GNU/Linux userland (apt-installable Debian " +
            "packages, persistent home dir, real TTY). PREFER this over run_shell when Termux is " +
            "installed — bare command names are auto-resolved against /data/data/com.termux/files/" +
            "usr/bin. Returns {status, exitCode, stdout, stderr}. Falls through to a structured " +
            "error when Termux isn't installed so you can fall back to run_shell."

    override val parameters = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Binary to run. Bare name (e.g. 'ls', 'python', 'git') resolves against " +
                        "/data/data/com.termux/files/usr/bin/. Absolute paths (e.g. " +
                        "'/system/bin/echo') are used as-is.",
                )
            })
            put("args", buildJsonObject {
                put("type", "array")
                put("description", "Arguments to the binary. Each entry must be a string.")
                put("items", buildJsonObject { put("type", "string") })
            })
            put("workdir", buildJsonObject {
                put("type", "string")
                put(
                    "description",
                    "Working directory inside Termux. Default Termux \$HOME " +
                        "(/data/data/com.termux/files/home).",
                )
            })
            put("background", buildJsonObject {
                put("type", "boolean")
                put(
                    "description",
                    "true = run silently via RunCommandService (default; result returned " +
                        "via callback). false = run in Termux's foreground session (TTY mode; " +
                        "user sees the command). Use false for `vim`, `htop`, interactive REPLs.",
                )
            })
            put("timeout_ms", buildJsonObject {
                put("type", "integer")
                put(
                    "description",
                    "Milliseconds to wait for the command to finish. Default 30000, max 180000.",
                )
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("command"))))
    }

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!availability.isInstalled()) {
            return ToolResult.ok(NOT_INSTALLED_JSON)
        }
        // Google Play Store Termux is a stripped-down build that
        // doesn't ship RunCommandService at all. Fail fast with a
        // clear hint instead of waiting for the 12-second timeout.
        if (availability.isPlayStoreVariant()) {
            return ToolResult.ok(PLAY_STORE_VARIANT_JSON)
        }

        val rawCmd = args["command"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        if (rawCmd.isBlank()) return ToolResult.fail("command must be non-empty")
        val cmdPath = if (rawCmd.startsWith("/")) rawCmd
        else "${TermuxAvailability.TERMUX_BIN_DIR}/$rawCmd"

        val cmdArgs = args["args"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull() }
            .orEmpty()
        val workdir = args["workdir"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
        val background = args["background"]?.jsonPrimitive?.contentOrNull()?.toBoolean() ?: true
        val timeoutMs = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull()?.toLongOrNull() ?: 30_000L)
            .coerceIn(500L, 180_000L)

        Log.d(TAG, "exec: $cmdPath ${cmdArgs.joinToString(" ")} (bg=$background, timeout=${timeoutMs}ms)")

        val resultBundle = withContext(Dispatchers.IO) {
            awaitTermuxResult(cmdPath, cmdArgs, workdir, background, timeoutMs)
        }
        if (resultBundle == null) {
            return ToolResult.ok(
                """{"status":"timeout","timeout_ms":$timeoutMs,""" +
                    """"hint":"check allow-external-apps=true in ~/.termux/termux.properties"}""",
            )
        }

        // Dump the full Bundle so we can see what Termux actually
        // returned. Termux's key naming has changed across major
        // versions, and the default values below are what bite us
        // when a key isn't present — surfacing the keys themselves
        // is the only reliable way to debug a misclassified result.
        Log.d(TAG, "result bundle keys: ${resultBundle.keySet().joinToString()}")
        resultBundle.keySet().forEach { k ->
            val v = runCatching { resultBundle.get(k) }.getOrNull()
            Log.d(TAG, "  $k = $v")
        }

        // Sometimes the result Bundle wraps everything in another
        // Bundle keyed differently across Termux versions; check
        // common nesting paths before falling back to top-level.
        val effective = resultBundle.getBundle(EXTRA_RESULT_BUNDLE)
            ?: resultBundle.getBundle("PLUGIN_RESULT_BUNDLE")
            ?: resultBundle.getBundle("commandResultBundle")
            ?: resultBundle

        val stdout = effective.getString("stdout").orEmpty()
        val stderr = effective.getString("stderr").orEmpty()
        val exitCode = effective.getInt("exitCode", Int.MIN_VALUE)
        // Termux uses "err" for the bridge-level error code, NOT
        // "errCode" (a common assumption from reading the wiki out
        // of context). Both names are tried for forward/backward
        // compatibility with version drift.
        val err = when {
            effective.containsKey("err") -> effective.getInt("err", 0)
            effective.containsKey("errCode") -> effective.getInt("errCode", 0)
            else -> 0
        }
        val errmsg = effective.getString("errmsg")
            ?: effective.getString("err_msg")
            ?: ""

        val truncatedOut = if (stdout.length > MAX_OUT) stdout.take(MAX_OUT) + "\n…[truncated]" else stdout
        val truncatedErr = if (stderr.length > MAX_OUT) stderr.take(MAX_OUT) + "\n…[truncated]" else stderr

        // Classify based on what Termux actually filled in:
        //
        //   Success — exitCode is a real value (not the sentinel).
        //   That means the binary ran and returned. The auxiliary
        //   `err` field is Termux-internal bookkeeping that defaults
        //   to -1 in some build paths even on success, so it CANNOT
        //   gate success. Real shell exits (zero or non-zero) are
        //   data the agent reasons about; we surface them as-is.
        //
        //   Bridge error — exitCode was NEVER set AND err != 0 AND
        //   errmsg is non-empty. That's Termux telling us "I never
        //   ran your command and here's why".
        //
        //   Empty result — exitCode missing and no other info either.
        //   Almost always allow-external-apps=true missing.
        val exitCodeSet = exitCode != Int.MIN_VALUE
        val hasErr = err != 0 && errmsg.isNotBlank()
        return when {
            exitCodeSet -> ToolResult.ok(
                """{"status":"ok","exitCode":$exitCode,"stdout":${jsonString(truncatedOut)},""" +
                    """"stderr":${jsonString(truncatedErr)}}""",
            )
            hasErr -> ToolResult.ok(
                """{"status":"bridge_error","err":$err,"errmsg":${jsonString(errmsg)},""" +
                    """"hint":"Termux rejected the request — most common cause is allow-external-apps=true missing from ~/.termux/termux.properties"}""",
            )
            else -> ToolResult.ok(
                """{"status":"empty_result","hint":"Termux returned a result with no stdout, stderr, or exit code. Check that allow-external-apps=true is set in ~/.termux/termux.properties and that you ran termux-reload-settings.","errmsg":${jsonString(errmsg)}}""",
            )
        }
    }

    /** Wire the RUN_COMMAND intent + register a one-shot
     *  BroadcastReceiver to catch Termux's reply. Returns the result
     *  Bundle or null on timeout. */
    private suspend fun awaitTermuxResult(
        cmdPath: String,
        cmdArgs: List<String>,
        workdir: String,
        background: Boolean,
        timeoutMs: Long,
    ): Bundle? {
        val deferred = CompletableDeferred<Bundle>()
        val resultAction = "com.mythara.TERMUX_RESULT_${UUID.randomUUID()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                // Termux nests its reply under the "result" key as a
                // Bundle. Pull that out; if missing, fall back to the
                // top-level extras so we don't lose anything.
                val payload = intent.getBundleExtra(EXTRA_RESULT_BUNDLE) ?: intent.extras ?: Bundle()
                deferred.complete(payload)
            }
        }

        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(resultAction),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )

            // FLAG_MUTABLE is REQUIRED here — Termux fills the result
            // Bundle into the intent extras via PendingIntent.send(ctx,
            // code, resultIntent). Immutable PIs drop those extras
            // silently (no error, just an empty Bundle in onReceive),
            // which sent us down a 90-minute rabbit hole the first
            // time it hit. The mutability surface is bounded: the PI
            // action is a per-call UUID nobody outside this process
            // can guess + the target is our own package.
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val callbackPi = PendingIntent.getBroadcast(
                context,
                0,
                Intent(resultAction).setPackage(context.packageName),
                piFlags,
            )

            val runIntent = Intent().apply {
                component = ComponentName(TERMUX_PKG, TERMUX_RUN_COMMAND_SERVICE)
                action = TERMUX_RUN_COMMAND_ACTION
                putExtra(EXTRA_COMMAND_PATH, cmdPath)
                if (cmdArgs.isNotEmpty()) putExtra(EXTRA_ARGUMENTS, cmdArgs.toTypedArray())
                if (workdir.isNotBlank()) putExtra(EXTRA_WORKDIR, workdir)
                putExtra(EXTRA_BACKGROUND, background)
                putExtra(EXTRA_PENDING_INTENT, callbackPi)
                // Required by Termux:RUN_COMMAND on Android 11+ when
                // the request comes from a "background" context — even
                // though we're typically already in a foreground
                // service, the foreground state isn't always passed
                // through, and missing this flag yields a silent drop.
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }

            val started = try {
                context.startService(runIntent)
            } catch (t: Throwable) {
                Log.w(TAG, "startService threw: ${t.message}")
                return Bundle().apply {
                    putInt("errCode", ERR_SERVICE_THREW)
                    putString("errmsg", "startService threw: ${t.message ?: t.javaClass.simpleName}")
                }
            }
            // startService returns null when the system can't resolve
            // the component — typically because RunCommandService
            // isn't declared in the installed Termux build (the Play
            // Store variant strips it). Surface that distinctly from
            // a permission-denied silent drop.
            if (started == null) {
                Log.w(TAG, "startService returned null — RunCommandService component not resolved")
                return Bundle().apply {
                    putInt("errCode", ERR_SERVICE_NOT_FOUND)
                    putString(
                        "errmsg",
                        "RunCommandService not found — the installed Termux build doesn't ship it. " +
                            "Most likely you have the Google Play Store variant; uninstall it and " +
                            "install Termux from F-Droid instead.",
                    )
                }
            }

            // Pad the timeout slightly so Termux's own command-runner
            // overhead (intent dispatch, process spawn) doesn't push
            // us over before the command finishes.
            return withTimeoutOrNull(timeoutMs + STARTUP_GRACE_MS) { deferred.await() }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()
    private fun String.escape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    private fun jsonString(s: String) = "\"" + s.escape() + "\""

    companion object {
        private const val TAG = "Mythara/TermuxExec"
        private const val MAX_OUT = 8_192
        private const val STARTUP_GRACE_MS = 4_000L

        private const val TERMUX_PKG = "com.termux"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        /** Bundle key Termux uses for the nested result extras. */
        private const val EXTRA_RESULT_BUNDLE = "result"

        /** Synthetic errCode values surfaced by awaitTermuxResult when
         *  the bridge fails BEFORE Termux even sees the request. Kept
         *  out of the 0..127 range Termux uses for normal exit codes
         *  so they never collide. */
        private const val ERR_SERVICE_NOT_FOUND = -1001
        private const val ERR_SERVICE_THREW = -1002

        private const val NOT_INSTALLED_JSON =
            """{"status":"not_installed","hint":"install Termux from F-Droid then enable allow-external-apps=true in ~/.termux/termux.properties; meanwhile fall back to run_shell"}"""

        private const val PLAY_STORE_VARIANT_JSON =
            """{"status":"play_store_variant","hint":"the installed Termux is from the Google Play Store, which is a stripped-down build that doesn't ship RunCommandService. Uninstall it and install Termux from F-Droid (https://f-droid.org/packages/com.termux/) — the maintained release. Until then, fall back to run_shell."}"""
    }
}
