package com.example.ottomatic.domain.model

/**
 * Platform-agnostic icon for a node type. The UI layer maps every entry to a
 * concrete vector asset with an exhaustive `when`
 * (`feature/grapheditor/nodeIcon`), so an icon cannot be referenced by a node
 * without also being drawn — the previous string keys silently fell back to a
 * generic placeholder when misspelled.
 */
enum class NodeIcon {
    BOLT,
    SPLIT,
    TIMER,
    SCHEDULE,
    NOTIFICATION,
    SMS,
    SEND,
    MAIL,
    HTTP,
    WIFI,
    NFC,
    BLUETOOTH,
    VOLUME,
    MUSIC,
    MUSIC_OFF,
    DND,
    LOCATION,
    BOOT,
    BATTERY_LEVEL,
    BATTERY_CHARGING,
    CONVERT,
    TEXT,
    JSON,
    CODE,
    VARIABLE,
    ORIENTATION,
    SHAKE,
    TAP,
    MOTION,
    PROXIMITY,
    LIGHT,
    POWER_SAVE,
    HEADSET,
    DOCK,
    DARK_MODE,
    LOOP,
    LIST,
    DIALOG,
    QUESTION,
    INPUT,
    CHOICE,
    LIGHTBULB,
    SCENE,

    /**
     * A messenger conversation. Distinct from [SMS], which is one specific transport,
     * and from [DIALOG], which is Ottomatic asking the *user* something rather than
     * another person messaging them.
     */
    CHAT,

    /**
     * A language model being asked something. Distinct from [CHAT], which is a
     * conversation with a *person* in a messenger, and from [SCENE]'s sparkles,
     * which are a saved lighting arrangement.
     */
    AI,

    /**
     * A smart home as a whole, rather than one thing in it.
     *
     * Distinct from [LIGHTBULB] and [SCENE], which are the two things a *light* node
     * acts on: this marks the nodes that speak to the hub itself and reach everything
     * else in the house — a thermostat, a door sensor, a media player, an arbitrary
     * service. On the palette that difference is the useful one, because it is what
     * tells somebody looking for "when the front door opens" not to go on reading the
     * lighting nodes.
     */
    HOME,

    /**
     * One file on the device's storage — read, written, copied or deleted.
     *
     * Distinct from [FOLDER], and the distinction is the useful one on a canvas
     * rather than a decorative one: at a glance it separates the four nodes that act
     * on a single named file from the one that answers with a folder's contents.
     */
    FILE,

    /** A folder, and what is inside it. See [FILE] for why the two are separate. */
    FOLDER,

    /**
     * An appointment in a calendar.
     *
     * Distinct from [SCHEDULE], which it would otherwise be tempting to reuse, and the
     * difference is the one that matters on a canvas: [SCHEDULE] is a *rule the app
     * follows* — every Monday at seven — where this is an entry in a diary somebody else
     * keeps. A macro that mixes the two is a macro whose author has to read the card to
     * tell which is which.
     */
    CALENDAR,

    /**
     * A picture on the phone.
     *
     * Distinct from [FILE] on the same argument that separates [FILE] from [FOLDER]: a
     * photo and a text file are both files, but the nodes that act on them address
     * different things — one a path under a granted folder, the other a row in the media
     * collection — and a canvas mixing them would make "which of these can I wire
     * together?" a question the card no longer answers.
     */
    IMAGE,

    /**
     * Changing a picture's pixels, as opposed to finding or administering one.
     *
     * The second image icon, and it earns its place the way [MUSIC_OFF] does beside
     * [MUSIC]: six of the seven image nodes read a photo or move it about, and exactly
     * one rewrites it. That is the difference somebody scanning a graph for "where does
     * this photo get changed?" is looking for.
     */
    IMAGE_EDIT,
}
