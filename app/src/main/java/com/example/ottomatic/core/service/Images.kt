package com.example.ottomatic.core.service

/**
 * Finding, reading and changing the pictures on the phone, for the `action.image_*` nodes,
 * `trigger.image_saved` and `value.latest_image`.
 *
 * Reached from `ExecutionContext` and nothing else, on [Files]' and `SmartHome`'s reasoning.
 * **Nothing here throws**: every member answers a result carrying an error string, or null, so
 * a node reports what happened and pulses `out` rather than unwinding a run. That includes the
 * `SecurityException` a missing grant produces and the `OutOfMemoryError` a very large decode
 * can, which is an `Error` rather than an `Exception` and so escapes a bare `runCatching`.
 *
 * **This is MediaStore, where [Files] is the filesystem, and the two are not rivals.** The
 * files subsystem rejected MediaStore for a reason that inverts exactly here: on Android 13+
 * no runtime permission reads *non-media* files another app wrote, so a PDF in `Download` is
 * reachable through SAF or not at all. `READ_MEDIA_IMAGES` does exist, so for pictures
 * MediaStore is both available and the better index — it is the only thing that can answer
 * "the newest photo", "everything in Screenshots" or "pictures taken last Tuesday", none of
 * which a folder grant can, because a grant knows a *directory* and MediaStore knows a
 * *collection*.
 *
 * **A picture is named by a handle that may be either a `content://` URI or a path**, read
 * through `com.example.ottomatic.domain.model.ImageRef`, and accepting both is forced rather
 * than indulgent. A path alone is not enough: `MediaStore.MediaColumns.DATA` is deprecated,
 * blank on some phones, and — the part that actually bites — **not openable by `java.io.File`
 * under scoped storage**, so a path this facade emitted could not be reopened by the node
 * after it. A URI alone is not enough either: `action.ai_describe` and every `action.file_*`
 * node take a `@FilePath`, so a URI-only family could not hand a picture to any of them.
 * Taking both keeps it **one field the user fills in one way** — the rule is that nobody is
 * made to choose between two platform mechanisms, not that only one may exist behind the
 * field. [ImageRecord] carries both spellings for the same reason.
 *
 * **The third facade to straddle both sides of the graph**, after `Variables` and
 * `HomeAssistant`: [latest] is `value.latest_image`'s read and is cheap enough to pull —
 * `value.calendar_busy`'s argument verbatim, a binder call into a local database with no
 * socket, no credential and no timeout — while everything else decodes bitmaps or asks the
 * user for permission and is an action's alone. [query] is deliberately *not* on the pull side
 * even though it is the same kind of call, because its answer depends entirely on config,
 * which is the property already keeping `value.variable` out of `sourceOptions()`.
 *
 * **Named for images and shaped for media.** A video family would add members here rather than
 * bring a second facade: the query, the trash and the consent ladder are identical, and only
 * the pixel operations are not.
 *
 * **Every member takes a plain `String` handle**, because `core` may not import `domain` —
 * [Files]' rule, and the enums here are unlabelled for the same reason, with each node
 * declaring its own `@Label`led copy and mapping onto these.
 */
interface Images {

    /** The pictures matching [spec], capped at [ImageLimits.MAX_LISTED]. */
    suspend fun query(spec: ImageQuery): ImageListing

    /**
     * The most recently added picture, or null when there is none or it cannot be read.
     *
     * The one member the pull side may call, and the only one answering a bare null:
     * "there are no pictures", "the grant is missing" and "the provider failed" collapse
     * here, and keeping them apart is [details]' job on the exec wire where the latency
     * shows. Null is the whole contract of a value node — the consumer falls back to its
     * own form value and a comparison fails closed.
     */
    suspend fun latest(): ImageRecord?

    /** Everything known about one picture: the collection's columns and the file's own EXIF. */
    suspend fun details(ref: String): ImageFacts

    /**
     * Decodes, transforms and re-encodes the picture at [ref], writing a **new** file.
     *
     * Bounded by [ImageLimits.MAX_DECODE_PIXELS] *while decoding* rather than by shrinking
     * afterwards, and serialised process-wide: this runs inside `MacroEngineService` beside
     * every armed macro, where two 12-megapixel decodes at once is ninety-six megabytes.
     *
     * **Never in place.** The original is left alone, so a macro cannot destroy a photo
     * nobody can get back — and the output is a file this app created, which is what keeps
     * the commonest operation clear of [ImageWrite.needsConfirmation] entirely.
     */
    suspend fun edit(ref: String, edit: ImageEdit): ImageWrite

