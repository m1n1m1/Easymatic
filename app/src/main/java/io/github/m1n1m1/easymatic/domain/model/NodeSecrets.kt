package io.github.m1n1m1.easymatic.domain.model

import io.github.m1n1m1.easymatic.core.model.ConfigKey
import io.github.m1n1m1.easymatic.domain.registry.API_TOKEN_KEY
import io.github.m1n1m1.easymatic.domain.registry.API_TRIGGER_TYPE_ID

/**
 * The config values that are credentials rather than settings, and the two things
 * that ever have to be done to one: mint a fresh one, or leave it behind.
 *
 * There is exactly one such value today. A `trigger.api` node's token is a bearer
 * credential — [ApiTokens]' own KDoc calls it "the only credential the Intent front
 * door has", because a broadcast arrives carrying no sender identity whatsoever.
 * Everything else a workflow can hold is a *reference* into a library whose secret
 * stays sealed under the Keystore, which is why this file names one key and not a
 * category.
 *
 * It lives in `domain` rather than beside the editor's duplicate command, where
 * [reissuedConfig] started, because the same rule now has three callers on two sides
 * of the dependency graph: duplicating a node, importing a macro, and exporting one.
 * `feature` may depend on `domain` and not the reverse, so the shared half has to be
 * here for the transfer code to reach it at all.
 */

/**
 * [node]'s config as a *new* node should carry it: verbatim, except for the values
 * that are only ever minted and never copied.
 *
 * Copying an API token would leave two independently callable triggers behind one
 * secret, so rotating one would not rotate the other and a caller aiming at one
 * would authenticate against both.
 *
 * That is also just what the rest of the editor already does. `initialConfig` mints a
 * token for every `trigger.api` however it was placed, on the argument that a node
 * must not "work or not depending on how it was made" — and a duplicate, and an
 * import, are both a node being made. The price is that the new trigger needs its new
 * key copied into whatever calls it, which is correct: it is a different endpoint.
 */
fun reissuedConfig(node: WorkflowNode): Map<ConfigKey, String> = when (node.typeId) {
    API_TRIGGER_TYPE_ID -> node.config + (API_TOKEN_KEY to ApiTokens.generate())
    else -> node.config
}

/**
 * [node]'s config as a file leaving this device should carry it: verbatim, except
 * that every credential is **removed** rather than blanked.
 *
 * The export-side sibling of [reissuedConfig], and the two are deliberately a pair —
 * a key stripped here is a key [reissuedConfig] mints on the way back in, so a
 * round trip yields a working node with a *different* endpoint. Anything else means
 * a live bearer token sitting in a file that is, by the whole point of this feature,
 * about to be sent to somebody.
 *
 * Removed rather than set to `""` because [ApiTokens.matches] treats a blank expected
 * token as never matching, which is the same end state, and an absent key is what a
 * node that has never been configured looks like — so the exported file says "this
 * has no token" in the vocabulary the rest of the system already reads, rather than
 * inventing an empty-string special case.
 */
fun strippedConfig(node: WorkflowNode): Map<ConfigKey, String> = when (node.typeId) {
    API_TRIGGER_TYPE_ID -> node.config - API_TOKEN_KEY
    else -> node.config
}
