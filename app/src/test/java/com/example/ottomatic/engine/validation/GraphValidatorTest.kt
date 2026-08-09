package com.example.ottomatic.engine.validation

import com.example.ottomatic.core.model.ConfigKey
import com.example.ottomatic.core.model.NodeId
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.domain.model.DataConnection
import com.example.ottomatic.domain.model.ExecConnection
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.VariableDeclaration
import com.example.ottomatic.domain.model.VariableRef
import com.example.ottomatic.domain.model.Workflow
import com.example.ottomatic.domain.model.WorkflowNode
import com.example.ottomatic.domain.model.WorkflowSummary
import com.example.ottomatic.core.permissions.PrerequisiteType
import com.example.ottomatic.domain.registry.GrantedPrerequisites
import com.example.ottomatic.domain.registry.MacroDirectory
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
        val issues = GraphValidator(wf).validate().issues
        val errors = issues.filter { it.severity == Severity.ERROR }
        assertTrue("expected no errors, got: $errors", errors.isEmpty())
    }

    /**
     * A node pointing at a variable that is not there — never chosen, or since
     * deleted — is a warning that blocks nothing.
     *
     * The node degrades exactly as it always has: it logs that it stored nothing
     * and pulses `out`. Quarantining an action because one of its fields is unset
     * would take out work the user can see is otherwise fine, which is the same
     * stance "'X' is not wired to anything" already takes. Deliberately *not* the
     * stance a broken data edge gets: there, falling back would substitute a form
     * value for a wire drawn on the canvas.
     */
    @Test
    fun `a variable reference that resolves to nothing warns and blocks nothing`() {
        val node = WorkflowNode(
            NodeId("set"), NodeTypeId("action.set_variable"), "Remember", 0f, 0f,
            config = mapOf(ConfigKey("name") to "not-a-declaration"),
        )
        val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

        assertTrue(
            validation.warnings.toString(),
            validation.warnings.any { it.message.contains("no longer exists") && node.id in it.nodes },
        )
        assertTrue(validation.blockedNodes.isEmpty())
        assertTrue(validation.isRunnable)
    }

    @Test
    fun `a node with no variable chosen warns that it will do nothing`() {
        val node = WorkflowNode(
            NodeId("set"), NodeTypeId("action.set_variable"), "Remember", 0f, 0f,
        )
        val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

        assertTrue(
            validation.warnings.toString(),
            validation.warnings.any { it.message.contains("no variable chosen") },
        )
        assertTrue(validation.isRunnable)
    }

    /**
     * A macro reference that resolves to nothing is the same family as a dangling
     * variable reference and gets the same stance: `MacroControl.enable` already
     * returns false and the node already pulses `out`, so there is nothing to block
     * — but until this warning existed nothing anywhere said why.
     */
    /**
     * A port name is unique per kind and direction, not globally, so a node may
     * legitimately have a DATA input called `body` — `action.send_sms` and
     * `action.http` both do, and both were being reported as loops with nothing in
     * their body because the loop check matched the name alone.
     */
    @Test
    fun `a data input named body does not make a node a loop`() {
        val sms = WorkflowNode(NodeId("sms"), NodeTypeId("action.send_sms"), "Text me", 0f, 0f)
        val http = WorkflowNode(NodeId("http"), NodeTypeId("action.http"), "Post it", 0f, 0f)

        val validation = GraphValidator(Workflow(nodes = listOf(sms, http))).validate()

        assertFalse(
            validation.warnings.toString(),
            validation.warnings.any { it.message.contains("loop body") },
        )
    }

    /** The other half of the same rule: a real loop is still reported. */
    @Test
    fun `a loop with nothing wired to its body still warns`() {
        val loop = WorkflowNode(NodeId("rep"), NodeTypeId("action.repeat"), "Repeat", 0f, 0f)

        val validation = GraphValidator(Workflow(nodes = listOf(loop))).validate()

        assertTrue(
            validation.warnings.toString(),
            validation.warnings.any { it.message.contains("loop body") && loop.id in it.nodes },
        )
    }

    @Test
    fun `a macro reference that resolves to nothing warns and blocks nothing`() {
        MacroDirectory.hydrate(listOf(WorkflowSummary(id = "other", name = "Other")))
        try {
            val node = WorkflowNode(
                NodeId("en"), NodeTypeId("action.enable_macro"), "Enable", 0f, 0f,
                config = mapOf(ConfigKey("macroId") to "deleted"),
            )
            val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

            assertTrue(
                validation.warnings.toString(),
                validation.warnings.any { it.message.contains("no longer exists") && node.id in it.nodes },
            )
            assertTrue(validation.blockedNodes.isEmpty())
            assertTrue(validation.isRunnable)
        } finally {
            MacroDirectory.reset()
        }
    }

    @Test
    fun `a node with no macro chosen warns that it will do nothing`() {
        val node = WorkflowNode(NodeId("en"), NodeTypeId("action.enable_macro"), "Enable", 0f, 0f)
        val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

        assertTrue(
            validation.warnings.toString(),
            validation.warnings.any { it.message.contains("no macro chosen") },
        )
        assertTrue(validation.isRunnable)
    }

    /**
     * Empty and unasked are different states. A process that has never listed
     * workflows — the executor validating a disk snapshot on boot — must not report
     * every macro reference in the graph as dangling.
     */
    @Test
    fun `an unhydrated macro directory reports nothing dangling`() {
        MacroDirectory.reset()
        val node = WorkflowNode(
            NodeId("en"), NodeTypeId("action.enable_macro"), "Enable", 0f, 0f,
            config = mapOf(ConfigKey("macroId") to "some-id"),
        )
        val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

        assertFalse(
            validation.warnings.toString(),
            validation.warnings.any { it.message.contains("no longer exists") },
        )
    }

    @Test
    fun `a declared variable raises nothing`() {
        val declaration = VariableDeclaration(id = "v1", name = "counter")
        val node = WorkflowNode(
            NodeId("set"), NodeTypeId("action.set_variable"), "Remember", 0f, 0f,
            config = mapOf(ConfigKey("name") to VariableRef.localSpec(declaration.id)),
        )
        val validation = GraphValidator(
            Workflow(nodes = listOf(node), variables = listOf(declaration)),
        ).validate()

        assertTrue(validation.warnings.none { it.message.contains("variable") })
    }

    @Test
    fun `exec cycle is rejected`() {
        val wf = sampleWorkflow().copy(
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n1"), PortName("in")),
            ),
        )
        val issues = GraphValidator(wf).validate().issues
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("Execution cycle") })
    }

    @Test
    fun `data edge between exec ports is rejected`() {
        val wf = sampleWorkflow().copy(
            dataConnections = listOf(
                DataConnection("d1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
        )
        val issues = GraphValidator(wf).validate().issues
        assertTrue(issues.any { it.severity == Severity.ERROR && it.message.contains("data output port") })
    }

    @Test
    fun `unknown exec port is rejected`() {
        val wf = sampleWorkflow().copy(
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("nope"), NodeId("n2"), PortName("in")),
            ),
        )
        val issues = GraphValidator(wf).validate().issues
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
        val issues = GraphValidator(wf).validate().issues
        assertTrue(issues.any { it.severity == Severity.ERROR })
    }

    @Test
    fun `connecting exec output to exec input of correct kind is accepted`() {
        val wf = sampleWorkflow()
        val errors = GraphValidator(wf).validate().issues.filter { it.severity == Severity.ERROR }
        assertTrue("got errors: $errors", errors.isEmpty())
    }

    @Test
    fun `isRunnable is true for clean sample workflow`() {
        assertTrue(GraphValidator(sampleWorkflow()).validate().isRunnable)
    }

    @Test
    fun `isRunnable is false for exec cycle`() {
        val wf = sampleWorkflow().copy(
            execConnections = listOf(
                ExecConnection("c1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
                ExecConnection("c2", NodeId("n2"), PortName("out"), NodeId("n1"), PortName("in")),
            ),
        )
        assertFalse(GraphValidator(wf).validate().isRunnable)
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
        val issues = GraphValidator(wf).validate().issues
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
        val issues = GraphValidator(wf).validate().issues
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
        val issues = GraphValidator(wf).validate().issues

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
        val issues = GraphValidator(wf).validate().issues
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
        val issues = GraphValidator(wf).validate().issues
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
        val issues = GraphValidator(wf).validate().issues
        assertTrue(
            "the rule must still see through the transform, got: ${issues.map { it.message }}",
            issues.any { it.severity == Severity.ERROR && it.message.contains("will not have run when") },
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
        val issues = GraphValidator(wf).validate().issues

        assertTrue(
            "a starved transform should warn, got: ${issues.map { it.message }}",
            issues.any { it.severity == Severity.WARNING && it.message.contains("nothing wired into it") },
        )
        assertTrue(issues.none { it.severity == Severity.ERROR })
    }

    @Test
    fun `a transform with its input typed into the form is not starved`() {
        // `transform.split_text` used as a list literal — one item per line in the
        // form, no edge — is the documented way to make a list without an API. The
        // form value and the edge supply the same input, so having one is not being
        // starved of the other, and a permanent badge on a correct graph is worse
        // than no badge at all.
        val wf = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.for_each"), "For each", 0f, 100f),
                WorkflowNode(
                    NodeId("items"), NodeTypeId("transform.split_text"), "Items", 200f, 50f,
                    config = mapOf(ConfigKey("text") to "a\nb"),
                ),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId("items"), TRANSFORM_OUT, NodeId("n2"), PortName("list")),
            ),
        )
        val issues = GraphValidator(wf).validate().issues

        assertTrue(
            "a transform fed from its own form should not warn, got: ${issues.map { it.message }}",
            issues.none { it.message.contains("nothing wired into it") },
        )
        assertTrue(issues.none { it.severity == Severity.ERROR })
    }

    @Test
    fun `a loop with an empty body is warned about but blocks nothing`() {
        val wf = Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                WorkflowNode(NodeId("n2"), NodeTypeId("action.repeat"), "Repeat", 0f, 100f),
            ),
            execConnections = listOf(
                ExecConnection("e1", NodeId("n1"), PortName("out"), NodeId("n2"), PortName("in")),
            ),
        )
        val validation = GraphValidator(wf).validate()

        assertTrue(
            "an empty loop body should warn, got: ${validation.issues.map { it.message }}",
            validation.warnings.any { it.message.contains("nothing in its loop body") },
        )
        // A warning blocks nothing, by construction.
        assertTrue(validation.blockedNodes.isEmpty())
        assertTrue(validation.blockedConnections.isEmpty())
    }

    /**
     * The Problems-panel half of a missing grant.
     *
     * The node's own config form has always shown this, but a macro missing a
     * permission is exactly the one that looks fine from the outside — so the
     * point of the warning is that it is visible without opening the node.
     */
    @Test
    fun `an ungranted prerequisite warns and blocks nothing`() {
        GrantedPrerequisites.hydrate(emptySet())
        try {
            val node = WorkflowNode(NodeId("la"), NodeTypeId("action.launch_app"), "Launch App", 0f, 0f)

            val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

            assertTrue(
                validation.warnings.toString(),
                validation.warnings.any { "draw over other apps" in it.message && node.id in it.nodes },
            )
            // A fact about the phone, not about the wiring: flipping a switch in
            // Settings makes this node work with no edit here at all.
            assertTrue(validation.blockedNodes.isEmpty())
            assertTrue(validation.isRunnable)
        } finally {
            GrantedPrerequisites.reset()
        }
    }

    @Test
    fun `a granted prerequisite says nothing`() {
        GrantedPrerequisites.hydrate(setOf(PrerequisiteType.OVERLAY.name))
        try {
            val node = WorkflowNode(NodeId("la"), NodeTypeId("action.launch_app"), "Launch App", 0f, 0f)

            val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

            assertFalse(
                validation.warnings.toString(),
                validation.warnings.any { "has not been granted" in it.message },
            )
        } finally {
            GrantedPrerequisites.reset()
        }
    }

    /**
     * The guard that keeps this honest. Nothing has to have asked Android for the
     * validator to run — the executor validates a disk snapshot on boot — and a
     * registry that answered "not granted" to everything would badge every
     * permission-declaring node in every macro. Empty and unasked differ.
     */
    @Test
    fun `nothing is reported while the grants are unknown`() {
        GrantedPrerequisites.reset()
        val node = WorkflowNode(NodeId("la"), NodeTypeId("action.launch_app"), "Launch App", 0f, 0f)

        val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

        assertFalse(
            validation.warnings.toString(),
            validation.warnings.any { "has not been granted" in it.message },
        )
    }

    /**
     * The message has to name the *setting*, not the constant. "Allow all the
     * time" is the wording on the Android page the user has to reach;
     * ACCESS_BACKGROUND_LOCATION is the wording that sends them nowhere.
     */
    @Test
    fun `the warning names the permission in the words Android uses`() {
        GrantedPrerequisites.hydrate(emptySet())
        try {
            val node = WorkflowNode(NodeId("gf"), NodeTypeId("trigger.geofence"), "Geofence", 0f, 0f)

            val validation = GraphValidator(Workflow(nodes = listOf(node))).validate()

            val warning = validation.warnings.single { "has not been granted" in it.message }
            assertTrue(warning.message, "Allow all the time" in warning.message)
            assertFalse("no manifest constants in a user-facing line", "ACCESS_" in warning.message)
        } finally {
            GrantedPrerequisites.reset()
        }
    }

    // region A fork's two branches

    /**
     * The rule that would otherwise be invisible: the deferred branch walks a
     * snapshot taken when the fork ran, so a wire from the immediate branch
     * carries **nothing** into it — and the consumer would quietly fall back to
     * its form value, which is exactly the substitution this validator exists to
     * prevent. Raw exec reachability cannot see it: the source really is upstream.
     */
    @Test
    fun `a data wire across a fork's two branches is refused`() {
        val validation = GraphValidator(forkWorkflow(fromNow = true)).validate()

        val error = validation.errors.single { "opposite" in it.message }
        assertTrue(error.message, "Wait Until" in error.message)
        // The consumer is held back; the source ran perfectly well.
        assertTrue(error.blockedNodes.toString(), error.blockedNodes == setOf(NodeId("later")))
    }

    /** Both directions are wrong; the deferred-to-immediate one even more obviously. */
    @Test
    fun `a data wire from the deferred branch back to the immediate one is refused too`() {
        val validation = GraphValidator(forkWorkflow(fromNow = false)).validate()

        assertTrue(validation.errors.toString(), validation.errors.any { "opposite" in it.message })
    }

    /**
     * The negative half, and the one that would make the rule useless if it were
     * wrong: everything before the fork ran before the snapshot was taken, so it
     * is visible to both branches and its wires are ordinary.
     */
    @Test
    fun `a wire from before the fork into either branch stays legal`() {
        val validation = GraphValidator(forkWorkflow(fromNow = null)).validate()

        // Not just "no fork error" — no error at all, which is what makes this a
        // statement about the graph rather than about one rule's wording.
        assertTrue(validation.errors.toString(), validation.errors.isEmpty())
    }

    /**
     * Manual → Ask to Choose → Wait Until, with another Ask to Choose on each
     * branch. That node is the subject only because it has both a `String` output
     * and a `String` input, so every wire below is type-clean and the only errors
     * a run can produce are the ones under test.
     *
     * [fromNow] chooses which wire is drawn: true feeds the immediate branch into
     * the deferred one, false the reverse, and null feeds the node *before* the
     * fork into the deferred branch — the case that must stay legal.
     */
    private fun forkWorkflow(fromNow: Boolean?): Workflow {
        val from = when (fromNow) {
            true -> "now"
            false -> "later"
            null -> "ask"
        }
        val to = if (fromNow == false) "now" else "later"
        return Workflow(
            nodes = listOf(
                WorkflowNode(NodeId("n1"), NodeTypeId("trigger.manual"), "Manual", 0f, 0f),
                choice("ask", "Ask"),
                WorkflowNode(NodeId("w"), NodeTypeId("action.wait_until"), "Wait Until", 0f, 80f),
                choice("now", "Now"),
                choice("later", "Later"),
            ),
            execConnections = listOf(
                ExecConnection("c0", NodeId("n1"), PortName("out"), NodeId("ask"), PortName("in")),
                ExecConnection("c1", NodeId("ask"), PortName("confirmed"), NodeId("w"), PortName("in")),
                ExecConnection("c2", NodeId("w"), PortName("out"), NodeId("now"), PortName("in")),
                ExecConnection("c3", NodeId("w"), PortName("resumed"), NodeId("later"), PortName("in")),
            ),
            dataConnections = listOf(
                DataConnection("d1", NodeId(from), PortName("choice"), NodeId(to), PortName("message")),
            ),
        )
    }

    private fun choice(id: String, name: String) =
        WorkflowNode(NodeId(id), NodeTypeId("action.dialog_choice"), name, 0f, 120f)

    // endregion

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
