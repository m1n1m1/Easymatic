// One composable per part of the panel, plus the wording each needs. Splitting the file
// would separate the pieces of one surface, which is the thing to read together.
@file:Suppress("TooManyFunctions")
@file:OptIn(ExperimentalLayoutApi::class)

package io.github.m1n1m1.easymatic.feature.grapheditor.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.R
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.domain.registry.NodeTypeRegistry
import io.github.m1n1m1.easymatic.engine.ai.GraphEditTools
import io.github.m1n1m1.easymatic.feature.ai.AiModelPickerOverlay
import io.github.m1n1m1.easymatic.feature.ai.LocalAiConnections
import io.github.m1n1m1.easymatic.feature.grapheditor.EditorColors
import io.github.m1n1m1.easymatic.feature.i18n.rememberNodeText
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The assistant, over the canvas.
 *
 * **One panel that changes height, not two panels that swap.** The handle drags the
 * conversation open and closed continuously and settles to whichever end it is nearer on
 * release — a Material sheet's behaviour, and worth the layout work for a reason a toggle
 * cannot give: you can see how much is behind it while you move it, and a swap between two
 * different composables could never be made to look like one thing moving.
 *
 * **The handle, the status row and the input never move.** Only the conversation above them
 * grows and shrinks. That is what makes the input consistent: it is the same field in the
 * same place whether the panel is folded away, open, or in the middle of a turn, so there
 * is no moment where the thing you were typing into is gone.
 *
 * **Size is a separate axis from the turn.** They were one thing at first — composing was
 * tall, working was a pill — and that read as the app deciding how much you were allowed to
 * see: you could not look back at what you had asked for while it was being worked on. Now
 * [AssistantTurn] says *what is happening* and [AssistantState.isExpanded] says *how much
 * of it is on screen*; sending sets only the second, because that is the moment the canvas
 * becomes the thing to watch.
 *
 * **It is in the layout tree, not a `Dialog`.** `EditorOverlay` is a `Dialog` precisely so
 * touches cannot fall through to the canvas, which is the opposite of what the folded state
 * needs. The dimming is a scrim whose alpha is the same fraction that drives the height, so
 * it comes and goes with the drag rather than snapping on.
 *
 * **It is not the collapsed dock removed on 2026-08-03.** That strip was permanent chrome
 * that had to stay under Material's 48 dp target to be worth overlaying, and its resize drag
 * sat over the bottom of the graph, so every pan started near the bottom edge resized it
 * instead. This is composed only while the assistant is open, and its handle is a full row
 * on the panel's own top edge rather than a hairline between the panel and the canvas.
 */
