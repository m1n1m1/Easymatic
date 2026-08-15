package com.example.ottomatic.core.service

/**
 * Reading and writing files on the device, for the six `action.file_*` nodes.
 *
 * Reached from `ExecutionContext` and nothing else, on `Mail`'s and `SmartHome`'s
 * reasoning. **Nothing here throws**: every member answers a result carrying an error
 * string, or null, so a node reports what happened and pulses `out` rather than
 * unwinding a run.
 *
 * **This is an action's facade and never a value node's.** Three reasons, and the
 * first is the one that generalises: there is no push channel here at all. `HomeAssistant.state`
 * and `Mqtt.lastMessage` are legal on the pull side because a socket keeps a map warm,
 * where a filesystem answers only when it is asked — this is `value.light_state`'s side
 * of that line, and a granted folder may be served by a cloud provider, making a "read"
 * a network call. Second, the answer depends entirely on config, which is the property
 * already keeping `value.variable` out of `sourceOptions()`, since a `val:` read is
 * performed with no config at all. Third, a value must fail closed to a single `null`,
 * and "the file is not there", "no grant covers this path" and "the provider failed"
 * are three different sentences somebody needs. `action.file_info` keeps them apart
 * with [FileFacts.exists] beside an error, on the exec wire where the latency shows.
 *
 * **Every member takes a plain path string rather than a parsed type**, because `core`
 * may not import `domain`. Each implementation reads it through
 * `com.example.ottomatic.domain.model.FilePath` and fails closed, which is `TimeOfDay`
 * shared by the schedule trigger and its picker: one reading of the text, two callers.
 */
interface Files {

    /**
     * The text in the file at [path], or a failure.
     *
     * Bounded by [FileLimits.MAX_READ_BYTES] **while the stream is read**, never by
     * reading the file and cutting the result down — this runs inside the engine's
     * foreground service, so a 200 MB video read into a `String` is not a slow read
     * but an out-of-memory kill that takes every other armed macro with it.
     */
    suspend fun readText(path: String, encoding: TextEncoding = TextEncoding.UTF_8): FileRead

    /** Writes [text] to [path], creating any folders above it. */
    suspend fun writeText(
        path: String,
        text: String,
        append: Boolean = false,
        whenExists: WhenExists = WhenExists.REPLACE,
        encoding: TextEncoding = TextEncoding.UTF_8,
    ): FileResult

    /**
     * The paths inside the folder at [path], name-sorted.
     *
     * **Full paths rather than names**, because every other member here takes a path:
     * emitting names would put a `transform.text` between `action.file_list` and every
     * node that acts on what it found. Sorted because a provider's own order is
     * unspecified, so "the first file" would otherwise mean different things on
     * different phones.
     */
    suspend fun list(
        path: String,
        pattern: String = "",
        show: ListFilter = ListFilter.FILES,
    ): FileListing

    /** What is known about [path], including whether anything is there at all. */
    suspend fun info(path: String): FileFacts

    /** Deletes the file at [path]. Folders are refused — see `action.file_delete`. */
    suspend fun delete(path: String): FileResult

    /** Copies or moves [from] to [to], content untouched. */
    suspend fun transfer(from: String, to: String, move: Boolean, whenExists: WhenExists): FileResult
}

/** How a write or a transfer behaves when something is already at the destination. */
enum class WhenExists {
    /** Overwrite it. */
    REPLACE,

    /**
     * Keep both, letting the new one be renamed — `notes (1).txt`.
     *
     * Named for what it does rather than for the mechanism, because the mechanism is
     * the surprise: `DocumentsContract.createDocument` **never overwrites**, so this
     * is what the platform does by default and [REPLACE] is the one that costs an
     * extra step. A node without this field would silently do this while its label
     * said otherwise.
     */
    KEEP_BOTH,

    /** Leave the existing one alone and do nothing, reporting `changed = false`. */
    SKIP,
}

/** Which entries a listing includes. */
enum class ListFilter { FILES, FOLDERS, BOTH }

/**
 * How bytes on disk map to text in the graph.
 *
 * Decoding is **total** — a byte that is not valid in the chosen encoding becomes
 * U+FFFD rather than an error — so reading a JPEG "succeeds" and yields nonsense.
 * That is stated in `action.file_read`'s own KDoc rather than guarded against with a
 * sniffer, because the honest sentence is short: this reads text, and a file that is
 * not text reads as rubbish.
 */
enum class TextEncoding { UTF_8, UTF_16, ISO_8859_1 }

/** Bounds the file nodes announce rather than enforce silently. */
object FileLimits {

    /**
     * The most text one read may produce.
     *
     * A cap and not a target. A truncated read still reaches the port, with a warning
     * in the run log — `action.ai_prompt`'s rule, for its reason: it is real output a
     * macro will happily send on, so silence would make half a file look like the
     * whole of one.
     */
    const val MAX_READ_BYTES: Int = 1024 * 1024

    /**
     * The most entries one listing may return.
     *
     * `action.for_each` caps iterations anyway; this exists so a folder with forty
     * thousand files in it does not build the list in the first place.
     */
    const val MAX_LISTED: Int = 2000
}

/** The outcome of a read. */
data class FileRead(
    val text: String = "",
    val ok: Boolean = false,
    /** True when the file was longer than [FileLimits.MAX_READ_BYTES]. */
    val truncated: Boolean = false,
    val error: String = "",
)

/**
 * The outcome of a write, a delete or a transfer.
 *
 * Carries [name] because **SAF renames silently**: asked to create `notes.txt` beside
 * an existing one, a provider answers with `notes (1).txt` and reports success, and it
 * may also append an extension of its own choosing. The name that actually exists is
 * therefore not something the caller can assume it knows.
 */
data class FileResult(
    val changed: Boolean = false,
    val path: String = "",
    val name: String = "",
    val error: String = "",
)

/** The outcome of a listing. */
data class FileListing(
    val paths: List<String> = emptyList(),
    val ok: Boolean = false,
    /** True when the folder held more than [FileLimits.MAX_LISTED] entries. */
    val truncated: Boolean = false,
    val error: String = "",
)

/**
 * What is known about one path.
 *
 * [exists] is deliberately separate from [error]: "there is no file there" is an
 * answer, where "I could not find out" is a failure, and a value node collapsing the
 * two onto one `null` is most of why there is no value node here.
 */
data class FileFacts(
    val exists: Boolean = false,
    val path: String = "",
    val name: String = "",
    val isFolder: Boolean = false,
    /** Size in bytes, or -1 when the provider does not say — never 0, which is a lie. */
    val sizeBytes: Long = -1,
    /** Last modified, epoch millis, or -1 when the provider does not say. */
    val modifiedEpochMs: Long = -1,
    val error: String = "",
)

/** The engine's default: no file access, failing closed with a sentence that says so. */
object NoFiles : Files {
    override suspend fun readText(path: String, encoding: TextEncoding) = FileRead(error = UNAVAILABLE)
    override suspend fun writeText(
        path: String,
        text: String,
        append: Boolean,
        whenExists: WhenExists,
        encoding: TextEncoding,
    ) = FileResult(error = UNAVAILABLE)

    override suspend fun list(path: String, pattern: String, show: ListFilter) = FileListing(error = UNAVAILABLE)
    override suspend fun info(path: String) = FileFacts(error = UNAVAILABLE)
    override suspend fun delete(path: String) = FileResult(error = UNAVAILABLE)
    override suspend fun transfer(from: String, to: String, move: Boolean, whenExists: WhenExists) =
        FileResult(error = UNAVAILABLE)

    private const val UNAVAILABLE = "File access is not available on this phone"
}
