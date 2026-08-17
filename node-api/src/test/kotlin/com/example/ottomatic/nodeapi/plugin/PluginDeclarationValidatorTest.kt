package com.example.ottomatic.nodeapi.plugin

import com.example.ottomatic.domain.model.Direction
import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.NodeKind
import com.example.ottomatic.domain.model.PortKind
import com.example.ottomatic.domain.model.config.ChoiceChooser
import com.example.ottomatic.domain.registry.ConfigFieldType
import com.example.ottomatic.nodeapi.wire.ConfigFieldTypeWire
import com.example.ottomatic.nodeapi.wire.ConfigFieldWire
import com.example.ottomatic.nodeapi.wire.ExecOutputsWire
import com.example.ottomatic.nodeapi.wire.NodeDeclarationWire
import com.example.ottomatic.nodeapi.wire.OptionWire
import com.example.ottomatic.nodeapi.wire.PLUGIN_PROTOCOL_VERSION
import com.example.ottomatic.nodeapi.wire.PluginManifestWire
import com.example.ottomatic.nodeapi.wire.PortWire
import com.example.ottomatic.nodeapi.wire.PrimitiveWire
import com.example.ottomatic.nodeapi.wire.RouteWire
import com.example.ottomatic.nodeapi.wire.SchemaWire
import com.example.ottomatic.nodeapi.wire.VisibilityWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plugin's manifest is untrusted input, and the failure mode to design against is
 * not a crash but a node that *looks* fine and does nothing. So every rejection here
 * is asserted to be a rejection of one node with a reason attached, and the two
 * whole-document refusals are asserted to be exactly two.
 */
class PluginDeclarationValidatorTest {

    private val pkg = "com.acme.tools"
    private val prefix = PluginLimits.typeIdPrefix(pkg)
    private val text = SchemaWire.Primitive(PrimitiveWire.TEXT)

    /**
     * A well-formed declaration. Tests that need a different name, icon or execution
     * shape use `.copy()`, which keeps this from growing a parameter per field.
     */
    private fun action(
        typeId: String = "${prefix}shout",
        kind: NodeKind = NodeKind.ACTION,
        dataPorts: List<PortWire> = listOf(PortWire("said", Direction.OUT, text)),
        config: List<ConfigFieldWire> = emptyList(),
    ) = NodeDeclarationWire(
        typeId = typeId,
        displayName = "Shout",
        description = "Shouts something",
        kind = kind,
        icon = NodeIcon.SEND.name,
        dataPorts = dataPorts,
        config = config,
    )

    private fun validate(vararg nodes: NodeDeclarationWire) =
        PluginDeclarationValidator.validate(PluginManifestWire(nodes = nodes.toList()), pkg)

    private fun rejectionFor(node: NodeDeclarationWire): String {
        val result = validate(node)
        assertTrue("expected ${node.typeId} to be rejected, but it was accepted", result.accepted.isEmpty())
        assertEquals(1, result.rejected.size)
        return result.rejected.single().reason
    }

    // ---- the happy path, and what it produces -------------------------------

    @Test
    fun `a well-formed action is accepted`() {
        val result = validate(action())

        assertNull(result.fatal)
        assertTrue(result.rejected.isEmpty())
        assertEquals("${prefix}shout", result.accepted.single().definition.typeId.value)
    }

    @Test
    fun `an action gets its execution ports derived rather than declared`() {
        val ports = validate(action()).accepted.single().definition.ports

        val exec = ports.filter { it.kind == PortKind.EXECUTION }
        assertEquals(listOf("in", "out"), exec.map { it.name.value })
        assertEquals(listOf(Direction.IN, Direction.OUT), exec.map { it.direction })
    }

    @Test
    fun `a branching action gets a true and a false`() {
        val ports = validate(action().copy(execOutputs = ExecOutputsWire.Branch)).accepted.single().definition.ports

        assertEquals(
            listOf("in", "true", "false"),
            ports.filter { it.kind == PortKind.EXECUTION }.map { it.name.value },
        )
    }

    // ---- named execution routes ---------------------------------------------

