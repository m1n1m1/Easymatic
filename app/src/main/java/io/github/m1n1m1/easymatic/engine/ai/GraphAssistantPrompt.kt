package io.github.m1n1m1.easymatic.engine.ai

import io.github.m1n1m1.easymatic.domain.model.Workflow

/**
 * What the graph assistant is told before it is asked anything.
 *
 * **English, and a constant rather than a string resource**, on `NodeToolCatalog`'s
 * reasoning: this is read by a model rather than shown to a user, and `engine/` cannot
 * reach the translated node text anyway. Everything the *user* reads about a turn is
 * worded in `feature/`.
 *
 * The half that earns its length is the graph's own rules. A model that has seen a
 * hundred automation tools will assume a per-node condition setting, a linear list of
 * steps, and untyped wires — Easymatic has none of those, and each wrong assumption
 * costs a turn out of a cap. Stating them up front is cheaper than letting `validate`
 * discover them.
 */
object GraphAssistantPrompt {

    /** One thing already said in this conversation. */
    data class PriorTurn(val fromUser: Boolean, val text: String)

    /** The standing instruction, with the whole node palette appended. */
    fun instruction(): String = RULES + "\n\n" + NodeCatalog.index()

    /**
     * One message, with what was already said and what is already on the canvas.
     *
     * **The graph is included rather than left to `read_graph`.** It is needed on
     * essentially every turn, and a tool call to fetch what could have been sent costs a
     * round trip out of a capped budget. The tool still exists, for after the model has
     * changed something and wants to see the result.
     *
     * **The conversation is restated rather than threaded through the provider.**
     * `AiRequest` carries one prompt and `runToolExchange` seeds its exchange with a
     * single `Ask`; threading prior turns would mean touching `core`, `data` and all
     * three protocols for no gain, because the *graph* is the state and restating it is
     * exactly what stops the model acting on a stale picture of it.
     *
     * It lives here rather than beside the session for the reason the tool descriptions
     * do: this text is read by a model, and every string under `feature/` is one a person
     * reads and a translator owns.
     */
    fun turn(history: List<PriorTurn>, workflow: Workflow, message: String): String = buildString {
        if (history.isNotEmpty()) {
            appendLine("Earlier in this conversation:")
            history.forEach { entry ->
                appendLine(if (entry.fromUser) "  They asked: ${entry.text}" else "  You answered: ${entry.text}")
            }
            appendLine()
        }
        appendLine("The workflow as it stands:")
        appendLine(GraphEditTools.describeGraph(workflow))
        appendLine()
        appendLine("They now ask:")
        append(message)
    }

    private val RULES = """
        You are editing one Easymatic automation workflow. It is a node graph, and the user is
        looking at it right now — every change you make appears on their screen as you make it.

        How the graph works:
        - A run starts at a TRIGGER node and follows EXECUTION wires from one ACTION to the next.
          A workflow with no trigger can never run.
        - DATA wires carry a typed value from an output port to an input port. Types are strict:
          a whole number output does not fit a text input. Ask for the wire anyway and a Convert
          node is placed in between for you.
        - VALUE and TRANSFORM nodes have no execution ports at all. They are read on demand by
          whatever consumes them, so you wire only their data output and never put them in the
          execution order.
        - There is no per-node condition setting. "Only do this when X" is an action.if placed
          upstream, with its true and false execution ports wired to the two branches.
        - Some config fields say "chosen by the user". Those name something from the user's own
          library — a place, a sound, an app, an account — and cannot be typed in. Leave them
          blank and say in your answer which ones the user has to fill in.

        How to work:
        1. Unless you already know the graph is empty, read it before you change it.
        2. Call describe_node_type for every node type you are about to use. Never guess a port
           name or a config field name; the error you get back will cost you a turn.
        3. Add the nodes, then wire them, then set their config.
        4. New nodes are laid out for you, so leave them alone unless the arrangement is
           actually wrong. move_node is for tidying: putting an action.if's true branch on
           one side and its false branch on the other, keeping a branch in its own column,
           or laying out a workflow you were asked to clean up. Nodes you move are left
           where you put them.
        5. Call validate when you think you are done, and fix whatever it reports.
        6. Finish with one or two plain sentences saying what you built and what is left for the
           user to do. Do not list every tool call; they can see the graph.

        Never delete or rewire anything the user did not ask you to change.

        The node types available to you, as "typeId | kind | category | name — description":
    """.trimIndent()
}