    /**
     * Sets one [detail] on the picture at [ref], or **removes it when [value] is blank**.
     *
     * Blank-removes is why there is no separate "strip the location" node: it falls out of
     * the one rule the field already needs. It is also why this is one tag per node rather
     * than a form of twelve optional fields — with twelve, blank would have to mean both
     * "leave it alone" and "remove it", which no amount of copy can disambiguate.
     */
    suspend fun setMetadata(ref: String, detail: MetadataDetail, value: String): ImageWrite

    /** Copies or moves the picture at [ref] into [toFolder], pixels and metadata untouched. */
    suspend fun transfer(ref: String, toFolder: String, move: Boolean, whenExists: WhenExists): ImageWrite

    /**
     * Removes the picture at [ref], to the recoverable Trash where the platform has one.
     *
     * [toTrash] is a request rather than a promise: there is no Trash below Android 11, and
     * the caller is told so in the run log rather than quietly handed a permanent delete.
     * "Recoverable" silently becoming "gone" is not a degradation worth hiding.
     */
    suspend fun delete(ref: String, toTrash: Boolean): ImageWrite

    /**
     * The picture at [ref], shrunk and re-encoded small enough to show a model.
     *
     * **Here rather than on [Files] because it is a picture operation, not a read.**
     * `Files.readBytes` hands over the bytes on disk and refuses anything over
     * `FileLimits.MAX_READ_BYTES` — which is right for a facade that cannot know what
     * the bytes are, and wrong for every photo a phone camera produces. This decodes,
     * downscales to [ImageLimits.MODEL_LONGEST_SIDE] and re-encodes, so the size a model
     * gets is bounded by *construction* rather than by refusal.
     *
     * It also reaches pictures [Files] cannot: a camera photo is a row in the collection,
     * readable with the media grant and **no folder grant at all**, where the same path
     * through the file router needs one.
     */
    suspend fun encodeForModel(ref: String): ImageEncoded

    /**
     * Captures the screen and saves it as a new picture.
     *
     * **The one member that creates a picture out of nothing**, where every other one
     * finds or changes one that already existed. It lives here rather than behind a
     * facade of its own on this interface's own stated rule — *"named for images and
     * shaped for media"* — and on a harder constraint: what comes back is a row in this
     * collection, and the write path that makes one is `internal` to the media layer, so
     * the capture-and-save pair cannot be composed anywhere else without opening that up.
     *
     * Blank [toFolder] means the folder this phone already keeps screenshots in, which
     * differs between phones and is the implementation's business to know. Blank [name]
     * generates one in Android's own `Screenshot_<date>_<time>.png` shape.
     *
     * **The result is always a row Ottomatic owns**, so no consent ladder applies on any
     * version — the property that also keeps `action.image_edit` clear of it.
     *
     * Never throws. No accessibility access, an Android older than 11, an app that
     * forbids screenshots, the platform's one-a-second rate limit and a failed write all
     * come back as [ImageWrite.error] with `changed = false`.
     */
    suspend fun capture(toFolder: String, name: String, whenExists: WhenExists): ImageWrite

    /**
     * The most recent screenshot on this phone, or null when there is none or it cannot
     * be read.
     *
     * The **second** member the pull side may call, and it qualifies on exactly [latest]'s
     * reasoning: a bounded cursor read is cheap, repeatable and answers a bare null, which
     * is the whole contract of a value node. It is a separate member rather than a [query]
     * with a folder because *which* folder is not something a caller can be asked to know
     * — see the note on [capture].
     */
    suspend fun latestScreenshot(): ImageRecord?

    /**
     * Whether this phone has a recoverable bin for pictures at all (Android 11+).
     *
     * A **capability rather than an outcome**, and it is on the facade rather than being
     * worked out by the node because `engine/` may not read `Build.VERSION` — the same
     * boundary that keeps every timestamp here in epoch millis. A plain value rather than
     * a `suspend fun` because it is a version check, not a call.
     *
     * It exists so `action.image_delete` can *announce* the degradation before performing
     * it. Without it the node would either stay silent — turning "move to the bin" into
     * "delete for good" with nothing said — or the facade would have to smuggle a warning
     * through [ImageWrite.error], where a non-blank error means the delete failed.
     */
    val hasRecoverableBin: Boolean get() = false
}