@Composable
internal fun BoxScope.AssistantOverlay(
    session: AssistantSession,
    onFocusNodes: (Set<NodeId>) -> Unit,
) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()

    // The keyboard rather than the field's focus: back dismisses the keyboard before it
    // reaches any `BackHandler`, which leaves the field focused with nothing below the
    // panel — and the margin belongs back the moment that happens.
    val keyboardIsUp = WindowInsets.isImeVisible

    // How much of the conversation is showing, 0 folded away and 1 fully open.
    //
    // An Animatable rather than a Boolean handed to `AnimatedContent`, because the finger
    // writes to it directly: during a drag it is snapped to wherever the finger is, and
    // only on release does it animate to an end. It is UI state and stays here — what the
    // session records is *which end it settled at*, which is the part worth keeping.
    val expansion = remember { Animatable(if (state.isExpanded) 1f else 0f) }

    // What the conversation measures when nothing is folding it, in pixels. Written during
    // measurement and read only inside the drag handler, never during composition. It has
    // to be the *measured* height rather than a constant, or a drag would be scaled against
    // a size the panel does not have and a short conversation would open onto empty space.
    val naturalHeight = remember { mutableFloatStateOf(1f) }

    // The canvas follows what the model just touched. The session stops emitting once the
    // user has taken the canvas, so this simply stops firing rather than fighting.
    LaunchedEffect(state.focusNodes) {
        if (state.focusNodes.isNotEmpty()) onFocusNodes(state.focusNodes)
    }

    // Follows the session when something other than the finger changes it — sending folds
    // the panel away, undoing brings it back.
    LaunchedEffect(state.isExpanded) {
        expansion.animateTo(if (state.isExpanded) 1f else 0f, SETTLE)
    }

    Scrim(
        visible = state.isOpen,
        alpha = { expansion.value },
        onTap = session::minimize.takeIf { state.isExpanded },
        modifier = Modifier.matchParentSize(),
    )

    AnimatedVisibility(
        visible = state.isOpen,
        enter = slideInVertically(tween(ENTER_MS)) { it } + fadeIn(tween(ENTER_MS)),
        exit = slideOutVertically(tween(EXIT_MS)) { it } + fadeOut(tween(EXIT_MS)),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Panel(
            modifier = Modifier
                .fillMaxWidth()
                // `imePadding` outside the margin, so the margin is what sits between the
                // panel and whatever is below it — and that is nothing worth keeping when
                // the keyboard is up. A gap there reads as the panel floating above the
                // keyboard rather than sitting on it, which is what every messaging app
                // has taught people to expect.
                .imePadding()
                .padding(start = MARGIN, end = MARGIN, top = MARGIN, bottom = if (keyboardIsUp) 0.dp else MARGIN),
        ) {
            DragHandle(
                // Only when there is a conversation to forget, and never while one is
                // running: the button keeps its place either way, so the row does not
                // reflow under a finger reaching for the ✕ beside it.
                canRestart = state.transcript.isNotEmpty() && state.turn !is AssistantTurn.Working,
                onRestart = session::restart,
                onToggle = session::toggleExpanded,
                onDrag = { delta ->
                    scope.launch {
                        expansion.snapTo((expansion.value - delta / naturalHeight.floatValue).coerceIn(0f, 1f))
                    }
                },
                onDragStopped = { velocity -> settle(session, expansion, velocity) },
                onClose = session::close,
            )
            Conversation(
                session = session,
                state = state,
                fraction = { expansion.value },
                naturalHeight = naturalHeight,
                // The only weighted child, which in a `Column` is what makes it the only
                // one measured *last*: the handle, the status and the input are measured
                // first and get the height they ask for, and the conversation takes
                // whatever is left. `fill = false` so it may also take less.
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusRow(session, state)
            InputRow(session, state, onFocused = session::expand)
        }
    }
}

/**
 * The dimming behind the panel.
 *
 * Its alpha is [alpha] read in the **draw** phase, so it follows the finger frame by frame
 * without recomposing anything — the whole reason the fraction is passed as a lambda. The
 * `AnimatedVisibility` around it is only for opening and closing; folding is the alpha.
 *
 * [onTap] is null when there is nothing folded to unfold, and that is also what stops an
 * invisible scrim from swallowing touches meant for the canvas.
 */
@Composable
private fun Scrim(visible: Boolean, alpha: () -> Float, onTap: (() -> Unit)?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(ENTER_MS)),
        exit = fadeOut(tween(EXIT_MS)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SCRIM_HEIGHT)
                .graphicsLayer { this.alpha = alpha() }
                .background(SCRIM)
                .then(
                    if (onTap == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(onTap) { detectTapGestures { onTap() } }
                    },
                ),
        )
    }
}

/**
 * Where the panel comes to rest once the finger lifts.
 *
 * Position decides it, except when the gesture was a flick — a fast drag is an intent
 * rather than a position, and settling a flick back to where it started is the thing that
 * makes a sheet feel stuck.
 */
private suspend fun settle(session: AssistantSession, expansion: Animatable<Float, *>, velocity: Float) {
    val expanded = when {
        velocity < -FLING_VELOCITY -> true
        velocity > FLING_VELOCITY -> false
        else -> expansion.value > SNAP_POINT
    }
    // Told first, then animated: the scrim stops taking touches at the moment the decision
    // is made rather than at the end of the animation.
    session.setExpanded(expanded)
    expansion.animateTo(if (expanded) 1f else 0f, SETTLE)
}

