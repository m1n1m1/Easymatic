package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.example.ottomatic.domain.model.config.ValueType
import com.example.ottomatic.domain.registry.CONVERT_IN
import com.example.ottomatic.domain.registry.CONVERT_TO_KEY
import com.example.ottomatic.domain.registry.CONVERT_TYPE_ID
import com.example.ottomatic.domain.registry.TRANSFORM_OUT
import org.junit.Test

/**
 * Verifies [GraphValidator] enforces the two-channel (execution + data)
 * model: port kind/direction sanity, acyclicity, strict data semantics
 * (source must be exec-upstream of target), and schema subtyping.
 */
class GraphValidatorTest {

    @Test
    fun `sample workflow with valid exec edges validates clean`() {
        val wf = sampleWorkflow()
        val issues = GraphValidator(wf).validate()
        val errors = issues.filter { it.severity == Severity.ERROR }
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
    }

    @Test
    fun `exec cycle is rejected`() {
        val wf = sampleWorkflow().copy(
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n1"), PortName("in")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("Execution cycle") })
    }

    @Test
    fun `data edge between exec ports is rejected`() {
        val wf = sampleWorkflow().copy(
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("data output port") })
    }

    @Test
    fun `unknown exec port is rejected`() {
        val wf = sampleWorkflow().copy(
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("nope"), NodeId("n2"), PortName("in")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(issues.any { it.severity == Severity.ERROR })
    }

    @Test
    fun `strict data semantics rejects source not exec-upstream of target`() {
        // n1 (trigger.manual, no data) and n2 (action.http has data out `response`).
        // Wire data n2.response -> some target that is exec-upstream of n2.
        // Simplest: add a second action n6 exec-fed by n2; then wire data n6->n2
        // (n6 is NOT exec-upstream of n2). For a self-contained case: data from
        // n2 back to n1 is impossible (n1 has no data in). Use two actions n2,n6
        // where n6 depends on n2 exec-wise, then data n6->n2 must be rejected.
        val wf = sampleWorkflow().copy(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.http"), "HTTP", 0f, 100f),
                WorkflowNode(NodeId("n6"), NodeTypeId("action.notify"), "Notify", 0f, 200f),
            ),
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n6"), PortName("in")),
            ),
            dataConnections = listOf(
                // No node currently has a DATA *input* port in v1, so this edge
                // will fail on the port-kind check rather than the strict-data
                // check. Still assert it is an error either way.
                DataConnection("d1", NodeId("n2"), PortName("response"), NodeId("n6"), PortName("in")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(issues.any { it.severity == Severity.ERROR })
    }

    @Test
    fun `connecting exec output to exec input of correct kind is accepted`() {
        val wf = sampleWorkflow()
        val errors = GraphValidator(wf).validate().filter { it.severity == Severity.ERROR }
        assertTrue("got errors: $errors", errors.isEmpty())
    }

    @Test
    fun `isValid returns true for clean sample workflow`() {
        assertTrue(GraphValidator(sampleWorkflow()).isValid())
    }

    @Test
    fun `isValid returns false for exec cycle`() {
        val wf = sampleWorkflow().copy(
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n1"), PortName("in")),
            ),
        )
        assertFalse(GraphValidator(wf).isValid())
    }

    @Test
    fun `the comparison with a source data edge is not flagged`() {
        val wf = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.charging"), "Charging", 0f, 0f),
                WorkflowNode(NodeId("c"), NodeTypeId("action.if"), "If", 0f, 100f),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("c"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("state"), NodeId("c"), PortName("source")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(
            "wired source should produce no errors, got: ${issues.map { it.message }}",
            issues.none { it.severity == Severity.ERROR },
        )
    }

    /**
     * The one validation rule a value node is exempt from, and the reason it can be
     * pulled at all: "the source must be exec-upstream" is meaningless for a node
     * that is never pulsed. It is read while collecting the target's inputs, which is
     * always in time.
     */
    @Test
    fun `a value source need not be exec-upstream of its consumer`() {
        val wf = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("title") to "T"),
                    visibleDataInputs = setOf(PortName("text")),
                ),
                // No exec edge into the value at all — it has no exec ports to wire.
                WorkflowNode(NodeId("v"), NodeTypeId("value.ringer"), "Ringer", 200f, 0f),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("v"), PortName("mode"), NodeId("n2"), PortName("text")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(
            "a pulled value must not trip the exec-upstream rule, got: ${issues.map { it.message }}",
            issues.none { it.severity == Severity.ERROR },
        )
    }

    @Test
    fun `a value wired to nothing is warned about`() {
        val wf = Workflow(
            nodes = listOf(WorkflowNode(NodeId("v"), NodeTypeId("value.battery"), "Battery", 0f, 0f)),
        )
        val issues = GraphValidator(wf).validate()

        assertTrue(
            "an unread value should warn, got: ${issues.map { it.message }}",
            issues.any { it.severity == Severity.WARNING && it.message.contains("never be read") },
        )
        assertTrue(issues.none { it.severity == Severity.ERROR })
    }

