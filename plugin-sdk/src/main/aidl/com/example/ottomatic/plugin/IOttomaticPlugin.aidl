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
 * All six methods are synchronous and may block the calling binder thread. The host
 * calls every one of them off its main thread, under a timeout chosen per method.
 */
interface IOttomaticPlugin {

    /**
     * The wire-format version this plugin speaks.
     *
     * Checked on every bind, before a single declaration is read. This is deliberately
     * the *only* version comparison in the plugin system — there is no check of a
     * plugin's versionCode, because a downgrade is as legitimate as an upgrade and
     * neither says anything about the wire.
     */
    int protocolVersion();

    /** Every node this plugin declares, as one PluginManifestWire JSON document. */
    String declarations();

    /** Runs an action. Takes a NodeCallWire, answers an ActionResultWire. */
    String runAction(String typeId, String requestJson);

    /** Reads a value. Takes a NodeCallWire, answers a ValueResultWire. */
    String readValue(String typeId, String requestJson);

    /** Runs a transform. Takes a NodeCallWire, answers a ValueResultWire. */
    String runTransform(String typeId, String requestJson);

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
