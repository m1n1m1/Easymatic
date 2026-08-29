package com.example.ottomatic.core.permissions

/**
 * A manifest permission name. Wrapped in a value class so permission constants
 * are typed and discoverable, and so callers don't pass arbitrary strings
 * where a permission is expected.
 */
@JvmInline
value class Permission(val manifest: String) {
    /** Last segment of the manifest name, e.g. `ACCESS_FINE_LOCATION`. */
    val simpleName: String get() = manifest.substringAfterLast('.')
}

/**
 * Catalogue of permissions used across Ottomatic. Centralised here so the
 * permission request flow and the trigger that depends on each permission
 * reference the same constant.
 */
object Permissions {
    val RECEIVE_SMS = Permission("android.permission.RECEIVE_SMS")
    val SEND_SMS = Permission("android.permission.SEND_SMS")
    val CALL_PHONE = Permission("android.permission.CALL_PHONE")

    /**
     * Knowing whether a call is going on, which is what gates delivery of the
     * `PHONE_STATE` broadcast and what `TelecomManager.isInCall` checks for.
     *
     * The manifest has held this since the call trigger was written, but no node
     * declared it — so somebody who denied it got a macro that looked armed and simply
     * never fired, with nothing anywhere saying so. It buys the *state* only: the
     * caller's number rides on `READ_CALL_LOG`, which Ottomatic deliberately does not
     * ask for. The caller's **name** arrives by another road entirely — the dialer's own
     * call notification — so declining that permission costs nothing this app shows.
     */
    val READ_PHONE_STATE = Permission("android.permission.READ_PHONE_STATE")

    /**
     * Reading the address book. Needed only to *resolve* a chosen contact when a
     * node runs — choosing one costs nothing, because the system picker hands its
     * row back under a transient grant. That is why no node declares this
     * statically; see `usesContacts`.
     */
    val READ_CONTACTS = Permission("android.permission.READ_CONTACTS")

    /**
     * Reading the device's calendars and the events in them.
     *
     * Declared *statically* by every calendar node, which is the opposite call from
     * [READ_CONTACTS] and worth saying why: there is no chooser here that hands back
     * a row under a transient grant. The system offers no calendar picker at all, so
     * Ottomatic lists the calendars itself — which needs this grant before the config
     * form can show anything, not merely when a node runs.
     */
    val READ_CALENDAR = Permission("android.permission.READ_CALENDAR")

    /**
     * Creating, changing and deleting calendar events.
     *
     * Separate from [READ_CALENDAR] because the platform separates them, and because
     * the split is real to a user: a macro that reads the day's meetings is a very
     * different proposition from one that can delete them. Only the three writing
     * operations of `action.calendar_update` and `action.calendar_add` declare it.
     */
    val WRITE_CALENDAR = Permission("android.permission.WRITE_CALENDAR")
    val POST_NOTIFICATIONS = Permission("android.permission.POST_NOTIFICATIONS")
    val ACCESS_FINE_LOCATION = Permission("android.permission.ACCESS_FINE_LOCATION")
    val ACCESS_COARSE_LOCATION = Permission("android.permission.ACCESS_COARSE_LOCATION")
    val ACCESS_BACKGROUND_LOCATION = Permission("android.permission.ACCESS_BACKGROUND_LOCATION")

    /**
     * Talking to the Bluetooth adapter, from API 31. Below that the platform does
     * not know the name at all and `BLUETOOTH` — an install-time permission — is
     * what applies, so a check against this one answers *denied* on an older
     * phone where nothing is actually wrong. See `AndroidSystemServices.setBluetooth`.
     */
    val BLUETOOTH_CONNECT = Permission("android.permission.BLUETOOTH_CONNECT")

    /**
     * Do-Not-Disturb policy access. Not a standard runtime permission — the
     * user must grant it on the `ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`
     * page (see `MainActivity.requestDndPermissionIfNeeded`). The
     * [PermissionChecker] handles this name specially via
     * `NotificationManager.isNotificationPolicyAccessGranted`.
     */
    val ACCESS_NOTIFICATION_POLICY = Permission("android.permission.ACCESS_NOTIFICATION_POLICY")

    /**
     * Reading the pictures in the shared media collection.
     *
     * **Declared by every image node on every API**, including the ones where the
     * platform has never heard of the name — see [onApi], which substitutes the name
     * the platform does know. Declaring the *modern* name rather than branching is
     * what keeps [PermissionRequirement.key] stable, so `GrantedPrerequisites`, the
     * Permissions screen and a saved workflow all go on meaning the same thing as the
     * fleet's API floor rises.
     */
    val READ_MEDIA_IMAGES = Permission("android.permission.READ_MEDIA_IMAGES")

