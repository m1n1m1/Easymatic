package com.example.ottomatic.domain.registry

import com.example.ottomatic.domain.model.VariableDeclaration

/**
 * The global variable declarations, as a lookup anything in `domain` may reach.
 *
 * A workflow's own variables travel in the workflow, so resolving one costs
 * nothing — [effectivePorts] and `GraphValidator` are both handed the graph
 * already. A *global* one has no such carrier, and the two things that need it are
 * the two that can least afford a dependency: [effectivePorts] is a pure function
 * called from Compose layout (`NodeCard`, `GraphCanvas` and `MarqueeSelection` all
 * call it while measuring, every frame), so it can have neither a suspension point
 * nor a repository, and `GraphValidator` runs on every keystroke in the editor.
 *
 * So the declarations are published here instead: `ServiceLocator` hydrates this
 * once from disk before anything can arm, and the globals editor re-hydrates on
 * every save so a card retypes itself the moment its variable does. It is the same
 * trade `VariableStore` already makes — an object, because it is reachable from
 * paths that are neither suspending nor injected.
 *
 * The alternative was threading a parameter through [effectivePorts], which has
 * four public entry points and around forty call sites between the app and its
 * tests, for the sake of avoiding one `@Volatile` field. The other alternative —
 * stamping the globals onto each [com.example.ottomatic.domain.model.Workflow] as
 * it is loaded — fails the way this codebase least likes: every producer that
 * forgot to stamp (the editor's blank-workflow fallback, every hand-built test
 * fixture) would resolve every global ref as deleted, silently and plausibly.
 */
object GlobalVariables {

    @Volatile
    private var current: List<VariableDeclaration> = emptyList()

    /** Every declared global, in the library's order. */
    val declarations: List<VariableDeclaration> get() = current

    /** The declaration [id] names, or null when it was deleted. */
    fun byId(id: String): VariableDeclaration? = current.firstOrNull { it.id == id }

    /** Publishes [declarations] as the current library. Called by `ServiceLocator` and the editor. */
    fun hydrate(declarations: List<VariableDeclaration>) {
        current = declarations
    }

    /** Drops every declaration. Test seam, mirroring `VariableStore.clear`; nothing in the app calls it. */
    internal fun reset() {
        current = emptyList()
    }
}
