package com.example.ottomatic.feature.shortcut

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.example.ottomatic.engine.service.MacroEngineService

/**
 * The activity a shortcut launches, which runs a macro and gets out of the way.
 *
 * A trampoline exists because a launcher shortcut *must* target an Activity —
 * `ShortcutInfo` takes an intent that is started with `startActivity`, and
 * `MacroEngineService` is `exported="false"` and could not be the target even if
 * services were allowed. So this is the smallest activity that can exist: no
 * layout, no content view, one call and [finish].
 *
 * It is also what makes the foreground-service start legal. Android 12+ blocks
 * starting one from the background, and being the foreground activity for the
 * instant this lives is an unambiguous exemption — where a widget tap relies on a
 * short interaction window that OEM builds treat inconsistently.
 *
 * The manifest gives it `Theme.Translucent.NoTitleBar`, `noHistory` and
 * `excludeFromRecents`, so nothing is drawn, nothing is left in the back stack and
 * nothing appears in Recents. The [Toast] is the only visible trace, and it is not
 * decoration: a shortcut that starts a macro doing something quiet — writing a
 * variable, sending a request — would otherwise be a launcher icon that appears to
 * do nothing at all.
 */
class RunTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val workflowId = intent?.getStringExtra(EXTRA_WORKFLOW_ID)
        val nodeId = intent?.getStringExtra(EXTRA_NODE_ID)
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()

        if (workflowId.isNullOrBlank() || nodeId.isNullOrBlank()) {
            // A shortcut pinned to a macro that has since been deleted. Say so:
            // the alternative is an icon that silently does nothing, which reads
            // as the app being broken rather than the macro being gone.
            Toast.makeText(this, "That macro no longer exists", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, if (label.isBlank()) "Running…" else "Running $label", Toast.LENGTH_SHORT).show()
            MacroEngineService.runManual(this, workflowId, nodeId)
        }
        finish()
    }

    companion object {
        const val EXTRA_WORKFLOW_ID = "workflowId"
        const val EXTRA_NODE_ID = "nodeId"
        const val EXTRA_LABEL = "label"

        /**
         * The intent a shortcut carries.
         *
         * `ACTION_VIEW` with an explicit component rather than a bare component
         * intent, because `ShortcutManagerCompat` requires the intent to have an
         * action set — a shortcut without one is rejected at publish time with an
         * exception rather than at the tap.
         */
        fun intent(context: Context, workflowId: String, nodeId: String, label: String): Intent =
            Intent(context, RunTriggerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_WORKFLOW_ID, workflowId)
                putExtra(EXTRA_NODE_ID, nodeId)
                putExtra(EXTRA_LABEL, label)
            }
    }
}