/**
 * One row of the picture collection.
 *
 * **Timestamps are epoch millis here and `DateTime` on the far side of the boundary**, because
 * `core` may not import `domain`. The engine converts, exactly as it does for
 * `CalendarEventRecord`.
 *
 * Both [uri] and [path] are carried, and neither is redundant — see the facade's KDoc. [path]
 * is blank rather than absent when the collection does not say, so a macro reading it gets
 * something it can test rather than a null it cannot.
 *
 * Unknown numbers are **-1, never 0** — `FileFacts`' rule, for its reason: a provider that
 * omits a column is not reporting a zero-byte, zero-pixel image, and a macro told otherwise
 * acts on it.
 */
data class ImageRecord(
    /** The row's `content://` URI. Always known for a row that exists. */
    val uri: String = "",
    /** Absolute filesystem path, when the collection still reports one. */
    val path: String = "",
    val name: String = "",
    /** Relative folder as a person recognises it — `DCIM/Camera`, `Pictures/Screenshots`. */
    val folder: String = "",
    val mimeType: String = "",
    val width: Int = -1,
    val height: Int = -1,
    val sizeBytes: Long = -1,
    /** When the shutter fired. -1 when the file does not say — a screenshot usually does not. */
    val takenAtEpochMs: Long = -1,
    /** When this phone learned about the file. Always known. */
    val addedAtEpochMs: Long = -1,
)

/** What `action.image_list` is looking for. */
data class ImageQuery(
    /** Absolute folder to look in, and everything under it. Blank is the whole collection. */
    val folder: String = "",
    /** A `FileGlob` pattern over the file name; blank matches everything. */
    val pattern: String = "",
    val takenAfterEpochMs: Long = UNBOUNDED,
    val takenBeforeEpochMs: Long = UNBOUNDED,
    val newestFirst: Boolean = true,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        /** No bound on this end of the range. -1, on [ImageRecord]'s rule. */
        const val UNBOUNDED: Long = -1
        const val DEFAULT_LIMIT: Int = 50
    }
}

/** The outcome of a [Images.query]. */
data class ImageListing(
    val images: List<ImageRecord> = emptyList(),
    val ok: Boolean = false,
    /** True when more matched than [ImageQuery.limit] or [ImageLimits.MAX_LISTED] allowed. */
    val truncated: Boolean = false,
    val error: String = "",
)

/**
 * What is known about one picture, the row and the file's own metadata together, because
 * nobody asking "what camera took this?" is thinking about which of the two answers it.
 *
 * [exists] is deliberately separate from [error], on `FileFacts`' rule: "there is no picture
 * there" is an answer, where "I could not find out" is a failure, and a node that collapsed
 * the two would make a revoked permission look like an empty gallery.
 *
 * [hasLocation] gates [latitude] and [longitude] rather than a sentinel doing it, because
 * **0.0, 0.0 is a real coordinate** — it is in the Gulf of Guinea — so there is no number here
 * that can mean "no location". [locationHidden] is the third state that pair still cannot
 * express: the photo *has* a location and Ottomatic is not allowed to read it, which from
 * Android 10 is what a missing `ACCESS_MEDIA_LOCATION` means. Without it, "this photo has no
 * location" and "I may not tell you" are the same answer — the collapse [Files]' KDoc argues
 * against, one layer further in.
 *
 * [exposureTime], [fNumber] and [focalLength] stay **text** because what somebody wants to
 * read is `1/250` and `f/1.8`; a number would have to pick one of the two spellings, and
 * `transform.convert` bridges it for anybody who wants to compare.
 */
data class ImageFacts(
    val exists: Boolean = false,
    val image: ImageRecord = ImageRecord(),
    /** Clockwise rotation a viewer should apply: 0, 90, 180, 270, or -1 when unstated. */
    val orientationDegrees: Int = -1,
    val cameraMake: String = "",
    val cameraModel: String = "",
    val isoSpeed: Int = -1,
    val exposureTime: String = "",
    val fNumber: String = "",
    val focalLength: String = "",
    val description: String = "",
    val hasLocation: Boolean = false,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** The photo has a location this app is not permitted to read. */
    val locationHidden: Boolean = false,
    val error: String = "",
)

