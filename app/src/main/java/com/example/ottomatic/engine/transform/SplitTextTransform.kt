package com.example.ottomatic.engine.transform

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.TransformNode
import com.example.ottomatic.engine.transformNode
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
    @Label("Text — one item per line, or wire something in") @Multiline @Wired val text: String = "",
    @Label("Separator (blank for a new line)") val separator: String = "",
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