    @Test
    fun `named routes become execution ports in declaration order, with their labels`() {
        val node = action().copy(
            execOutputs = ExecOutputsWire.Named(
                listOf(RouteWire("out", "When posted"), RouteWire("error", "When it fails")),
            ),
        )

        val exec = validate(node).accepted.single().definition.ports.filter { it.kind == PortKind.EXECUTION }

        assertEquals(listOf("in", "out", "error"), exec.map { it.name.value })
        assertEquals(listOf("in", "When posted", "When it fails"), exec.map { it.label })
    }

    /**
     * Order is not cosmetic here.
     *
     * `PluginNodeRunner` lands both an undeclared route and an *unreachable plugin* on the
     * first entry of `routesFor`, so if the derivation reordered them a failed binder call
     * would pulse whichever port happened to come first.
     */
    @Test
    fun `the first named route is the first route the host will fall back to`() {
        val node = action().copy(
            execOutputs = ExecOutputsWire.Named(
                listOf(RouteWire("published"), RouteWire("rejected")),
            ),
        )

        assertEquals("published", routesFor(validate(node).accepted.single().declaration).first())
    }

    @Test
    fun `a route with a blank name is refused`() {
        val node = action().copy(execOutputs = ExecOutputsWire.Named(listOf(RouteWire(""))))

        assertTrue(rejectionFor(node).contains("blank name"))
    }

    @Test
    fun `two routes with the same name are refused`() {
        val node = action().copy(
            execOutputs = ExecOutputsWire.Named(listOf(RouteWire("out"), RouteWire("out"))),
        )

        assertTrue(rejectionFor(node).contains("more than once"))
    }

    /**
     * `in` is what the way *into* an action is called, so a route by that name would
     * derive a second port with the same name and read as a duplicate — a sentence that
     * points at the wrong thing entirely.
     */
    @Test
    fun `a route named in is refused, naming the collision`() {
        val node = action().copy(
            execOutputs = ExecOutputsWire.Named(listOf(RouteWire("out"), RouteWire("in"))),
        )

        assertTrue(rejectionFor(node).contains("way *into* an action"))
    }

    @Test
    fun `more routes than the cap are refused`() {
        val node = action().copy(
            execOutputs = ExecOutputsWire.Named(
                (0..PluginLimits.MAX_ROUTES_PER_NODE).map { RouteWire("r$it") },
            ),
        )

        assertTrue(rejectionFor(node).contains("${PluginLimits.MAX_ROUTES_PER_NODE}"))
    }

    @Test
    fun `naming no routes at all is refused`() {
        val node = action().copy(execOutputs = ExecOutputsWire.Named(emptyList()))

        assertTrue(rejectionFor(node).contains("somewhere to continue from"))
    }

    // ---- plugin-owned choices -----------------------------------------------

    @Test
    fun `a choice field becomes a PLUGIN_CHOICE with its provider stamped on`() {
        val node = action(
            config = listOf(ConfigFieldWire("board", "Board", ConfigFieldTypeWire.ChoiceOf("boards"))),
        )

        val accepted = validate(node).accepted.single()
        val field = requireNotNull(accepted.configSchema).fields.single()
        val type = field.type as ConfigFieldType.PLUGIN_CHOICE

        assertEquals("boards", type.source)
        // Never read off the wire: the host stamps the typeId it already resolved and
        // namespaced, so a plugin cannot point a chooser at somebody else's node.
        assertEquals("${prefix}shout", type.providerTypeId)
    }

    /**
     * The rendering choice has to survive the crossing, because it is the only thing that
     * tells the editor whether to draw a list or open somebody else's Activity — and a
     * `SCREEN` field silently read back as `LIST` would open an empty overlay instead.
     */
    @Test
    fun `a choice field carries which chooser draws it`() {
        val node = action(
            config = listOf(
                ConfigFieldWire("board", "Board", ConfigFieldTypeWire.ChoiceOf("boards")),
                ConfigFieldWire(
                    "card",
                    "Card",
                    ConfigFieldTypeWire.ChoiceOf("cards", listOf("board"), ChoiceChooser.SCREEN),
                ),
            ),
        )

        val fields = requireNotNull(validate(node).accepted.single().configSchema).fields
        val board = fields.single { it.key.value == "board" }.type as ConfigFieldType.PLUGIN_CHOICE
        val card = fields.single { it.key.value == "card" }.type as ConfigFieldType.PLUGIN_CHOICE

        // The default, so an existing declaration keeps behaving as it did.
        assertEquals(ChoiceChooser.LIST, board.chooser)
        assertEquals(ChoiceChooser.SCREEN, card.chooser)
        // Everything else about the field is unchanged by the rendering choice.
        assertEquals(listOf("board"), card.scopedBy)
        assertEquals("${prefix}shout", card.providerTypeId)
    }