/**
 * The outcome of a write, a transfer, a metadata change or a delete.
 *
 * Carries the whole [image] rather than a path and a name because a write is exactly where
 * those change: a collision renames the file, and an edit changes its size and dimensions.
 * `FileResult.name` learned the same lesson.
 *
 * [needsConfirmation] is the one field with no counterpart in `FileResult`, and it earns its
 * place because Android has an outcome `changed`-plus-`error` cannot express. From Android 11,
 * changing a photo another app saved needs the person holding the phone to tap Allow — so
 * **"there was nobody to ask" is worth retrying later**, where "you said no" and "the file is
 * gone" are not. A macro can branch on it; collapsing it into [error] would make a locked
 * screen indistinguishable from a refusal.
 */
data class ImageWrite(
    val changed: Boolean = false,
    val image: ImageRecord = ImageRecord(),
    /** Android wanted the user to confirm and there was no way to ask. Retryable. */
    val needsConfirmation: Boolean = false,
    val error: String = "",
)

/**
 * A picture prepared for a model.
 *
 * **Base64 rather than a `ByteArray`**, on [FileBytes]' reasoning and for its reason: every
 * provider wants exactly this string on the wire, so bytes would only be encoded again at the
 * point of use, holding both forms in the foreground service at once.
 *
 * [shrunk] is carried so the node can *say* the picture was made smaller. A downscale is the
 * right thing to do and still a thing the user should be told about — the run log is where
 * "the model saw a smaller version of this" belongs, on `action.ai_prompt`'s truncation rule.
 */
data class ImageEncoded(
    val base64: String = "",
    val mediaType: String = "",
    val width: Int = -1,
    val height: Int = -1,
    val shrunk: Boolean = false,
    val sourceWidth: Int = -1,
    val sourceHeight: Int = -1,
    val error: String = "",
)

/** What [Images.edit] should do to the pixels. */
data class ImageEdit(
    val operation: ImageOperation = ImageOperation.RESIZE,
    /** Longest side in pixels, for [ImageOperation.RESIZE]. The aspect ratio is always kept. */
    val maxSide: Int = DEFAULT_MAX_SIDE,
    /** Clockwise degrees, for [ImageOperation.ROTATE]. 90, 180 or 270. */
    val turnDegrees: Int = QUARTER_TURN,
    /** For [ImageOperation.FLIP]: mirror left-to-right rather than top-to-bottom. */
    val flipHorizontal: Boolean = true,
    val cropX: Int = 0,
    val cropY: Int = 0,
    val cropWidth: Int = 0,
    val cropHeight: Int = 0,
    /** Blank keeps whatever the source was. */
    val format: ImageFormat = ImageFormat.SAME,
    /** 1-100, ignored by [ImageFormat.PNG], which is lossless. */
    val quality: Int = DEFAULT_QUALITY,
    /** Absolute folder for the result. Blank means the app's own `Pictures/Ottomatic`. */
    val toFolder: String = "",
    /** File name for the result. Blank derives one from the source. */
    val name: String = "",
    val whenExists: WhenExists = WhenExists.KEEP_BOTH,
) {
    companion object {
        const val DEFAULT_MAX_SIDE: Int = 1920
        const val QUARTER_TURN: Int = 90
        const val DEFAULT_QUALITY: Int = 90
    }
}

/** Which pixel operation [Images.edit] performs. */
enum class ImageOperation { RESIZE, ROTATE, FLIP, CROP, CONVERT }

/** What [Images.edit] writes out as. [SAME] keeps the source's format. */
enum class ImageFormat { SAME, JPEG, PNG, WEBP }

/**
 * Which piece of a picture's metadata [Images.setMetadata] changes.
 *
 * A closed set, which is why the node renders it as an enum rather than as a `@Picker` over
 * typed tag names: the answer set is knowable and complete, so there is nothing a typed tag
 * could reach that the list cannot. That is `PickerKind.HA_ENTITY`'s completeness test, and a
 * mistyped EXIF tag would otherwise fail exactly the way a mistyped identifier does —
 * silently, by naming something else.
 */
enum class MetadataDetail {
    DESCRIPTION,
    DATE_TAKEN,
    /** `"47.07, 15.44"`. Blank removes every GPS tag, which is the privacy case. */
    LOCATION,
    ORIENTATION,
    CAMERA_MAKE,
    CAMERA_MODEL,
    ARTIST,
    COPYRIGHT,
}

/** Bounds the image nodes announce rather than enforce silently. */
object ImageLimits {

    /**
     * The most rows one [Images.query] may return.
     *
     * `action.for_each` caps iterations anyway; this exists so a phone with forty thousand
     * photos on it does not build the list in the first place.
     */
    const val MAX_LISTED: Int = 2000

