package io.github.m1n1m1.easymatic.data.prompt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.core.service.PromptAnswer
import io.github.m1n1m1.easymatic.core.service.PromptField
import io.github.m1n1m1.easymatic.core.service.PromptFieldKind
import io.github.m1n1m1.easymatic.core.service.PromptRequest
import io.github.m1n1m1.easymatic.core.service.Prompts
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Shows a macro's question in a window that floats over whatever the user is
 * actually looking at.
 *
 * **Why an overlay and not an Activity.** The engine is a foreground service, and
 * from Android 10 on an app in the background may not start an Activity at all.
 * The documented way out of that is holding `SYSTEM_ALERT_WINDOW` — so an
 * Activity would need the very same grant this does, and would additionally drag
 * a task switch, a back stack and a rotation lifecycle into something that exists
 * to ask one question. Drawing the window directly is the smaller thing.
 *
 * **Why Material Components and not Compose.** This is the app's one surface that
 * is not Compose, and it is not a preference: `data/` may not depend on
 * `feature/`, and hosting Compose in a raw window means standing up a lifecycle
 * owner, a saved-state registry and a view-model store by hand, plus back-press
 * and IME plumbing. [MaterialAlertDialogBuilder] *is* the Material 3 dialog —
 * shape, elevation, type scale, button placement, the list layout, the icon slot
 * — so using it is how this window looks current without any of it being drawn
 * here.
 *
 * **It follows the phone, not the editor.** The canvas is a fixed dark palette;
 * this deliberately is not. A window that appears over somebody's messaging app
 * should look like it came from their phone, so the theme is `DayNight` and, on
 * Android 12+, [DynamicColors] repaints it from the wallpaper. The one thing that
 * stays ours is the bolt in the title slot — enough to say who is asking,
 * without pretending to be a system dialog.
 *
 * **One at a time.** A [Mutex] serialises the whole show-and-wait, so two macros
 * asking at once queue rather than stack. A queued request whose caller gives up
 * first simply never appears, which is the honest outcome: its timeout was
 * measured from when it was asked, not from when it got to the front.
 *
 * **Known limit:** an overlay does not appear over the lock screen. With the
 * display off the dialog waits behind it and will usually hit its timeout, so a
 * macro that has to reach somebody who is not holding their phone wants a
 * notification instead.
 */
class OverlayPrompts(context: Context) : Prompts {

    /**
     * The context every dialog is built from: Easymatic's Material 3 theme, then
     * the system's own palette on top of it where the platform has one.
     *
     * Built once. `wrapContextIfAvailable` reads the device's dynamic colours,
     * and doing that per dialog would pay for it on every question.
     */
    private val themed: Context = DynamicColors.wrapContextIfAvailable(
        ContextThemeWrapper(context.applicationContext, R.style.Theme_Easymatic_Dialog),
    )

    private val gate = Mutex()
    private val main = Handler(Looper.getMainLooper())