    @Test
    fun `a choice field with a blank source is refused`() {
        val node = action(
            config = listOf(ConfigFieldWire("board", "Board", ConfigFieldTypeWire.ChoiceOf(""))),
        )

        assertTrue(rejectionFor(node).contains("blank source"))
    }

    /**
     * A scope naming nothing narrows on a value nothing can set, so the chooser answers
     * the same empty list forever — indistinguishable from a plugin that genuinely has
     * nothing to offer.
     */
    @Test
    fun `a choice scoped by a field the node does not declare is refused`() {
        val node = action(
            config = listOf(
                ConfigFieldWire("board", "Board", ConfigFieldTypeWire.ChoiceOf("boards", listOf("space"))),
            ),
        )

        assertTrue(rejectionFor(node).contains("scoped by 'space'"))
    }

    @Test
    fun `a choice scoped by a sibling it does declare is accepted`() {
        val node = action(
            config = listOf(
                ConfigFieldWire("space", "Space", ConfigFieldTypeWire.ChoiceOf("spaces")),
                ConfigFieldWire("board", "Board", ConfigFieldTypeWire.ChoiceOf("boards", listOf("space"))),
            ),
        )

        assertTrue(validate(node).rejected.isEmpty())
    }

    // ---- choosers another app draws -----------------------------------------

    /**
     * The declaration crosses whole, because the host builds the launch from it and nothing
     * else. A field silently losing its `resultExtra` would open a scanner, read the wrong
     * place and store nothing — which reads as the scanner having failed.
     */
    @Test
    fun `an intent choice field becomes an INTENT_CHOICE carrying the whole request`() {
        val node = action(
            config = listOf(
                ConfigFieldWire(
                    "report",
                    "Report",
                    ConfigFieldTypeWire.IntentChoiceOf(
                        action = "android.intent.action.CREATE_DOCUMENT",
                        mimeType = "text/csv",
                        category = "android.intent.category.OPENABLE",
                        inputExtras = listOf("android.intent.extra.TITLE=report.csv"),
                        icon = NodeIcon.FILE.name,
                    ),
                ),
            ),
        )

        val field = requireNotNull(validate(node).accepted.single().configSchema).fields.single()
        val type = field.type as ConfigFieldType.INTENT_CHOICE

        assertEquals("android.intent.action.CREATE_DOCUMENT", type.action)
        assertEquals("text/csv", type.mimeType)
        assertEquals("android.intent.category.OPENABLE", type.category)
        assertEquals(listOf("android.intent.extra.TITLE=report.csv"), type.inputExtras)
        assertEquals(NodeIcon.FILE, type.icon)
    }

    /**
     * The other half of the pair: an answer that lives in an extra rather than in the
     * result's own data, which is the only reason `resultExtra` exists.
     */
    @Test
    fun `an intent choice may name the extra its answer arrives in`() {
        val node = action(
            config = listOf(
                ConfigFieldWire(
                    "chime",
                    "Chime",
                    ConfigFieldTypeWire.IntentChoiceOf(
                        action = "android.intent.action.RINGTONE_PICKER",
                        resultExtra = "android.intent.extra.ringtone.PICKED_URI",
                        icon = NodeIcon.MUSIC.name,
                    ),
                ),
            ),
        )

        val field = requireNotNull(validate(node).accepted.single().configSchema).fields.single()
        val type = field.type as ConfigFieldType.INTENT_CHOICE

        assertEquals("android.intent.extra.ringtone.PICKED_URI", type.resultExtra)
        // Blank rather than absent: an untyped action is ordinary, not a declaration that
        // forgot something, so nothing here defaults it to a wildcard.
        assertEquals("", type.mimeType)
        assertEquals(emptyList<String>(), type.inputExtras)
    }

