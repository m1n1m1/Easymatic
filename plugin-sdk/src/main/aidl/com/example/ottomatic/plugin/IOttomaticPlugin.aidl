package com.example.ottomatic.plugin;

import com.example.ottomatic.plugin.ITriggerCallback;

/**
 * What a plugin app exports, and the whole of what Ottomatic may ask of it.
 *
 * Every payload is a JSON document rather than a Parcelable, and that is a
 * forward-compatibility decision. A Parcelable is read back by field order, so a
 * plugin built against protocol 1 talking to a host at protocol 2 does not fail — it
 * reads a shifted field and carries on with plausible nonsense. JSON with
 * `ignoreUnknownKeys` makes the same skew a no-op instead.
 *
 * It is also why nothing here can carry a capability. There is no Uri, no
 * PendingIntent, no IBinder and no ParcelFileDescriptor in any of these payloads, and
 * there must never be: a binder call already runs in the plugin's process under the
 * plugin's uid, so the host's own permissions are not borrowable — right up until
 * something helpfully hands back a Uri with FLAG_GRANT_READ_URI_PERMISSION.
 *
 * Every method is synchronous and may block the calling binder thread. The host calls
 * every one of them off its main thread, under a timeout chosen per method.
 *
 * There is deliberately no protocolVersion() transaction. One existed until protocol 2
 * and the host never called it: the version comparison that actually runs is against
 * PluginManifestWire.protocolVersion, inside PluginDeclarationValidator. Two answers to
 * the same question, able to disagree, are worse than one — and the manifest is already
 * size-bounded and parsed with ignoreUnknownKeys, so reading a stranger's document first
 * costs nothing.
 */
interface IOttomaticPlugin {

    /** Every node this plugin declares, as one PluginManifestWire JSON document. */
    String declarations();

    /**
     * Whether this plugin can currently do its work, as a PluginStatusWire.
     *
     * The question no permission check reaches: a plugin holds every permission it asked
     * for and still does nothing when nobody has signed in. The answer is a sentence in
     * the plugin's own words, which the host raises as a warning that blocks nothing.
     *
     * Asked of the plugin rather than of a node, because "nobody is signed in" is a fact
     * about the app. A plugin that cannot answer leaves the host saying nothing at all,
     * which is the deliberate choice over badging every node on a guess.
     */
    String status();

    /** Runs an action. Takes a NodeCallWire, answers an ActionResultWire. */
    String runAction(String typeId, String requestJson);

    /** Reads a value. Takes a NodeCallWire, answers a ValueResultWire. */
    String readValue(String typeId, String requestJson);

    /** Runs a transform. Takes a NodeCallWire, answers a ValueResultWire. */
    String runTransform(String typeId, String requestJson);

    /**
     * Lists what a @PluginChoice field may be set to, as a ChoiceListWire.
     *
     * source is the key the field declared — "pages", "boards" — and is opaque to the
     * host, which passes it back untouched. requestJson is a NodeCallWire carrying the
     * node's config so far, so a field scoped by a sibling can narrow on it; its data map
     * is always empty, because nothing has been wired at the moment somebody opens a
     * chooser.
     *
     * Called from the editor, not from a run. Everything this can answer is the plugin's
     * own; nothing of the host's is reachable through it.
     */
    String choices(String typeId, String source, String requestJson);

    /**
     * Arms a trigger under armId, which the host owns and the plugin only echoes back.
     *
     * Events arrive on callback until disarmTrigger is called with the same armId, or
     * until the binding drops — at which point the plugin must release whatever it
     * registered, because the host will arm again rather than resume.
     */
    void armTrigger(String typeId, String armId, String requestJson, ITriggerCallback callback);

    /** Releases armId. Must be safe to call for an armId that is already gone. */
    void disarmTrigger(String armId);
}