    override suspend fun ask(request: PromptRequest): PromptAnswer {
        if (!Settings.canDrawOverlays(themed)) {
            return PromptAnswer.Unavailable("Easymatic may not draw over other apps")
        }
        return gate.withLock {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { continuation -> show(request, continuation) }
            }
        }
    }

    /**
     * Builds and shows the dialog, resuming [continuation] with whatever the user
     * does to it.
     *
     * `runCatching` around the show covers the one failure the permission check
     * cannot rule out: the grant can be revoked between the check and the call,
     * and a `BadTokenException` escaping here would take the run down instead of
     * landing on the node's cancelled branch.
     */
    private fun show(request: PromptRequest, continuation: CancellableContinuation<PromptAnswer>) {
        val answered = Answered(continuation)
        val builder = MaterialAlertDialogBuilder(themed)
            .setCancelable(true)
            .setOnCancelListener { answered.resume(PromptAnswer.Cancelled) }
        request.cancelLabel?.let { label ->
            builder.setNegativeButton(label) { _, _ -> answered.resume(PromptAnswer.Cancelled) }
        }
        if (request.options.isNotEmpty()) {
            fillList(builder, request, answered)
        } else {
            fillValue(builder, request, answered)
        }

        val dialog = builder.create()
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // The keyboard has to be able to reach a field in a window the user
            // never navigated to.
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        // Dismissal is registered rather than done at each exit: the user has four
        // ways out (either button, back, a tap outside) and the caller has a fifth.
        dialog.setOnDismissListener { answered.resume(PromptAnswer.Cancelled) }
        continuation.invokeOnCancellation { main.post { runCatching { dialog.dismiss() } } }

        val shown = runCatching { dialog.show() }.isSuccess
        if (!shown) answered.resume(PromptAnswer.Unavailable("the dialog could not be opened"))
    }

    /**
     * A dialog offering a list, where tapping an entry *is* the confirmation — so
     * there is no positive button to press for one decision.
     *
     * Its question goes into a custom header rather than through `setMessage`;
     * see `dialog_prompt_header.xml` for why the platform leaves no choice.
     */
    private fun fillList(builder: MaterialAlertDialogBuilder, request: PromptRequest, answered: Answered) {
        builder.setCustomTitle(header(request))
        builder.setItems(request.options.toTypedArray()) { _, which ->
            answered.resume(PromptAnswer.Confirmed(request.options[which], which))
        }
    }

    /** A dialog that states something, and optionally takes one value for it. */
    private fun fillValue(builder: MaterialAlertDialogBuilder, request: PromptRequest, answered: Answered) {
        val field = request.field?.let { inputFor(it) }
        builder.setIcon(R.drawable.ic_dialog_easymatic)
        builder.setTitle(request.title)
        if (request.message.isNotBlank()) builder.setMessage(request.message)
        field?.let { builder.setView(it.view) }
        builder.setPositiveButton(request.confirmLabel) { _, _ ->
            answered.resume(PromptAnswer.Confirmed(field?.read().orEmpty()))
        }
    }

    /** The icon, title and question of a list dialog, stacked as Material 3 stacks them. */
    private fun header(request: PromptRequest): View =
        LayoutInflater.from(themed).inflate(R.layout.dialog_prompt_header, null).apply {
            findViewById<TextView>(R.id.prompt_title).text = request.title
            findViewById<TextView>(R.id.prompt_message).apply {
                text = request.message
                visibility = if (request.message.isBlank()) View.GONE else View.VISIBLE
            }
        }

    /**
     * Resumes a continuation exactly once.
     *
     * Load-bearing rather than defensive: pressing a button both fires its
     * listener *and* dismisses the dialog, so every ordinary answer arrives here
     * twice, with `Cancelled` second. First one wins, and the real answer is
     * always the first.
     */
    private class Answered(private val continuation: CancellableContinuation<PromptAnswer>) {
        private var done = false

        fun resume(answer: PromptAnswer) {
            if (done || !continuation.isActive) return
            done = true
            continuation.resume(answer)
        }
    }

    /**
     * The control a [PromptField] is typed into, and how to read it back.
     *
     * A [TextInputLayout] rather than a bare `EditText`, because the outlined box
     * with a floating label *is* what a text field looks like in Material 3 — and
     * because it gives the hint somewhere to go that is not inside the box the
     * user is about to type over.
     */
    private fun inputFor(field: PromptField): Input = when (field.kind) {
        PromptFieldKind.YES_OR_NO -> MaterialCheckBox(themed).apply {
            text = field.hint.ifBlank { "Yes" }
            isChecked = field.initial.trim().lowercase() in TRUE_WORDS
        }.let { box -> Input(pad(box), { box.isChecked.toString() }) }

        else -> {
            // The style has to arrive as a themed *context*: `TextInputLayout`'s
            // third constructor argument is a `defStyleAttr`, so handing it a style
            // resource silently leaves the field looking like the theme's default.
            val outlined = ContextThemeWrapper(themed, OUTLINED_BOX_STYLE)
            val box = TextInputLayout(outlined).apply { hint = field.hint.ifBlank { null } }
            val edit = TextInputEditText(box.context).apply {
                setText(field.initial)
                inputType = inputTypeOf(field.kind)
                if (field.kind != PromptFieldKind.MULTILINE_TEXT) maxLines = 1
                setSelection(text?.length ?: 0)
            }
            // Stated rather than left to `generateDefaultLayoutParams`, which only
            // happens to be full width because `TextInputLayout` is a vertical
            // `LinearLayout` — a field half the dialog wide would look like a bug.
            box.addView(
                edit,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            Input(pad(box), { edit.text?.toString().orEmpty() })
        }
    }

    /**
     * The keyboard a kind asks for. A date is deliberately ordinary text: the
     * parser behind it takes `18:00`, `2026-07-27`, a full ISO stamp and epoch
     * millis, which is more of what people type than a picker would accept.
     */
    private fun inputTypeOf(kind: PromptFieldKind): Int = when (kind) {
        PromptFieldKind.NUMBER ->
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        PromptFieldKind.WHOLE_NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        PromptFieldKind.MULTILINE_TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        else -> InputType.TYPE_CLASS_TEXT
    }

    /**
     * Insets a custom view to the dialog's own margins.
     *
     * `setView` drops what it is given flush against the edges — Material 3's
     * padding applies to the title and message it lays out itself, not to a view
     * handed in from outside.
     */
    private fun pad(view: View): View = FrameLayout(themed).apply {
        val density = resources.displayMetrics.density
        setPadding(
            (SIDE_PADDING_DP * density).toInt(),
            (TOP_PADDING_DP * density).toInt(),
            (SIDE_PADDING_DP * density).toInt(),
            0,
        )
        addView(view)
    }

    private class Input(val view: View, val read: () -> String)

    private companion object {
        val TRUE_WORDS = setOf("true", "yes", "on", "1")

        /** Material 3's outlined text field, the default shape for a field on a surface. */
        val OUTLINED_BOX_STYLE = com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox

        const val SIDE_PADDING_DP = 24
        const val TOP_PADDING_DP = 8
    }
}