/**
 * The part that folds: what has been said, and which model is answering.
 *
 * [fraction] is a lambda rather than a value so it is read in the measure phase. The panel
 * therefore re-measures as the finger moves without recomposing any of its contents, which
 * is what keeps a drag over a long transcript smooth.
 */
@Composable
private fun Conversation(
    session: AssistantSession,
    state: AssistantState,
    fraction: () -> Float,
    naturalHeight: MutableFloatState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.lastIndex)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.folding(fraction, naturalHeight),
    ) {
        if (state.transcript.isEmpty()) {
            Text(
                text = stringResource(R.string.assistant_hint),
                color = EditorColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            // **The one text in the editor the user did not type.** An answer naming an
            // entity id, quoting a script or explaining what it could not do is worth
            // taking somewhere else, and a `Text` is not selectable unless something says
            // so. Around the whole list rather than per message, so a selection can run
            // from a question into its answer.
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = TRANSCRIPT_MAX_HEIGHT),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.transcript.size) { index -> MessageRow(state.transcript[index]) }
                }
            }
        }
        ModelChip(state.modelRef, session::chooseModel)
    }
}

/**
 * Reports [fraction] of the height this content wants, clipped, anchored to its bottom
 * edge.
 *
 * Three things at once, and each is load-bearing. It drops the **minimum** height, so a
 * short conversation opens to its own two lines rather than to a box with a gap above it,
 * while keeping the **maximum** it was given — which is what makes the input's priority
 * hold: `Column` hands this whatever the unweighted rows left over, and measuring past
 * that would take the space back. It **places from the bottom**, so folding hides the
 * oldest message and the newest stays against the input. And it reads [fraction] **in the
 * measure phase**, so a fold is a re-measure rather than a recomposition.
 *
 * [naturalHeight] is therefore what can actually be shown rather than what the transcript
 * would like, which is also the right thing for the drag to be scaled against.
 */
private fun Modifier.folding(fraction: () -> Float, naturalHeight: MutableFloatState): Modifier =
    clipToBounds().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        naturalHeight.floatValue = placeable.height.toFloat().coerceAtLeast(1f)
        val height = (placeable.height * fraction()).roundToInt().coerceIn(0, placeable.height)
        layout(placeable.width, height) {
            placeable.place(0, height - placeable.height)
        }
    }

/**
 * What the turn is doing, above the input and below the conversation.
 *
 * Outside the folding part deliberately: this is the one thing somebody who has folded the
 * panel away still needs to see, and it is the whole reason a folded panel is worth having
 * rather than just closing it.
 */
@Composable
private fun StatusRow(session: AssistantSession, state: AssistantState) {
    when (val turn = state.turn) {
        is AssistantTurn.Idle -> Unit
        is AssistantTurn.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = EditorColors.actionAccent,
            )
            Text(
                text = stepText(turn.step),
                color = EditorColors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
            TextButton(onClick = session::cancel) { Text(stringResource(R.string.assistant_stop)) }
        }
        is AssistantTurn.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = changesText(turn.changes, turn.isError),
                color = if (turn.isError) EditorColors.errorAccent else EditorColors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            if (state.canUndo) {
                TextButton(onClick = session::undo) { Text(stringResource(R.string.assistant_undo)) }
            }
            IconButton(onClick = session::dismissResult, modifier = Modifier.size(SMALL_ICON_BUTTON)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.assistant_dismiss),
                    tint = EditorColors.textSecondary,
                    modifier = Modifier.size(SMALL_ICON),
                )
            }
        }
    }
}

/**
 * The one fixture of the whole panel.
 *
 * Outside everything that folds and outside every branch on the turn, so it is the same
 * field in the same place at every moment the assistant is on screen. Only the send button
 * changes, and only to say that a turn is already running.
 */