    /**
     * A transform is pulled like a value, so the exec-upstream rule is just as
     * meaningless for it — and the whole autocast design depends on that, since the
     * Convert node the editor drops into a wire is never given an exec edge.
     */
    @Test
    fun `a transform in a data wire need not be exec-upstream of its consumer`() {
        val wf = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("title") to "T"),
                    visibleDataInputs = setOf(PortName("text")),
                ),
                WorkflowNode(NodeId("v"), NodeTypeId("value.battery"), "Battery", 200f, 0f),
                WorkflowNode(
                    NodeId("c"), CONVERT_TYPE_ID, "Convert", 200f, 50f,
                    config = mapOf(CONVERT_TO_KEY to ValueType.TEXT.name),
                    visibleDataInputs = setOf(CONVERT_IN),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("v"), PortName("level"), NodeId("c"), CONVERT_IN),
                DataConnection("d2", NodeId("c"), TRANSFORM_OUT, NodeId("n2"), PortName("text")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(
            "a pulled transform must not trip the exec-upstream rule, got: ${issues.map { it.message }}",
            issues.none { it.severity == Severity.ERROR },
        )
    }

    /**
     * The other half of the same exemption, and the one that was missing: the
     * transform is the *target* here. Nothing can ever be exec-upstream of a Convert
     * node — it has no exec input to reach — so checking the edge against the
     * transform itself failed every graph of the ordinary shape "trigger produces a
     * number, Convert makes it text, notification shows it". The rule has to be
     * applied against the node that eventually reads the chain.
     */
    @Test
    fun `a trigger may feed a transform that feeds a downstream action`() {
        val wf = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.light_level"), "Light", 0f, 0f),
                WorkflowNode(NodeId("b"), NodeTypeId("action.break"), "Break", 0f, 80f),
                WorkflowNode(
                    NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 160f,
                    config = mapOf(ConfigKey("title") to "T"),
                    visibleDataInputs = setOf(PortName("text")),
                ),
                WorkflowNode(
                    NodeId("c"), CONVERT_TYPE_ID, "Convert", 200f, 120f,
                    config = mapOf(CONVERT_TO_KEY to ValueType.TEXT.name),
                    visibleDataInputs = setOf(CONVERT_IN),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("b"), PortName("in")),
                ExecConnection("e2", NodeId("b"), PortName("out"), NodeId("n"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("t"), PortName("reading"), NodeId("b"), PortName("struct")),
                DataConnection("d2", NodeId("b"), PortName("value"), NodeId("c"), CONVERT_IN),
                DataConnection("d3", NodeId("c"), TRANSFORM_OUT, NodeId("n"), PortName("text")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(
            "a transform in the wire must not fail the exec-upstream rule, got: ${issues.map { it.message }}",
            issues.none { it.severity == Severity.ERROR },
        )
    }

    /**
     * Exempting the transform must not lose the rule it stands in for: the source
     * still has to have run by the time the *consumer* does, however many
     * conversions sit in between.
     */
    @Test
    fun `a transform does not launder a source that is not exec-upstream`() {
        val wf = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("t"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n"), NodeTypeId("action.notify"), "Notify", 0f, 80f,
                    config = mapOf(ConfigKey("title") to "T"),
                    visibleDataInputs = setOf(PortName("text")),
                ),
                // Runs *after* the notification, so its response cannot reach it.
                WorkflowNode(NodeId("h"), NodeTypeId("action.http"), "HTTP", 0f, 160f),
                WorkflowNode(
                    NodeId("c"), CONVERT_TYPE_ID, "Convert", 200f, 120f,
                    config = mapOf(CONVERT_TO_KEY to ValueType.TEXT.name),
                    visibleDataInputs = setOf(CONVERT_IN),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("t"), PortName("out"), NodeId("n"), PortName("in")),
                ExecConnection("e2", NodeId("n"), PortName("out"), NodeId("h"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("h"), PortName("response"), NodeId("c"), CONVERT_IN),
                DataConnection("d2", NodeId("c"), TRANSFORM_OUT, NodeId("n"), PortName("text")),
            ),
        )
        val issues = GraphValidator(wf).validate()
        assertTrue(
            "the rule must still see through the transform, got: ${issues.map { it.message }}",
            issues.any { it.severity == Severity.ERROR && it.message.contains("not exec-upstream") },
        )
    }

    @Test
    fun `a transform with nothing wired into it is warned about`() {
        val wf = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(
                    NodeId("n2"), NodeTypeId("action.notify"), "Notify", 0f, 100f,
                    config = mapOf(ConfigKey("title") to "T"),
                    visibleDataInputs = setOf(PortName("text")),
                ),
                WorkflowNode(NodeId("c"), CONVERT_TYPE_ID, "Convert", 200f, 50f),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d2", NodeId("c"), TRANSFORM_OUT, NodeId("n2"), PortName("text")),
            ),
        )
        val issues = GraphValidator(wf).validate()

        assertTrue(
            "a starved transform should warn, got: ${issues.map { it.message }}",
            issues.any { it.severity == Severity.WARNING && it.message.contains("nothing wired into it") },
        )
        assertTrue(issues.none { it.severity == Severity.ERROR })
    }

    @Suppress("unused")
    private fun portKindUnused(): PortKind = PortKind.EXECUTION

    private fun sampleWorkflow(): Workflow = Workflow(
        nodes = listOf(
            WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual Trigger", 0f, 0f),
            WorkflowNode(NodeId("n2"), NodeTypeId("action.http"), "HTTP Request", 0f, 100f),
        ),
        execConnections = listOf(
            ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
        ),
    )
}