    /**
     * The camera, wanted for two different things, only one of which uses it.
     *
     * **Declared by `action.camera_photo`**, which opens the camera in-process through
     * camera2 and photographs with no camera app and nobody pressing a button. That is the
     * ordinary reading: a node that genuinely exercises a capability declares it, and the
     * Permissions screen, the Problems panel and the node's own card all follow from the
     * declaration.
     *
     * The second reason is stranger and outlives the first. Ottomatic also declares `CAMERA`
     * in its manifest for the torch, and Android refuses `ACTION_IMAGE_CAPTURE` **outright**
     * when an app has declared `CAMERA` without holding it — even though the photograph
     * would be taken by *another* app under its own grant. So an `@IntentChoice` naming the
     * capture action has to ask for this too, and dropping the manifest declaration to avoid
     * that would take the torch node with it.
     */
    val CAMERA = Permission("android.permission.CAMERA")

    /**
     * What [READ_MEDIA_IMAGES] is called at or below API 32. **Never declared by a
     * node** — [onApi] substitutes it, and a node naming it directly would report a
     * different `key` on old and new phones for one capability.
     */
    val READ_EXTERNAL_STORAGE = Permission("android.permission.READ_EXTERNAL_STORAGE")

    /**
     * Changing or deleting a picture another app saved, at or below API 28 and
     * nowhere else.
     *
     * From API 29 there is no permission for this at all: the platform asks the user
     * per operation through an `IntentSender`. That is why `existsOnThisApi` reports
     * this **granted** above 28 rather than denied — there is nothing there to grant,
     * and a row saying otherwise would be a permanent false alarm. See `MediaConsents`.
     */
    val WRITE_EXTERNAL_STORAGE = Permission("android.permission.WRITE_EXTERNAL_STORAGE")

    /**
     * The GPS tags inside a picture.
     *
     * From API 29 MediaStore strips them out of the bytes it hands over unless this is
     * held *and* the URI has been through `MediaStore.setRequireOriginal` — both, not
     * either. Declared by `action.image_info` alone, statically rather than derived from
     * config the way `usesContacts` is, because there is no configuration of that node
     * in which it does not want the grant: its struct always carries a latitude and a
     * longitude, so the badge is never a lie.
     */
    val ACCESS_MEDIA_LOCATION = Permission("android.permission.ACCESS_MEDIA_LOCATION")

    /**
     * The microphone. Declared by the three recording actions and by nothing else.
     *
     * The trigger and the value of that family deliberately do not declare it, which is
     * the same split [READ_MEDIA_IMAGES] does not get to make: `trigger.recording_saved`
     * is told that a recording finished rather than listening to anything, and
     * `value.recording` reads a flag this process already holds. Badging either of them
     * would put an amber warning on a node that works perfectly without the grant.
     *
     * Holding it is not on its own enough to record from the background. From API 30 a
     * foreground service reaches the microphone only while its declared *type* says so,
     * and from API 34 naming that type additionally needs `FOREGROUND_SERVICE_MICROPHONE`
     * and this grant held at the moment `startForeground` runs — see `ServiceForeground`,
     * which claims the type for the length of one recording and drops it again.
     */
    val RECORD_AUDIO = Permission("android.permission.RECORD_AUDIO")
}

/**
 * The name this permission goes by on API [sdkInt].
 *
 * **The one thing `AndroidPermissionChecker.existsOnThisApi` cannot do.** That answers *is
 * there anything here to grant*, which is the right question for a permission the platform
 * has not invented yet. The media read split is a different shape: it is a **rename**, the
 * same capability under two names, and checking the wrong one reports a refusal on a phone
 * where everything works.
 *
 * Pure and parameterised rather than reading `Build.VERSION`, which is what lets it live here
 * in `:node-api` beside the constants it maps between — the module compiled without
 * `android.jar` — and be JVM-tested at every boundary rather than needing a device per API
 * level.
 *
 * **Three callers, and missing one is the bug this exists to make impossible**: the checker,
 * and the two places that launch the runtime dialog. A grant requested under one name and
 * checked under the other is a permission that can never be satisfied.
 */
fun Permission.onApi(sdkInt: Int): Permission = when {
    this == Permissions.READ_MEDIA_IMAGES && sdkInt < MEDIA_PERMISSION_SPLIT_API ->
        Permissions.READ_EXTERNAL_STORAGE

    else -> this
}

/** API 33, where the single storage read permission split into one per media type. */
const val MEDIA_PERMISSION_SPLIT_API: Int = 33

/** API 29, where scoped storage removed direct write access to another app's media. */
const val SCOPED_STORAGE_API: Int = 29