@Composable
private fun InputRow(session: AssistantSession, state: AssistantState, onFocused: () -> Unit) {
    val canSend = state.draft.isNotBlank() && state.turn !is AssistantTurn.Working

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = session::editDraft,
            placeholder = { Text(stringResource(R.string.assistant_placeholder)) },
            modifier = Modifier
                .weight(1f)
                // Going to type is going to want to see what has been said, so tapping in
                // opens the conversation. On *focus* rather than on each keystroke: a fold
                // made while the field is focused is a deliberate act, and re-opening on
                // the next character would make it impossible to hold.
                .onFocusChanged { if (it.isFocused) onFocused() },
            maxLines = INPUT_MAX_LINES,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { session.send(state.draft) }),
            colors = TextFieldDefaults.colors(
                focusedTextColor = EditorColors.textPrimary,
                unfocusedTextColor = EditorColors.textPrimary,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedPlaceholderColor = EditorColors.textSecondary,
                unfocusedPlaceholderColor = EditorColors.textSecondary,
                cursorColor = EditorColors.actionAccent,
                focusedIndicatorColor = EditorColors.actionAccent,
                unfocusedIndicatorColor = EditorColors.chromeBorder,
            ),
        )
        IconButton(onClick = { session.send(state.draft) }, enabled = canSend) {
            Icon(
                imageVector = Icons.Filled.ArrowUpward,
                contentDescription = stringResource(R.string.assistant_send),
                tint = if (canSend) EditorColors.actionAccent else EditorColors.textSecondary,
            )
        }
    }
}

/**
 * Which model is answering.
 *
 * The same chooser the Ask AI node's Model field opens, for the reason that field has one:
 * an AI model profile is an identifier the user minted, and the list of them lives in one
 * place. A blank chip says so rather than picking one — an implicit default is invisible,
 * and quota is per key.
 */
