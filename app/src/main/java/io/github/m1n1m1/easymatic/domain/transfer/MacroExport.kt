package io.github.m1n1m1.easymatic.domain.transfer

import io.github.m1n1m1.easymatic.domain.model.GeofencePlace
import io.github.m1n1m1.easymatic.domain.model.NfcTag
import io.github.m1n1m1.easymatic.domain.model.VariableDeclaration
import io.github.m1n1m1.easymatic.domain.model.Workflow
import kotlinx.serialization.Serializable

/** What every export file says it is, so a file that is not one can be refused by name. */
const val MACRO_EXPORT_FORMAT = "easymatic.macro"

/**
 * The envelope's own version, which moves when *this file's* shape changes and is
 * unrelated to [Workflow.CURRENT_SCHEMA_VERSION], which moves when the graph's does.
 *
 * Two numbers rather than one because they fail in opposite directions. A graph from
 * an older schema cannot be read at all — `WorkflowRepository` discards rather than
 * migrates, for reasons its KDoc gives — while an envelope from an older version can
 * be, since a field added here arrives with a default. So the graph version is
 * checked with `<` and this one only with `>`.
 */
const val MACRO_EXPORT_VERSION = 1

/**
 * A macro packaged to leave the device: the graph itself, the credential-free library
 * entries it points at, and a statement of what it still needs on the other side.
 *
 * **The exchange format is the storage format.** The `workflow` field is the ordinary
 * [Workflow] serializer, unchanged and unwrapped, because a node's config is a flat
 * `Map<ConfigKey, String>` whose keys are derived at runtime from each node's config
 * class — a second encoder would have to know those classes, which is impossible by
 * construction for a plugin node whose class this app has never compiled against.
 * `ignoreUnknownKeys` is the forward-compatibility story on both sides and only works
 * on a self-describing format, which is also why this is not a bit format: the saving
 * would be tens of kilobytes of text, against giving up every property that makes a
 * macro readable, diffable and pasteable into a bug report.
 *
 * [schemaVersion] is a **declared property with no default on the encode side** — the
 * export encoder sets `encodeDefaults = true` precisely so this key is always written.
 * `WorkflowRepository`'s own encoder does not, which is why a workflow file on disk
 * never carries one and its version gate never fires; a file that has travelled has
 * no such excuse, since the app that reads it may be older than the app that wrote it.
 */
@Serializable
data class MacroExport(
    /**
     * Deliberately **without a default**, which is the only thing standing between this
     * decoder and every JSON object on the phone.
     *
     * Every other property here has one, so with a default on this one too an empty
     * object — or a photo's sidecar, or any unrelated file — decodes cleanly into an
     * envelope that then claims to be ours, and imports as a blank macro. A required
     * property makes the absence of this key a decode failure, which is exactly what
     * "this is not one of our files" should be. Costs nothing to write, since the
     * exporter always sets it.
     */
    val format: String,
    val formatVersion: Int = MACRO_EXPORT_VERSION,
    val schemaVersion: Int = Workflow.CURRENT_SCHEMA_VERSION,
    val appVersion: String = "",
    val workflow: Workflow = Workflow(),
    val bundled: BundledLibrary = BundledLibrary(),
    val requires: Requirements = Requirements(),
)

/**
 * The library entries travelling *with* the macro, carried by value so the graph's
 * references resolve on arrival without being rewritten.
 *
 * Only three of the fourteen libraries a config value can point into are here, and the
 * line between them is not importance but **secrecy**: these three are pure data, where
 * an AI connection, a smart-home or Home Assistant or MQTT hub, and a mail account each
 * hold a credential sealed under the Keystore. Those cannot travel and must not — so
 * they are named in [Requirements.setup] instead, which tells the recipient what to set
 * up without handing them anything. A calendar, a sound URI and a `contact:` phone ref
 * are excluded for the different reason that they name rows in a provider on *this*
 * phone, so a copy would be meaningless rather than dangerous.
 *
 * Ids are preserved, never re-minted. That is what makes the graph's refs resolve
 * untouched, and it is safe because every id here is a UUID or a hardware uid.
 */
@Serializable
data class BundledLibrary(
    val globals: List<VariableDeclaration> = emptyList(),
    val places: List<GeofencePlace> = emptyList(),
    val nfcTags: List<NfcTag> = emptyList(),
)

/**
 * What the receiving device must supply itself, stated so an import can say it plainly
 * rather than leaving the user to find a silently dead node.
 *
 * Nothing here blocks an import. Each entry corresponds to something the app already
 * reports on its own once the macro is in — an uninstalled plugin quarantines its own
 * node in `GraphValidator`, an unresolvable ref lands in the Problems panel — so this
 * is about saying it *at the moment of import*, when the user still has the context to
 * act on it, rather than about detecting anything new.
 *
 * [setup] holds the stable keys of [SetupNeed] rather than sentences, and that is
 * forced rather than tidy: this list is *written into a file that travels*, so a
 * sentence would arrive in the exporter's language and be shown, unreadable, to a
 * reader in another. A key is translated at the point of display, where the reader's
 * locale is the one in scope. An unrecognised key — one a newer version added — is
 * skipped rather than shown raw, which is why this is a `List<String>` and not an
 * enum: a `@Serializable` enum throws on an unknown member and would fail the whole
 * import over a line that is only ever advisory.
 *
 * [plugins] and [apps] hold package names, which need no such treatment because they
 * *are* portable — the same package identifies the same app on any device, which is
 * what makes them actionable rather than merely descriptive.
 */
@Serializable
data class Requirements(
    val plugins: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val setup: List<String> = emptyList(),
)

/**
 * The things an import can discover a macro needs but cannot supply.
 *
 * Stable strings, never renamed once shipped: they are persisted in exported files,
 * so changing one silently drops the notice from every macro exported before the
 * change. Adding one is free — an older reader skips what it does not know.
 */
object SetupNeed {
    const val AI_MODEL = "ai_model"
    const val SMART_HOME_HUB = "smart_home_hub"
    const val MAIL_ACCOUNT = "mail_account"
    const val CALENDAR = "calendar"
    const val SOUND = "sound"
    const val CONTACT = "contact"
}
