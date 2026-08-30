package io.github.m1n1m1.easymatic.feature.grapheditor

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.m1n1m1.easymatic.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m1n1m1.easymatic.core.model.NodeId
import io.github.m1n1m1.easymatic.domain.model.Workflow
import io.github.m1n1m1.easymatic.engine.validation.GraphValidation
import io.github.m1n1m1.easymatic.engine.validation.Severity
import io.github.m1n1m1.easymatic.engine.validation.ValidationIssue
import kotlinx.coroutines.flow.StateFlow

/**
 * What is wrong with the graph, before it is ever run.
 *
 * The counterpart to [ConsoleBody] and deliberately not folded into it: the
 * console is a record of what *happened*, kept until it is cleared, while this is
 * a statement about what the graph *is* right now and goes away the moment the
 * wire is fixed. Sharing one list would have last night's failed run sitting
 * beside a live structural error with nothing to tell them apart.
 *
 * It takes the flow and collects it here for the reason [ConsoleBody] records:
 * collecting a level up invalidates `GraphEditorContent`, and `GraphCanvas` cannot
 * skip.
 *
 * [workflow] is passed rather than baked into the issues so a row always names the
 * node as it is called *now* — an issue is live state, unlike a
 * [io.github.m1n1m1.easymatic.core.service.LogEntry], which is a record of a moment and
 * rightly keeps the name the node had then.
 */
@Composable
fun ProblemsBody(
    validation: StateFlow<GraphValidation>,
    workflow: Workflow,
    onSelectNode: (NodeId) -> Unit,
    onSelectConnection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val problems by validation.collectAsState()
    // Errors first: one of them is stopping something from running, and a warning
    // never is.
    val rows = problems.errors + problems.warnings

    Column(modifier = modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            NoProblems()
            return@Column
        }
        Summary(problems)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            items(rows) { issue ->
                ProblemRow(
                    issue = issue,
                    workflow = workflow,
                    onSelect = {
                        // The node is what the user can act on; an edge is only
                        // worth selecting when the finding names no node at all.
                        val node = issue.nodes.firstOrNull { workflow.node(it) != null }
                        when {
                            node != null -> onSelectNode(node)
                            issue.connectionId != null -> onSelectConnection(issue.connectionId)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The Problems tab's warning light: a count, tinted by the worst thing in it.
 *
 * Its own composable so it collects [validation] itself — a badge that invalidated
 * its parent would take the whole bar with it on every keystroke in the editor.
 */
@Composable
fun ProblemsBadge(validation: StateFlow<GraphValidation>, content: @Composable () -> Unit) {
    val problems by validation.collectAsState()
    if (problems.isEmpty) {
        content()
        return
    }
    val tint = if (problems.errors.isEmpty()) EditorColors.warnAccent else EditorColors.errorAccent
    BadgedBox(
        badge = { Badge(containerColor = tint) { Text("${problems.issues.size}") } },
        content = { content() },
    )
}

@Composable
private fun Summary(problems: GraphValidation) {
    val errors = problems.errors.size
    val warnings = problems.warnings.size
    val parts = buildList {
        if (errors > 0) add(pluralStringResource(R.plurals.problems_errors, errors, errors))
        if (warnings > 0) add(pluralStringResource(R.plurals.problems_warnings, warnings, warnings))
    }
    Text(
        text = parts.joinToString(stringResource(R.string.problems_summary_separator)),
        color = EditorColors.textSecondary,
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * One finding. The severity bar and the tap-to-select behaviour mirror the
 * console's rows, so the two surfaces read as the same kind of list.
 */
@Composable
private fun ProblemRow(issue: ValidationIssue, workflow: Workflow, onSelect: () -> Unit) {
    val color = severityColor(issue.severity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = issue.text(),
                color = EditorColors.textPrimary,
                fontSize = 13.sp,
            )
            val where = whereText(issue, workflow)
            if (where != null) {
                Text(text = where, color = EditorColors.textSecondary, fontSize = 11.sp)
            }
        }
    }
}

/**
 * The line under a finding: which cards it is about, and whether anything is being
 * held back because of it.
 *
 * "Skipped" is the part a user cannot deduce from the message. A schema mismatch
 * sounds like a warning until you know a step will not run.
 */
@Composable
private fun whereText(issue: ValidationIssue, workflow: Workflow): String? {
    val names = issue.nodes.mapNotNull { workflow.node(it)?.name }
    val blocked = issue.blockedNodes.mapNotNull { workflow.node(it)?.name }
    val parts = buildList {
        if (names.isNotEmpty()) add(names.joinToString(stringResource(R.string.problems_path_arrow)))
        if (blocked.isNotEmpty()) {
            add(stringResource(R.string.problems_skipped_nodes, blocked.joinToString(", ")))
        } else if (issue.blockedConnections.isNotEmpty()) {
            add(stringResource(R.string.problems_wire_skipped))
        }
    }
    return parts.joinToString(stringResource(R.string.problems_where_separator)).takeIf { it.isNotEmpty() }
}

@Composable
private fun NoProblems() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 36.dp),
        ) {
            Text(
                text = stringResource(R.string.grapheditor_no_problems),
                color = EditorColors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.grapheditor_this_workflow_is_ready_to),
                color = EditorColors.textSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.ERROR -> EditorColors.errorAccent
    Severity.WARNING -> EditorColors.warnAccent
}