@Composable
private fun ModelChip(modelRef: String, onChoose: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val connections = LocalAiConnections.current
    val name = when {
        modelRef.isBlank() -> stringResource(R.string.assistant_choose_model)
        else -> connections?.modelProfile(modelRef)?.name ?: stringResource(R.string.config_deleted_connection)
    }

    TextButton(onClick = { picking = true }, enabled = connections != null) {
        Icon(
            imageVector = Icons.Filled.Psychology,
            contentDescription = null,
            tint = EditorColors.textSecondary,
            modifier = Modifier.size(SMALL_ICON),
        )
        Text(
            text = name,
            color = EditorColors.textSecondary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 6.dp),
        )
    }

    if (picking && connections != null) {
        AiModelPickerOverlay(
            viewModel = connections,
            selectedId = modelRef.takeIf { it.isNotBlank() },
            onPick = {
                onChoose(it)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun MessageRow(message: AssistantMessage) {
    val (text, colour) = when (message) {
        is AssistantMessage.FromUser -> message.text to EditorColors.textPrimary
        is AssistantMessage.FromAssistant ->
            message.text to if (message.isError) EditorColors.errorAccent else EditorColors.textSecondary
        is AssistantMessage.Notice -> stringResource(message.notice.labelRes()) to EditorColors.textSecondary
    }
    val prefix = when (message) {
        is AssistantMessage.FromUser -> stringResource(R.string.assistant_you)
        else -> stringResource(R.string.assistant_ai)
    }
    Column {
        // The speaker labels are the app talking about the conversation rather than part
        // of it, so they stay out of the selection: what is copied is what was said.
        DisableSelection {
            Text(
                text = prefix,
                color = EditorColors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(text = text, color = colour, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The surface everything sits on.
 *
 * Its content is a `ColumnScope` so one row can claim a `weight`. That is not a styling
 * detail: in a `Column` the weighted children are measured **after** the rest, which is the
 * whole mechanism by which the input keeps its size when the conversation is long.
 */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = EditorColors.chrome,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Column(
            // The panel's own padding, which is not the same thing as the margin around
            // it: this is what keeps the input off the panel's edge, and it is wanted
            // whether or not there is a keyboard below.
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content()
        }
    }
}

/**
 * Drag to open and close the conversation; tap to send it to whichever end it is not at.
 *
 * **It is on the panel's own top edge, which is what keeps it clear of the canvas.** A dock
 * with a resize drag along the bottom of the graph was built here and removed on
 * 2026-08-03, because every pan started near the bottom edge grabbed the dock instead of
 * the canvas. This does not repeat that: the panel is only composed while the assistant is
 * open, the grabber sits above the panel rather than between it and the graph, and the row
 * is a full [HANDLE_HEIGHT] target rather than a hairline.
 *
 * `draggable` rather than `detectVerticalDragGestures`, for one thing the raw detector does
 * not give: a **velocity** in `onDragStopped`, which is what lets a flick mean something
 * other than wherever the finger happened to stop.
 *
 * The tap and the drag do not fight: a tap is cancelled once the pointer passes touch slop,
 * which is the same threshold `draggable` starts at.
 */
@Suppress("LongParameterList") // Four distinct things one row does; a holder would only rename them.
@Composable
private fun DragHandle(
    canRestart: Boolean,
    onRestart: () -> Unit,
    onToggle: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragStopped: suspend CoroutineScope.(Float) -> Unit,
    onClose: () -> Unit,
) {
    val label = stringResource(R.string.assistant_resize)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HANDLE_HEIGHT)
            .pointerInput(Unit) { detectTapGestures { onToggle() } }
            .draggable(
                state = rememberDraggableState(onDelta = onDrag),
                orientation = Orientation.Vertical,
                onDragStopped = onDragStopped,
            ),
    ) {
        // Mirrors the ✕ across the grabber, because the two are the same kind of thing:
        // one ends the conversation, the other ends this one and starts another.
        IconButton(
            onClick = onRestart,
            enabled = canRestart,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(CLOSE_BUTTON),
        ) {
            Icon(
                imageVector = Icons.Filled.RestartAlt,
                contentDescription = stringResource(R.string.assistant_restart),
                tint = if (canRestart) EditorColors.textSecondary else EditorColors.nodeBorder,
                modifier = Modifier.size(CLOSE_ICON),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = HANDLE_WIDTH, height = HANDLE_THICKNESS)
                .background(EditorColors.textSecondary, RoundedCornerShape(percent = 50))
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(CLOSE_BUTTON),
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.assistant_close),
                tint = EditorColors.textSecondary,
                modifier = Modifier.size(CLOSE_ICON),
            )
        }
    }
}

/**
 * What the model is doing, in the user's language.
 *
 * The session hands over a tool name and a typeId rather than a sentence, exactly so this
 * can be translated — the run log's English is a record of what happened, and this is a
 * label somebody is reading while they wait.
 */
@Composable
private fun stepText(step: AssistantStep?): String {
    step ?: return stringResource(R.string.assistant_thinking)
    val nodeText = rememberNodeText()
    val name = step.typeId?.let { typeId -> NodeTypeRegistry.byId(typeId)?.let(nodeText::name) }
    return when (step.tool) {
        GraphEditTools.READ_GRAPH,
        GraphEditTools.LIST_NODE_TYPES,
        GraphEditTools.DESCRIBE_NODE_TYPE,
        -> stringResource(R.string.assistant_looking)
        GraphEditTools.VALIDATE -> stringResource(R.string.assistant_checking)
        GraphEditTools.ADD_NODE -> when (name) {
            null -> stringResource(R.string.assistant_adding_a_node)
            else -> stringResource(R.string.assistant_adding, name)
        }
        GraphEditTools.DELETE_NODE -> stringResource(R.string.assistant_removing)
        GraphEditTools.SET_CONFIG -> stringResource(R.string.assistant_configuring)
        GraphEditTools.CONNECT,
        GraphEditTools.DISCONNECT,
        -> stringResource(R.string.assistant_wiring)
        else -> stringResource(R.string.assistant_working)
    }
}

/**
 * What a turn changed, counted rather than narrated.
 *
 * Nodes and wires only: a config change has no count worth reading, and the model's own
 * sentence in the transcript is where the detail belongs.
 */
@Composable
private fun changesText(changes: AssistantChanges, isError: Boolean): String {
    if (changes.isEmpty) {
        return stringResource(if (isError) R.string.assistant_failed else R.string.assistant_no_changes)
    }
    val added = countText(changes.nodesAdded, changes.wiresAdded)
    val removed = countText(changes.nodesRemoved, changes.wiresRemoved)
    return when {
        removed == null && added != null -> stringResource(R.string.assistant_added, added)
        added == null && removed != null -> stringResource(R.string.assistant_removed, removed)
        else -> stringResource(R.string.assistant_changed)
    }
}

/**
 * "3 nodes and 2 connections", or null when neither happened.
 *
 * Real `<plurals>` and the *existing* ones, which the selection bar already uses to say the
 * same two things — a second pair would be a second set of forms to get right in seven
 * languages for no new meaning.
 */
@Composable
private fun countText(nodes: Int, wires: Int): String? {
    val nodeText = if (nodes > 0) pluralStringResource(R.plurals.selection_nodes, nodes, nodes) else null
    val wireText = if (wires > 0) pluralStringResource(R.plurals.selection_connections, wires, wires) else null
    return when {
        nodeText != null && wireText != null -> stringResource(R.string.assistant_and, nodeText, wireText)
        else -> nodeText ?: wireText
    }
}

private fun AssistantNotice.labelRes(): Int = when (this) {
    AssistantNotice.NO_MODEL_CHOSEN -> R.string.assistant_notice_no_model
    AssistantNotice.CANCELLED -> R.string.assistant_notice_cancelled
    AssistantNotice.FAILED -> R.string.assistant_notice_failed
    AssistantNotice.NOTHING_SAID -> R.string.assistant_notice_done
    AssistantNotice.UNDONE -> R.string.assistant_notice_undone
}

private val SCRIM = Color(0xB3000000)

/**
 * The scrim is given a very large fixed height rather than its parent's.
 *
 * It lives inside an `AnimatedVisibility`, whose own size is what the enter and exit
 * transitions are animating — so a child sized to *that* would shrink with the transition
 * instead of holding still behind the panel.
 */
private val SCRIM_HEIGHT = 4_000.dp

private val TRANSCRIPT_MAX_HEIGHT = 260.dp
private const val ENTER_MS = 220
private const val EXIT_MS = 180
private const val INPUT_MAX_LINES = 5
private val SMALL_ICON = 18.dp
private val SMALL_ICON_BUTTON = 32.dp

/** The margin between the panel and everything around it. */
private val MARGIN = 12.dp

/**
 * The handle row, which is a touch target rather than a hairline.
 *
 * The ✕ **fills the row** and carries a larger glyph than the app's other small icon
 * buttons. That is where its extra size comes from: `IconButton`'s own padding rather than
 * the row's height, so the button grew and the panel did not. The grabber is unchanged and
 * deliberately so — it is a marking that says the panel moves, not a thing to aim at, and
 * the whole row takes the drag anyway.
 */
private val HANDLE_HEIGHT = 36.dp
private val CLOSE_BUTTON = HANDLE_HEIGHT
private val CLOSE_ICON = 24.dp
private val HANDLE_WIDTH = 36.dp
private val HANDLE_THICKNESS = 4.dp

/** Past halfway it opens, short of it it closes — unless the gesture was a flick. */
private const val SNAP_POINT = 0.5f

/** Pixels per second past which a drag is an intent rather than a position. */
private const val FLING_VELOCITY = 400f

/** Material's own sheet feel: quick, and without a bounce at the end. */
private val SETTLE = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
