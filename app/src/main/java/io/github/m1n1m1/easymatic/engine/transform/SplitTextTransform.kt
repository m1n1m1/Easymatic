package io.github.m1n1m1.easymatic.engine.transform

import io.github.m1n1m1.easymatic.domain.model.NodeCategory
import io.github.m1n1m1.easymatic.domain.model.NodeIcon
import io.github.m1n1m1.easymatic.domain.model.config.Hint
import io.github.m1n1m1.easymatic.domain.model.config.Label
import io.github.m1n1m1.easymatic.domain.model.config.Multiline
import io.github.m1n1m1.easymatic.domain.model.config.Wired
import io.github.m1n1m1.easymatic.domain.model.dataOut
import io.github.m1n1m1.easymatic.engine.ExecutionContext
import io.github.m1n1m1.easymatic.engine.TransformNode
import io.github.m1n1m1.easymatic.engine.transformNode
import kotlinx.serialization.Serializable

/**
 * Config for `transform.split_text`.
 *
 * [text] is `@Wired` *and* `@Multiline`, which is what lets one node do two jobs:
 * type one item per line into the form and it is a **list literal**, wire an SMS
 * body or an HTTP response into it and it splits that instead. A separate
 * "Make a list" node would be the same node with the socket turned off.
 *
 * A blank [separator] means a line break, so the form's default behaviour matches
 * what the multiline field looks like.
 */
@Serializable
data class SplitTextConfig(
    @Label("Text")
    @Hint("one item per line, or wire something in")
    @Multiline @Wired val text: String = "",
    @Label("Separator")
    @Hint("blank for a new line")
    val separator: String = "",
)

/**
 * `transform.split_text` — text into a list of text.
 *
 * The everyday way to get a list without an API: `a, b, c` typed into a field, or
 * the lines of a message that arrived. n8n calls the same idea Split Out; the
 * inverse is `transform.list_join`.
 *
 * Blank entries are dropped and each item is trimmed, because `a, b, c` and
 * `a,b,c` mean the same thing to whoever typed them, and a trailing separator is a
 * typo rather than a request for an empty item.
 */
class SplitTextTransform : TransformNode<SplitTextConfig, List<String>> {

    override val definition = transformNode<SplitTextConfig, List<String>>(
        typeId = "transform.split_text",
        displayName = "Split text into a list",
        description = "Splits text into a list — one item per line, or on a separator you choose",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.LIST,
        output = dataOut<List<String>>("value", label = "List"),
    )

    override suspend fun transform(config: SplitTextConfig, context: ExecutionContext): List<String> {
        val separator = config.separator.ifEmpty { "\n" }
        return config.text.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
