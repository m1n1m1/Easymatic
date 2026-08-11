package com.example.ottomatic.domain.registry

import com.example.ottomatic.core.model.PortName
import com.example.ottomatic.core.model.NodeTypeId
import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.NodeTypeDefinition
import com.example.ottomatic.domain.model.Port
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.execOut
import com.example.ottomatic.domain.model.schema.ItemSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's own nodes, put through the same rule set a third party's are.
 *
 * [NodeDeclarationRules] exists because a plugin's declaration arrives at runtime,
 * where `NodeDeclarationContractTest` cannot reach it — but a rule set only ever
 * used against foreign input is one that drifts away from what the app itself does.
 * This is the other half of that arrangement: every built-in node is held to the
 * extracted rules, so the day one of them stops describing this codebase, this fails
 * rather than the plugin API quietly becoming stricter than the app.
 *
 * `NodeDeclarationContractTest` still carries the checks needing more context than a
 * [NodeTypeDefinition] has — the adaptive-port retyping, which resolves through
 * `effectivePorts` and a real `Workflow`, and the wired-property-to-port mapping,
 * which needs `NodeSchema.wiredPorts` to tell a config-derived port from a node's own.
 */
class NodeDeclarationRulesTest {

    private val allNodes: List<Pair<NodeTypeDefinition, NodeConfigSchema?>> =
        NodeTypeRegistry.all.map { it to ConfigSchemaRegistry.byId(it.typeId) }

    private val stringOut = Port(
        name = PortName("value"),
        kind = PortKind.DATA,
        direction = Direction.OUT,
        schema = ItemSchema.Primitive(String::class),
    )

    @Test
    fun `every registered node satisfies the shared declaration rules`() {
        val problems = allNodes.flatMap { (definition, config) ->
            NodeDeclarationRules.problems(definition, config).map { "${definition.typeId.value} $it" }
        }

        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `the rules are actually looking at something`() {
        // Guards this test's own failure mode: an empty registry, or a `problems` that
        // returned early, would satisfy the assertion above in silence.
        assertTrue("only ${allNodes.size} nodes registered", allNodes.size > 100)
        assertTrue(allNodes.any { (_, config) -> config != null })
        assertTrue(allNodes.any { (definition, _) -> definition.kind == NodeKind.VALUE })
        assertTrue(allNodes.any { (definition, _) -> definition.kind == NodeKind.TRANSFORM })
    }

    @Test
    fun `the rules reject a value node that sits on the execution wire`() {
        // A rule nothing exercises is a rule that can rot into always-true. This is the
        // pull side's central contract: a value is read outside the execution order, so
        // it can have no position in it.
        val broken = definition(NodeKind.VALUE, NodeCategory.VALUE_DEVICE, listOf(execOut(), stringOut))

        assertTrue(NodeDeclarationRules.problems(broken, null).any { it.contains("cannot have execution ports") })
    }

    @Test
    fun `the rules reject a data port with no schema`() {
        // Unchecked, every edge into it is accepted and nothing downstream ever narrows.
        val broken = definition(
            NodeKind.VALUE,
            NodeCategory.VALUE_DEVICE,
            listOf(stringOut.copy(schema = null)),
        )

        assertTrue(NodeDeclarationRules.problems(broken, null).any { it.contains("with no schema") })
    }

    @Test
    fun `the rules reject a transform that requires a permission`() {
        val broken = definition(
            NodeKind.TRANSFORM,
            NodeCategory.TRANSFORM_DATA,
            listOf(stringOut.copy(name = PortName("in"), direction = Direction.IN), stringOut),
        ).copy(
            permissionRequirements = listOf(
                com.example.ottomatic.core.permissions.PermissionRequirement(
                    manifestPermission = "android.permission.CAMERA",
                    type = com.example.ottomatic.core.permissions.PrerequisiteType.RUNTIME,
                ),
            ),
        )

        assertTrue(NodeDeclarationRules.problems(broken, null).any { it.contains("cannot require a permission") })
    }

    private fun definition(kind: NodeKind, category: NodeCategory, ports: List<Port>) = NodeTypeDefinition(
        typeId = NodeTypeId("test.broken"),
        displayName = "Broken",
        description = "",
        kind = kind,
        category = category,
        ports = ports,
        icon = NodeIcon.BOLT,
    )
}
