package com.example.ottomatic.engine.transform

import com.example.ottomatic.domain.model.NodeCategory
import com.example.ottomatic.domain.model.NodeIcon
import com.example.ottomatic.domain.model.config.Hint
import com.example.ottomatic.domain.model.config.Label
import com.example.ottomatic.domain.model.config.Multiline
import com.example.ottomatic.domain.model.config.Wired
import com.example.ottomatic.domain.model.dataOut
import com.example.ottomatic.domain.registry.TEXT_TYPE_ID
import com.example.ottomatic.engine.ExecutionContext
import com.example.ottomatic.engine.TransformNode
import com.example.ottomatic.engine.transformNode
import kotlinx.serialization.Serializable

/**
 * Config for `transform.text`.
 *
 * [a], [b] and [c] are `@Wired`, so each is either typed into the form as a
 * literal or fed by an incoming value. They are `String` because the template
 * builds text; anything else connected to them picks up a `transform.convert` on
 * the way in, which is visible on the canvas.
 */
@Serializable
data class BuildTextConfig(
    @Label("Template")
    @Hint("use {A}, {B}, {C}")
    @Multiline val template: String = "",
    @Label("A") @Wired val a: String = "",
    @Label("B") @Wired val b: String = "",
    @Label("C") @Wired val c: String = "",
)

/**
 * `transform.text` — combines fixed words with up to three values.
 *
 * This is what turns a bare `43` into "Battery is 43%". Wiring a value straight
 * into a notification can only ever send the value alone; the template is the
 * place where the sentence around it lives.
 *
 * A placeholder with nothing wired or typed into it becomes empty text rather than
 * being left in the output — a half-filled template should read as a short
 * sentence, not as `Battery is {B}%`.
 */
class BuildTextTransform : TransformNode<BuildTextConfig, String> {

    override val definition = transformNode<BuildTextConfig, String>(
        typeId = TEXT_TYPE_ID.value,
        displayName = "Build text",
        description = "Combines words and values into one piece of text",
        category = NodeCategory.TRANSFORM_DATA,
        icon = NodeIcon.TEXT,
        output = dataOut<String>("text", label = "Text"),
    )

    override suspend fun transform(config: BuildTextConfig, context: ExecutionContext): String =
        SLOT.replace(config.template) { match ->
            when (match.groupValues[1].uppercase()) {
                "A" -> config.a
                "B" -> config.b
                else -> config.c
            }
        }

    private companion object {
        /**
         * `{A}`, `{b}`, `{ C }` — case and surrounding spaces are all forgiven.
         *
         * The closing brace must be escaped too. OpenJDK tolerates a bare `}`, so a
         * JVM unit test passes, but Android's ICU regex engine rejects it and throws
         * from this initializer — which took the whole node palette down with it.
         */
        val SLOT = Regex("""\{\s*([ABCabc])\s*\}""")
    }
}