    /**
     * An unknown icon is cosmetic and must never cost the node — still less the manifest.
     *
     * The reason this is sharper than `NodeDeclarationWire.icon`'s: a `NodeIcon` on the wire
     * would make a glyph added in a later version fail *this member's* decode, and the
     * manifest is one document, so every node the plugin declares would be rejected over a
     * picture on one chooser button.
     */
    @Test
    fun `an intent choice naming an icon this version has never heard of falls back`() {
        val node = action(
            config = listOf(
                ConfigFieldWire(
                    "code",
                    "Code",
                    ConfigFieldTypeWire.IntentChoiceOf(action = "some.ACTION", icon = "HOLOGRAM"),
                ),
            ),
        )

        val field = requireNotNull(validate(node).accepted.single().configSchema).fields.single()
        assertEquals(NodeIcon.BOLT, (field.type as ConfigFieldType.INTENT_CHOICE).icon)
    }

    /** A blank action resolves to no app at all, so the button reads as dead. */
    @Test
    fun `an intent choice with a blank action is refused`() {
        val node = action(
            config = listOf(ConfigFieldWire("code", "Code", ConfigFieldTypeWire.IntentChoiceOf(action = ""))),
        )

        assertTrue(rejectionFor(node).contains("blank action"))
    }

    /**
     * An extra with no `=` is a key the asked-for app never receives — a scanner left in the
     * wrong mode, a camera left with no destination — and nothing anywhere would say so.
     */
    @Test
    fun `an intent choice with a malformed input extra is refused`() {
        val node = action(
            config = listOf(
                ConfigFieldWire(
                    "code",
                    "Code",
                    ConfigFieldTypeWire.IntentChoiceOf(action = "some.ACTION", inputExtras = listOf("SCAN_MODE")),
                ),
            ),
        )

        assertTrue(rejectionFor(node).contains("SCAN_MODE"))
    }

    @Test
    fun `an intent choice with more extras than the limit is refused`() {
        val tooMany = (0..PluginLimits.MAX_INTENT_EXTRAS).map { "k$it=v$it" }
        val node = action(
            config = listOf(
                ConfigFieldWire(
                    "code",
                    "Code",
                    ConfigFieldTypeWire.IntentChoiceOf(action = "some.ACTION", inputExtras = tooMany),
                ),
            ),
        )

        assertTrue(rejectionFor(node).contains("extras on its intent"))
    }

    /**
     * A value that *may legitimately contain* `=` is not malformed.
     *
     * Base64 padding and query strings both do, and only the first `=` separates — so a
     * stricter split-on-every-`=` rule would refuse honest declarations.
     */
    @Test
    fun `an intent extra whose value contains an equals sign is accepted`() {
        val node = action(
            config = listOf(
                ConfigFieldWire(
                    "code",
                    "Code",
                    ConfigFieldTypeWire.IntentChoiceOf(action = "some.ACTION", inputExtras = listOf("q=a=b")),
                ),
            ),
        )

        assertTrue(validate(node).rejected.isEmpty())
    }

    @Test
    fun `a value gets no execution ports at all`() {
        val node = action(typeId = "${prefix}reading", kind = NodeKind.VALUE)

        val ports = validate(node).accepted.single().definition.ports

        assertTrue(ports.none { it.kind == PortKind.EXECUTION })
    }

    @Test
    fun `an accepted node is never adaptive`() {
        // Enforced rather than checked: `effectivePorts` resolves ports by walking the
        // host's graph, which a plugin cannot be handed.
        assertFalse(validate(action()).accepted.single().definition.hasDynamicPorts)
    }

    @Test
    fun `an accepted node declares no host permission requirement`() {
        val node = action().copy(permissions = listOf("android.permission.CAMERA"))

        val accepted = validate(node).accepted.single()

        // The permission stays on the declaration, to be checked against the plugin's
        // own package. It must not enter the host's permission catalogue.
        assertTrue(accepted.definition.permissionRequirements.isEmpty())
        assertEquals(listOf("android.permission.CAMERA"), accepted.declaration.permissions)
    }

    @Test
    fun `an accepted node lands in the placeholder plugin category`() {
        assertEquals(NodeCategory.PLUGIN_ACTION, validate(action()).accepted.single().definition.category)
    }

    @Test
    fun `an unknown icon falls back rather than costing the node`() {
        val accepted = validate(action().copy(icon = "SPARKLES_AND_UNICORNS")).accepted.single()

        assertEquals(NodeIcon.BOLT, accepted.definition.icon)
    }

