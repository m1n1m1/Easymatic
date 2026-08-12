package com.example.ottomatic.engine.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.ottomatic.ServiceLocator
import com.example.ottomatic.domain.model.ApiContract
import com.example.ottomatic.domain.model.ApiTokens
import com.example.ottomatic.engine.api.ApiInputs
import com.example.ottomatic.engine.api.ApiRateLimiter
import com.example.ottomatic.engine.api.ApiRun
import com.example.ottomatic.engine.api.findApiTrigger
import kotlinx.coroutines.launch

/**
 * The process API's door for everything that is not an Android app: a shell script,
 * Tasker, Automate, MacroDroid, an `adb shell am broadcast`.
 *
 * ```
 * adb shell am broadcast -a com.example.ottomatic.action.RUN_MACRO \
 *   -p com.example.ottomatic --es macroId <id> --es token <key> --es in.city Vienna
 * ```
 *
 * ## A key is always required here
 *
 * Not merely sufficient, as it is on [ApiTriggerProvider], but **required** — and the
 * asymmetry is forced rather than chosen. `sendBroadcast` carries no sender identity
 * whatsoever: there is no `getCallingPackage`, no uid, nothing. So a caller here
 * cannot be recognised, only *authenticated*, and a bearer secret is the only
 * instrument that works. A trigger with no key is therefore unreachable through this
 * door at all, which is exactly what clearing the key field is for.
 *
 * The secret is safe to send this way only because the caller sets the package, making
 * the intent explicit; an implicit broadcast of the same action would be readable by
 * every app on the phone. `docs/EXTERNAL_API.md` says so in the one place a caller
 * will read.
 *
 * ## Nothing is answered
 *
 * A broadcast has no return value, so a wrong key, a missing macro and a switched-off
 * macro are all indistinguishable from the outside — they differ only in a log line.
 * That is the honest cost of this door, and the reason `docs/EXTERNAL_API.md` points
 * anything that wants to know what happened at the provider instead.
 *
 * ## Android 12+ will usually refuse the foreground service
 *
 * A third-party broadcast is on no foreground-service-start exemption list, so
 * `ApiRun.start`'s `startForegroundService` is expected to throw here and its
 * `appScope` fallback is the normal road rather than the unusual one. What that costs
 * is the service's protection against the process being reaped mid-run, which matters
 * only for a macro that takes real time — one more reason the docs send long macros to
 * the provider.
 */
class ApiTriggerReceiver : BroadcastReceiver() {

    @Suppress("ReturnCount") // A door is a sequence of refusals; each one is its own sentence.
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApiContract.ACTION_RUN) return
        val macroId = intent.getStringExtra(ApiContract.EXTRA_MACRO_ID)?.takeIf { it.isNotBlank() } ?: return
        val nodeId = intent.getStringExtra(ApiContract.EXTRA_NODE_ID)
        val token = intent.getStringExtra(ApiContract.EXTRA_TOKEN)
        val inputs = inputsFrom(intent.extras)
        // Every anonymous caller shares one bucket, which is the right way round: the
        // door that cannot say who is knocking should be the more tightly bounded one.
        if (!limiter.allow(ApiRateLimiter.INTENT_CALLER)) {
            Log.w(TAG, "Rate limited an API broadcast for '$macroId'")
            return
        }
        // goAsync, because resolving the macro is a file read and onReceive's ~10s is
        // spent the moment the process was cold-started to deliver this.
        val pending = goAsync()
        val appContext = context.applicationContext
        ServiceLocator.appScope.launch {
            try {
                dispatch(appContext, macroId, nodeId, token, inputs)
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("ReturnCount") // As [onReceive]: each refusal is a distinct log line.
    private suspend fun dispatch(
        context: Context,
        macroId: String,
        nodeId: String?,
        token: String?,
        inputs: Map<String, String>,
    ) {
        val target = findApiTrigger(ServiceLocator.workflowRepository, macroId, nodeId)
        if (target == null) {
            Log.w(TAG, "No API trigger for macro '$macroId'")
            return
        }
        if (!ApiTokens.matches(target.token, token)) {
            Log.w(TAG, "Refused an API broadcast for '$macroId': wrong or missing key")
            return
        }
        if (!target.workflow.enabled) {
            Log.i(TAG, "Ignored an API broadcast for '${target.workflow.name}': it is switched off")
            return
        }
        ApiRun.start(context, target, ApiInputs.read(inputs, target.specs), CALLER)
    }

    private companion object {
        const val TAG = "Ottomatic"

        /** What the run log calls a caller this door cannot identify. */
        const val CALLER = "an external call"

        val limiter = ApiRateLimiter()
    }
}