    /**
     * The most pixels one decode may hold in memory.
     *
     * **This is the bound that keeps the engine alive.** An edit runs inside
     * `MacroEngineService`, beside every armed macro, and a decoded bitmap costs four bytes a
     * pixel — so a 50-megapixel phone photo decoded whole is two hundred megabytes and is not
     * a slow edit but an out-of-memory kill that takes every other macro with it. Twelve
     * megapixels is forty-eight, which is survivable.
     *
     * A cap and not a target: a source above it is **downsampled and the run log says so**, on
     * `action.ai_prompt`'s rule, because a silently shrunk photo is real output a macro will
     * happily go on to send.
     */
    const val MAX_DECODE_PIXELS: Int = 12_000_000

    /**
     * A source file above this is refused before anything is decoded at all.
     *
     * Reading the bounds is cheap but not free, and a half-gigabyte "image" is a mistake
     * rather than a photo.
     */
    const val MAX_SOURCE_BYTES: Long = 128L * 1024 * 1024

    /** Bytes per pixel of a decoded `ARGB_8888` bitmap, which is what [MAX_DECODE_PIXELS] counts. */
    const val BYTES_PER_PIXEL: Int = 4

    /**
     * The longest side a picture is shrunk to before it is shown to an AI model.
     *
     * **Not a guess and not this app's opinion**: it is the size the providers
     * themselves recommend, above which they downscale server-side anyway. Sending more
     * costs upload time and tokens to transmit detail the model then discards, so the
     * cap makes the feature both work and cost less.
     */
    const val MODEL_LONGEST_SIDE: Int = 1568

    /** JPEG quality for that re-encode. High enough that no model can tell. */
    const val MODEL_QUALITY: Int = 85

    /**
     * How long the watcher waits for a burst to finish before looking.
     *
     * Longer than the calendar's, because burst mode and a messenger's media import both
     * insert dozens of rows over several seconds and one look should cover the lot.
     */
    const val CHANGE_DEBOUNCE_MS: Long = 1500

    /**
     * The most pictures one scan may fire for.
     *
     * Importing five hundred photos must not run a macro five hundred times. Over this, the
     * newest are reported, the mark is advanced past the rest, and the run log says how many
     * were skipped — a silent cap here would read as the trigger being unreliable.
     */
    const val MAX_NEW_PER_SCAN: Int = 20

    /**
     * How far back a scan looks behind its own high-water mark, in seconds.
     *
     * **This exists for `IS_PENDING`, and without it a camera photo is missed entirely.** From
     * Android 10 a camera app inserts its row *first*, marked pending and invisible to us, then
     * fills in the bytes and publishes. The row id was allocated at insert time, so by the time
     * the photo appears a later id may already have advanced the mark past it — and the picture
     * everybody actually wanted would never be reported. Looking back over `DATE_ADDED` catches
     * it; the already-fired ring is what stops the overlap from reporting it twice.
     */
    const val PENDING_LOOKBACK_SECONDS: Long = 120

    /** How many recently-fired ids the watcher remembers, to make the lookback above safe. */
    const val FIRED_RING_SIZE: Int = 64
}

/** The engine's default: no picture access, failing closed with a sentence that says so. */
object NoImages : Images {
    override suspend fun query(spec: ImageQuery) = ImageListing(error = UNAVAILABLE)
    override suspend fun latest(): ImageRecord? = null
    override suspend fun details(ref: String) = ImageFacts(error = UNAVAILABLE)
    override suspend fun edit(ref: String, edit: ImageEdit) = ImageWrite(error = UNAVAILABLE)
    override suspend fun setMetadata(ref: String, detail: MetadataDetail, value: String) =
        ImageWrite(error = UNAVAILABLE)

    override suspend fun transfer(ref: String, toFolder: String, move: Boolean, whenExists: WhenExists) =
        ImageWrite(error = UNAVAILABLE)

    override suspend fun delete(ref: String, toTrash: Boolean) = ImageWrite(error = UNAVAILABLE)
    override suspend fun encodeForModel(ref: String) = ImageEncoded(error = UNAVAILABLE)

    override suspend fun capture(toFolder: String, name: String, whenExists: WhenExists) =
        ImageWrite(error = UNAVAILABLE)

    override suspend fun latestScreenshot(): ImageRecord? = null

    private const val UNAVAILABLE = "Picture access is not available on this phone"
}