    @Test
    fun `config fields become form fields`() {
        val node = action(
            config = listOf(
                ConfigFieldWire("volume", "Volume", ConfigFieldTypeWire.Int, "3"),
                ConfigFieldWire(
                    "tone",
                    "Tone",
                    ConfigFieldTypeWire.EnumOf(listOf(OptionWire("warm"), OptionWire("cold"))),
                    "warm",
                ),
            ),
        )

        val schema = requireNotNull(validate(node).accepted.single().configSchema)

        assertEquals(listOf("volume", "tone"), schema.fields.map { it.key.value })
    }

    // ---- whole-document refusals, of which there are exactly two ------------

    @Test
    fun `a manifest speaking another protocol is refused whole`() {
        val result = PluginDeclarationValidator.validate(
            PluginManifestWire(protocolVersion = PLUGIN_PROTOCOL_VERSION + 1, nodes = listOf(action())),
            pkg,
        )

        assertNotNull(result.fatal)
        assertTrue(result.accepted.isEmpty())
    }

    @Test
    fun `a manifest with more nodes than the cap is refused whole`() {
        val nodes = (0..PluginLimits.MAX_NODES_PER_PLUGIN).map { action(typeId = "${prefix}node$it") }

        val result = PluginDeclarationValidator.validate(PluginManifestWire(nodes = nodes), pkg)

        assertNotNull(result.fatal)
    }

    // ---- namespacing --------------------------------------------------------

    @Test
    fun `a typeId outside the plugin's own namespace is rejected`() {
        // The prefix is derived from PackageManager, so this is the case where a plugin
        // tried to claim someone else's — or the app's own — node.
        assertTrue(rejectionFor(action(typeId = "action.notify")).contains("not this plugin's"))
        assertTrue(rejectionFor(action(typeId = "plugin:com.evil/x")).contains("not this plugin's"))
    }

    @Test
    fun `a typeId that is only the prefix is rejected`() {
        assertTrue(rejectionFor(action(typeId = prefix)).contains("names nothing"))
    }

    @Test
    fun `a typeId longer than the cap is rejected`() {
        val long = prefix + "x".repeat(PluginLimits.MAX_TYPE_ID_LENGTH)

        assertTrue(rejectionFor(action(typeId = long)).contains("longer than"))
    }

    @Test
    fun `the same typeId twice keeps the first and rejects the second`() {
        val result = validate(action(), action().copy(displayName = "Shout again"))

        assertEquals(1, result.accepted.size)
        assertEquals("Shout", result.accepted.single().definition.displayName)
        assertTrue(result.rejected.single().reason.contains("more than once"))
    }

    // ---- the pull-side contract, per NodeDeclarationRules -------------------

    @Test
    fun `a value with a data input is rejected`() {
        val node = action(
            typeId = "${prefix}reading",
            kind = NodeKind.VALUE,
            dataPorts = listOf(
                PortWire("of", Direction.IN, text),
                PortWire("value", Direction.OUT, text),
            ),
        )

        assertTrue(rejectionFor(node).contains("cannot have data inputs"))
    }

    @Test
    fun `a value with two data outputs is rejected`() {
        val node = action(
            typeId = "${prefix}reading",
            kind = NodeKind.VALUE,
            dataPorts = listOf(PortWire("a", Direction.OUT, text), PortWire("b", Direction.OUT, text)),
        )

        assertTrue(rejectionFor(node).contains("exactly one data output"))
    }

    @Test
    fun `a transform with no data input is rejected`() {
        val node = action(typeId = "${prefix}shorten", kind = NodeKind.TRANSFORM)

        assertTrue(rejectionFor(node).contains("at least one data input"))
    }

    @Test
    fun `a transform with two data outputs is rejected`() {
        // The executor's pull memo is keyed by node, not by port, so a second output
        // could never be addressed.
        val node = action(
            typeId = "${prefix}shorten",
            kind = NodeKind.TRANSFORM,
            dataPorts = listOf(
                PortWire("in", Direction.IN, text),
                PortWire("a", Direction.OUT, text),
                PortWire("b", Direction.OUT, text),
            ),
        )

        assertTrue(rejectionFor(node).contains("exactly one data output"))
    }

    @Test
    fun `a trigger with a data input is rejected`() {
        val node = action(
            typeId = "${prefix}heard",
            kind = NodeKind.TRIGGER,
            dataPorts = listOf(PortWire("filter", Direction.IN, text), PortWire("said", Direction.OUT, text)),
        )

        assertTrue(rejectionFor(node).contains("cannot have data inputs"))
    }

    // ---- caps and malformed shapes -----------------------------------------

    @Test
    fun `a duplicate port name is rejected`() {
        val node = action(
            dataPorts = listOf(PortWire("said", Direction.OUT, text), PortWire("said", Direction.OUT, text)),
        )

        assertTrue(rejectionFor(node).contains("more than one"))
    }

    @Test
    fun `a port nested deeper than the cap is rejected`() {
        var schema: SchemaWire = text
        repeat(PluginLimits.MAX_SCHEMA_DEPTH) { schema = SchemaWire.ListOf(schema) }

        val node = action(dataPorts = listOf(PortWire("said", Direction.OUT, schema)))

        assertTrue(rejectionFor(node).contains("nests its type"))
    }

    @Test
    fun `more ports than the cap is rejected`() {
        val ports = (0..PluginLimits.MAX_PORTS_PER_NODE).map { PortWire("p$it", Direction.OUT, text) }

        assertTrue(rejectionFor(action(dataPorts = ports)).contains("data ports"))
    }

    @Test
    fun `more config fields than the cap is rejected`() {
        val fields = (0..PluginLimits.MAX_CONFIG_FIELDS_PER_NODE).map {
            ConfigFieldWire("f$it", "Field $it", ConfigFieldTypeWire.Str)
        }

        assertTrue(rejectionFor(action(config = fields)).contains("config fields"))
    }

    @Test
    fun `more enum options than the cap is rejected`() {
        val options = (0..PluginLimits.MAX_ENUM_OPTIONS).map { OptionWire("o$it") }
        val node = action(
            config = listOf(ConfigFieldWire("tone", "Tone", ConfigFieldTypeWire.EnumOf(options), "o0")),
        )

        assertTrue(rejectionFor(node).contains("choices"))
    }

    @Test
    fun `an over-long display name is rejected`() {
        val long = "x".repeat(PluginLimits.MAX_STRING_LENGTH + 1)

        assertTrue(rejectionFor(action().copy(displayName = long)).contains("longer than"))
    }

    @Test
    fun `a blank display name is rejected`() {
        assertTrue(rejectionFor(action().copy(displayName = "  ")).contains("blank name"))
    }

    @Test
    fun `a choice field defaulting to something it cannot be is rejected`() {
        val node = action(
            config = listOf(
                ConfigFieldWire(
                    "tone",
                    "Tone",
                    ConfigFieldTypeWire.EnumOf(listOf(OptionWire("warm"), OptionWire("cold"))),
                    defaultValue = "tepid",
                ),
            ),
        )

        assertTrue(rejectionFor(node).contains("not one of its options"))
    }

    @Test
    fun `a visibility rule naming a field that does not exist is rejected`() {
        val node = action(
            config = listOf(
                ConfigFieldWire(
                    "detail",
                    "Detail",
                    ConfigFieldTypeWire.Str,
                    visibleWhen = VisibilityWire("advanced", setOf("true")),
                ),
            ),
        )

        assertTrue(rejectionFor(node).contains("no such field"))
    }

    @Test
    fun `a visibility rule naming a value its controller can never hold is rejected`() {
        val node = action(
            config = listOf(
                ConfigFieldWire("advanced", "Advanced", ConfigFieldTypeWire.Bool, "false"),
                ConfigFieldWire(
                    "detail",
                    "Detail",
                    ConfigFieldTypeWire.Str,
                    visibleWhen = VisibilityWire("advanced", setOf("maybe")),
                ),
            ),
        )

        assertTrue(rejectionFor(node).contains("can never be"))
    }

    @Test
    fun `a duplicate config key is rejected`() {
        val node = action(
            config = listOf(
                ConfigFieldWire("tone", "Tone", ConfigFieldTypeWire.Str),
                ConfigFieldWire("tone", "Tone again", ConfigFieldTypeWire.Str),
            ),
        )

        assertTrue(rejectionFor(node).contains("more than one config field"))
    }

    @Test
    fun `one bad node costs its author one node and no more`() {
        // The quarantine doctrine, at the plugin boundary: the smallest broken thing.
        val result = validate(action(), action(typeId = "not.mine"))

        assertEquals(1, result.accepted.size)
        assertEquals(1, result.rejected.size)
        assertNull(result.fatal)
    }
}
